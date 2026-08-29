package pl.jarvis.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.jarvis.app.ai.AIProvider
import pl.jarvis.app.ai.AIProviderException
import pl.jarvis.app.ai.AIProviderFactory
import pl.jarvis.app.ai.AIResponse
import pl.jarvis.app.actions.Action
import pl.jarvis.app.actions.ActionConfirmation
import pl.jarvis.app.actions.ActionExecutor
import pl.jarvis.app.actions.ActionMode
import pl.jarvis.app.actions.ActionResult
import pl.jarvis.app.actions.DirectActionExecutor
import pl.jarvis.app.actions.SmartActionDetector
import pl.jarvis.app.audio.AudioManager
import pl.jarvis.app.ble.ButtonAction
import pl.jarvis.app.ble.ButtonActionDetector
import pl.jarvis.app.ble.ConnectionState
import pl.jarvis.app.ble.JarvisManager
import pl.jarvis.app.camera.BurstCaptureManager
import pl.jarvis.app.conversation.ConversationContext
import pl.jarvis.app.conversation.ConversationalMode
import pl.jarvis.app.data.HistoryRepository
import pl.jarvis.app.data.RemoteModelValidator
import pl.jarvis.app.data.SettingsRepository
import pl.jarvis.app.persona.Persona
import pl.jarvis.app.persona.PersonaRegistry
import pl.jarvis.app.storage.PhotoStorage
import pl.jarvis.app.vision.OCRReader
import pl.jarvis.app.vision.OCRResult
import pl.jarvis.app.vision.QRScanner
import pl.jarvis.app.vision.ScannedCode
import pl.jarvis.app.web.URLAnalyzer
import pl.jarvis.app.web.WebContent

/**
 * Orkiestrator - koordynuje cały flow:
 * 1. Trigger (przycisk, text input, wake word)
 * 2. Capture (5 zdjęć co 1s przez JarvisManager)
 * 3. AI analysis (multimodal)
 * 4. TTS playback
 * 5. Zapis do historii
 */
class AIOrchestrator(
    private val context: Context,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val wakeWord: pl.jarvis.app.wakeword.WakeWordDetector? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val _lastResponse = MutableStateFlow<AIResponse?>(null)
    val lastResponse: StateFlow<AIResponse?> = _lastResponse.asStateFlow()

    // Ostrzeżenia o modelu (deprecated, migration, itd.)
    private val _modelWarning = MutableStateFlow<String?>(null)
    val modelWarning: StateFlow<String?> = _modelWarning.asStateFlow()

    // Aktualnie używany model
    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId.asStateFlow()

    // HeyCyan BLE manager (singleton z vendor SDK)
    private val heyCyan: JarvisManager = JarvisManager.getInstance(context)
    private val photoStorage: PhotoStorage = PhotoStorage(context)
    private val capture: BurstCaptureManager = BurstCaptureManager(
        context = context,
        photoStorage = photoStorage,
        heyCyan = heyCyan
    )

    // Power management - kontroluje co może działać
    val powerManager = pl.jarvis.app.power.PowerManager(context, settings)
    private val aiCache = pl.jarvis.app.ai.AIResponseCache()
    private val wakeLock = pl.jarvis.app.power.WakelockHelper(context)
    private val audio: AudioManager = AudioManager.getInstance(context)
    private val qrScanner: QRScanner = QRScanner()
    private val ocrReader: OCRReader = OCRReader()
    private val actionDetector = SmartActionDetector()
    private val actionExecutor = ActionExecutor(context)
    private val directActionExecutor = DirectActionExecutor(context)
    private val urlAnalyzer = URLAnalyzer()
    private val translator = pl.jarvis.app.translation.SimultaneousTranslator()
    private val longTermMemory = pl.jarvis.app.memory.LongTermMemory(context, history)
    private val conversationContext = ConversationContext()
    private val captureModeSelector = pl.jarvis.app.camera.CaptureModeSelector()

    // Tryb konwersacyjny (continuous listening)
    private val conversationalMode = ConversationalMode(
        audio = audio,
        wakeWord = wakeWord,
        speechToText = pl.jarvis.app.conversation.SpeechToText(context),
        onUserSpoke = { text -> handleUserTrigger(TriggerSource.VOICE, text) },
        onActivated = { Log.i(TAG, "Tryb konwersacyjny ON") },
        onDeactivated = { Log.i(TAG, "Tryb konwersacyjny OFF") }
    ).apply {
        // Rozpoznawanie ma słuchać w tym języku, w którym użytkownik mówi.
        recognitionLanguageTag = languageTagFor(settings.getResponseLanguage())
    }

    // Accessibility - dla niewidomych/słabowidzących
    val accessibility = pl.jarvis.app.accessibility.AccessibilityService(
        audio = audio,
        ocrReader = ocrReader,
        heyCyan = heyCyan,
        onDescribeScene = { photoBytes ->
            // Opis sceny ze zdjęcia. Bez zmyślonych odległości - model ich nie zmierzy.
            val text = buildString {
                append("Opisz krótko co widać na tym zdjęciu. ")
                append("Używaj kierunków słownych (na wprost/po lewej/po prawej). ")
                append("Bliskość opisuj względnie (blisko, kilka kroków dalej) - ")
                append("NIE podawaj odległości w metrach. ")
                append("Jeśli obraz jest niewyraźny, powiedz to. ")
                append("Bez ozdobników, tylko fakty. 1-2 zdania po polsku.")
            }
            val provider = getOrCreateProvider()
            val response = provider.analyze(
                textQuestion = text,
                images = listOf(photoBytes),
                audioBytes = null,
                scannedCodes = emptyList(),
                enableWebSearch = false,
                systemPrompt = ACCESSIBILITY_SYSTEM_PROMPT
            )
            response.text
        },
        onNavigate = { photoBytes ->
            // Opis drogi ze zdjęcia - pomoc uzupełniająca, nie system bezpieczeństwa.
            val text = buildString {
                append("Co widać na drodze przed osobą idącą? ")
                append("Schody, krawężnik, słupek, drzwi, przeszkoda? ")
                append("Odpowiedz krótko, np. \"Na wprost schody w dół\" albo ")
                append("\"Nie widzę wyraźnie\". ")
                append("NIE podawaj odległości w metrach i NIE mów, że droga jest wolna ")
                append("ani że można bezpiecznie iść - nie masz do tego podstaw.")
            }
            val provider = getOrCreateProvider()
            val response = provider.analyze(
                textQuestion = text,
                images = listOf(photoBytes),
                enableWebSearch = false,
                systemPrompt = ACCESSIBILITY_SYSTEM_PROMPT
            )
            response.text
        }
    )

    /**
     * Publiczny dostęp do trybu konwersacyjnego (UI/Settings).
     */
    val conversationalModeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = conversationalMode.enabled
    val isListeningFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = conversationalMode.isListening

    /**
     * Zamienia kod języka odpowiedzi na tag BCP-47 dla rozpoznawania mowy.
     * `SpeechRecognizer` oczekuje pełnego tagu z regionem - samo "pl" bywa
     * ignorowane i schodzi na język systemu.
     */
    private fun languageTagFor(languageCode: String): String = when (languageCode) {
        "pl" -> "pl-PL"
        "en" -> "en-US"
        "de" -> "de-DE"
        "fr" -> "fr-FR"
        "es" -> "es-ES"
        "it" -> "it-IT"
        "uk" -> "uk-UA"
        else -> languageCode
    }

    fun enableConversationalMode() {
        // Język mógł się zmienić w ustawieniach od czasu utworzenia orkiestratora.
        conversationalMode.recognitionLanguageTag =
            languageTagFor(settings.getResponseLanguage())
        conversationalMode.enable()
    }

    fun disableConversationalMode() = conversationalMode.disable()

    // Akcja oczekująca na potwierdzenie (null = nic nie czeka)
    private val _pendingActionConfirmation = MutableStateFlow<PendingActionConfirmation?>(null)
    val pendingActionConfirmation: StateFlow<PendingActionConfirmation?> =
        _pendingActionConfirmation.asStateFlow()
    private val buttonDetector = ButtonActionDetector()

    // Ostatnia rozmowa (do follow-up)
    private var lastQuestion: String = ""
    private var lastResponseText: String = ""

    private var currentProvider: AIProvider? = null
    private var currentProviderId: String? = null
    private var activeModelId: String? = null

    init {
        // Nasłuchuj akcji przycisku fizycznego
        scope.launch {
            heyCyan.buttonEvent.collect { event ->
                event?.let {
                    buttonDetector.processEvent(it)
                    heyCyan.consumeButtonEvent()
                }
            }
        }

        // Nasłuchuj zdetektowane akcje
        scope.launch {
            buttonDetector.action.collect { action ->
                handleButtonAction(action)
            }
        }
    }

    private fun handleButtonAction(action: ButtonAction) {
        Log.i(TAG, "Button action: $action")
        when (action) {
            ButtonAction.QUICK_QUESTION -> handleUserTrigger(TriggerSource.BUTTON, "")
            ButtonAction.FOLLOW_UP -> {
                val context = if (lastQuestion.isNotBlank()) {
                    "Kontynuacja: $lastQuestion. Co jeszcze?"
                } else {
                    "Co jeszcze powinienem wiedzieć?"
                }
                handleUserTrigger(TriggerSource.BUTTON, context)
            }
            ButtonAction.SCAN_QR -> {
                handleUserTrigger(TriggerSource.BUTTON, "Co jest na tym QR kodzie? Wyjaśnij krótko.")
            }
            ButtonAction.NEW_CONVERSATION -> {
                lastQuestion = ""
                lastResponseText = ""
                reset()
            }
        }
    }

    /**
     * Główny flow - wywoływany po naciśnięciu przycisku lub wpisaniu tekstu.
     */
    fun handleUserTrigger(
        trigger: TriggerSource,
        textQuestion: String = ""
    ) {
        if (_state.value !is OrchestratorState.Idle) {
            Log.w(TAG, "Already processing, ignoring trigger")
            return
        }

        // === SPRAWDŹ CZY TO AKCJA (nie wymaga AI) ===
        val actions = actionDetector.detect(textQuestion)
        if (actions.isNotEmpty()) {
            handleActions(actions, textQuestion)
            return
        }

        // Pytanie wpisane z klawiatury nie musi dotyczyć tego, co widać.
        // Wcześniej aplikacja odmawiała odpowiedzi na cokolwiek bez połączonych
        // okularów - także na "ile to 20 euro w złotych". Warstwa AI radzi sobie
        // bez obrazu (providerzy budują prompt zależnie od images.isNotEmpty()),
        // więc jedyne, co blokowało, to ten warunek.
        val glassesReady = heyCyan.connectionState.value == ConnectionState.READY
        val useVision = glassesReady ||
            !(trigger == TriggerSource.TEXT_INPUT && textQuestion.isNotBlank())

        if (!glassesReady && useVision) {
            // Przycisk, wake word i głos to z założenia pytania o otoczenie -
            // bez okularów nie ma o czym rozmawiać.
            _state.value = OrchestratorState.Error("Okulary nie są połączone. Ustawienia → Połącz.")
            return
        }
        if (!useVision) {
            Log.i(TAG, "Odpowiadam bez zdjęcia - pytanie tekstowe, okulary niepodłączone")
        }

        // Sprawdź czy jest klucz API
        val providerId = settings.getActiveProvider()
        if (!settings.hasApiKey(providerId)) {
            _state.value = OrchestratorState.Error("Brak klucza API. Ustawienia → Klucz API.")
            return
        }

        scope.launch {
            try {
                // 1. CAPTURE - adaptacyjny tryb
                val provider = getOrCreateProvider()
                val capabilities = provider.capabilities

                val captureResult = if (useVision) {
                    val preferredMode = pl.jarvis.app.ai.CaptureMode.valueOf(
                        settings.getPreferredCaptureMode()
                    )
                    val decision = captureModeSelector.select(
                        preferred = preferredMode,
                        capabilities = capabilities,
                        autoDegrade = settings.isAutoDegradeCaptureEnabled()
                    )
                    Log.i(TAG, "Capture decision: ${decision.mode} (${decision.reason})")

                    val total = decision.mode.expectedImageCount.coerceAtLeast(1)
                    _state.value = OrchestratorState.Capturing(progress = 0, total = total)

                    // Dla trybów seryjnych respektuj liczbę zdjęć i odstęp z ustawień.
                    val isBurstMode = !decision.mode.requiresVideo &&
                        decision.mode.expectedImageCount > 1
                    capture.capture(
                        mode = decision.mode,
                        resolution = decision.resolution,
                        countOverride = if (isBurstMode) settings.getCaptureCount() else null,
                        intervalMsOverride =
                            if (isBurstMode) settings.getCaptureIntervalMs() else null
                    ) { progress ->
                        _state.value = OrchestratorState.Capturing(progress = progress, total = total)
                    }
                } else {
                    null
                }

                val photos = captureResult?.images.orEmpty()
                val video = captureResult?.video
                val videoDurationMs = captureResult?.videoDurationMs ?: 0L

                // Puste zdjęcia są błędem tylko wtedy, gdy mieliśmy je zrobić.
                if (useVision && photos.isEmpty()) {
                    _state.value = OrchestratorState.Error("Nie udało się pobrać żadnego zdjęcia")
                    return@launch
                }

                // 1b. Skan QR (offline, ML Kit)
                val scannedCodes = mutableListOf<ScannedCode>()
                photos.forEach { imageBytes ->
                    qrScanner.scanImageBytesSync(imageBytes).forEach { code ->
                        if (scannedCodes.none { it.rawValue == code.rawValue }) {
                            scannedCodes.add(code)
                        }
                    }
                }
                if (scannedCodes.isNotEmpty()) {
                    Log.i(TAG, "Wykryto ${scannedCodes.size} kod(ów): ${scannedCodes.map { it.format }}")
                }

                // 1c. URL z QR - fetch content jeśli user chce info
                var webContext: WebContent? = null
                if (scannedCodes.isNotEmpty() && shouldFetchUrl(textQuestion, scannedCodes)) {
                    _state.value = OrchestratorState.Thinking
                    webContext = urlAnalyzer.fetchFirstUrl(scannedCodes)
                    if (webContext != null) {
                        Log.i(TAG, "Pobrany URL: ${webContext.url}, ${webContext.totalChars()} znaków")
                    }
                }

                // 1d. OCR - czytaj tekst z otoczenia
                var ocrContext: OCRResult? = null
                if (photos.isNotEmpty() && shouldRunOcr(textQuestion)) {
                    _state.value = OrchestratorState.Thinking
                    // Pierwsze zdjęcie - zazwyczaj ostre, dobre do OCR
                    val firstPhotoBytes = photos.first()
                    ocrContext = ocrReader.readBytes(firstPhotoBytes)
                    if (ocrContext.isSuccess) {
                        Log.i(TAG, "OCR odczytał ${ocrContext.fullText.length} znaków z ${ocrContext.blocks.size} bloków")
                    }
                }

                // 2. AI - streaming
                _state.value = OrchestratorState.Thinking

                // provider już zadeklarowany wyżej dla capabilities

                // Pobierz aktywną personę (system prompt)
                val persona = getActivePersona()
                Log.d(TAG, "Using persona: ${persona.name}")

                // 1d2. Wizytówka vCard z kodu QR
                val contactCard = scannedCodes
                    .asSequence()
                    .map { it.rawValue }
                    .filter { pl.jarvis.app.vision.VCardParser.looksLikeContact(it) }
                    .mapNotNull { pl.jarvis.app.vision.VCardParser.parse(it) }
                    .firstOrNull()
                if (contactCard != null) {
                    Log.i(TAG, "Odczytano wizytówkę: ${contactCard.name}")
                }

                // 1e. Pamięć długoterminowa - poszukaj podobnych rozmów w historii
                val memoryContext = buildMemoryContext(textQuestion)

                // 1f. Tłumaczenie tekstu z OCR (gdy user prosi o tłumaczenie)
                val translatedOcr = translateOcrIfRequested(textQuestion, ocrContext)

                // Buduj prompt z kontekstem: pamięć + URL + OCR + kontekst rozmowy
                val enhancedPrompt = buildString {
                    if (memoryContext != null) {
                        append(memoryContext)
                        append("\n\n")
                    }
                    if (webContext != null) {
                        append(urlAnalyzer.buildPromptContext(webContext))
                        append("\n\n")
                    }
                    if (ocrContext != null && ocrContext.isSuccess) {
                        append(ocrContext.toPromptContext())
                        append("\n\n")
                    }
                    if (translatedOcr != null) {
                        append(translatedOcr)
                        append("\n\n")
                    }
                    if (contactCard != null) {
                        append(contactCard.toPromptContext())
                        append("Jeśli użytkownik chce, zaproponuj zapisanie kontaktu.\n\n")
                    }
                    append(conversationContext.asSystemContext())
                    append(textQuestion)
                }

                val accumulatedText = StringBuilder()
                val language = settings.getResponseLanguage()
                var firstChunk = true

                // Wybierz analyzeStream (zdjęcia) vs analyzeVideo (wideo)
                val streamFlow = if (video != null && video.isNotEmpty() && capabilities.supportsVideo) {
                    Log.i(TAG, "Używam analyzeVideo (${video.size} bytes, ${videoDurationMs}ms)")
                    provider.analyzeVideoStream(
                        textQuestion = enhancedPrompt,
                        videoBytes = video,
                        videoDurationMs = videoDurationMs,
                        audioBytes = null,
                        scannedCodes = scannedCodes,
                        enableWebSearch = settings.isWebSearchEnabled(),
                        systemPrompt = persona.systemPrompt
                    )
                } else {
                    Log.i(TAG, "Używam analyzeStream (${photos.size} zdjęć)")
                    provider.analyzeStream(
                        textQuestion = enhancedPrompt,
                        images = photos,
                        audioBytes = null,
                        scannedCodes = scannedCodes,
                        enableWebSearch = settings.isWebSearchEnabled(),
                        systemPrompt = persona.systemPrompt
                    )
                }

                // CACHE CHECK - może już mamy odpowiedź?
                val cachedAnswer = if (aiCache.shouldCache(textQuestion) && photos.isEmpty() && video == null) {
                    val providerId = settings.getActiveProvider()
                    val modelId = settings.getSelectedModel(providerId) ?: "default"
                    aiCache.get(textQuestion, providerId, modelId)
                } else null

                if (cachedAnswer != null) {
                    Log.i(TAG, "✅ Odpowiedź z cache (zaoszczędzony request!)")
                    accumulatedText.append(cachedAnswer)
                    audio.speak(cachedAnswer, language = settings.getResponseLanguage())
                    _state.value = OrchestratorState.Streaming(cachedAnswer)
                } else {

                // Streaming - każdy fragment natychmiast mówimy
                streamFlow.collect { chunk ->
                    accumulatedText.append(chunk.text)

                    if (chunk.isFinal) {
                        Log.i(TAG, "Stream complete, ${chunk.tokensUsed} tokens, text len=${accumulatedText.length}")
                        // Wymuś wypowiedzenie ostatniego fragmentu
                        audio.flushStream()

                        // Zapisz do cache
                        if (aiCache.shouldCache(textQuestion) && photos.isEmpty() && video == null) {
                            val providerId = settings.getActiveProvider()
                            val modelId = settings.getSelectedModel(providerId) ?: "default"
                            aiCache.put(textQuestion, accumulatedText.toString(), providerId, modelId)
                        }
                    } else if (chunk.text.isNotBlank()) {
                        // Pierwszy fragment - zacznij mówić natychmiast
                        if (firstChunk) {
                            Log.d(TAG, "First chunk received, starting TTS streaming")
                            firstChunk = false
                        }

                        // Wykryj kompletne zdania i mów je od razu (TTS streaming)
                        val spokenSentences = audio.addStreamFragment(chunk.text)
                        if (spokenSentences.isNotEmpty()) {
                            Log.d(TAG, "Spoke ${spokenSentences.size} sentence(s): ${spokenSentences.last().take(50)}...")
                        }

                        // Aktualizuj UI na bieżąco
                        _state.value = OrchestratorState.Streaming(accumulatedText.toString())
                    }
                }  // streamFlow.collect
                }  // else dla cache check

                val response = AIResponse(
                    text = accumulatedText.toString().trim(),
                    providerId = provider.id
                )
                _lastResponse.value = response

                // Zapamiętaj dla follow-up + multi-turn context
                lastQuestion = textQuestion
                lastResponseText = response.text
                conversationContext.addTurn(
                    question = textQuestion,
                    answer = response.text,
                    photos = photos.size,
                    tokens = response.tokensUsed
                )

                // 3. TTS
                // język pobrany wyżej przy budowaniu strumienia odpowiedzi
                conversationalMode.onAiStartedSpeaking()
                audio.speak(response.text, language = language)
                // Po zakończeniu TTS - w trybie konwersacyjnym wznów nasłuchiwanie
                conversationalMode.onAiFinishedSpeaking()

                // 4. Historia - zapisz pierwsze zdjęcie do pliku (miniatura)
                try {
                    val firstPhotoPath = photos.firstOrNull()?.let { bytes ->
                        photoStorage.savePhoto(bytes, prefix = "conv_${System.currentTimeMillis()}")
                    }
                    history.save(
                        question = textQuestion.ifBlank { "(pytanie głosowe - brak transkrypcji w MVP)" },
                        response = response.text,
                        providerId = response.providerId,
                        firstPhotoPath = firstPhotoPath,
                        photoCount = photos.size,
                        tokensUsed = response.tokensUsed,
                        sourcesJson = null
                    )
                    Log.d(TAG, "Saved to history (photo: $firstPhotoPath)")
                    // Egzekwuj limit historii z ustawień
                    history.trimTo(settings.getHistoryLimit())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save history", e)
                }

                _state.value = OrchestratorState.Completed(response.text)

            } catch (e: AIProviderException) {
                Log.e(TAG, "AI error", e)
                _state.value = OrchestratorState.Error(
                    "Błąd AI: ${e.message}" + if (e.isRetryable) " (spróbuj ponownie)" else ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                _state.value = OrchestratorState.Error("Nieoczekiwany błąd: ${e.message}")
            }
        }
    }

    /**
     * Reset do stanu Idle (po wyświetleniu odpowiedzi)
     */
    fun reset() {
        _state.value = OrchestratorState.Idle
    }

    /**
     * Wykonuje wykryte akcje bez udziału AI (szybko, offline).
     * Wspiera SAFE i DIRECT mode.
     */
    private fun handleActions(actions: List<Action>, originalText: String) {
        Log.i(TAG, "Executing ${actions.size} action(s) from: \"$originalText\"")

        val mode = ActionMode.fromName(settings.getActionMode())
        Log.d(TAG, "Action mode: $mode")

        // W trybie DIRECT - sprawdź czy akcja wymaga potwierdzenia
        if (mode == ActionMode.DIRECT) {
            val firstDirect = actions.firstOrNull()
            if (firstDirect != null) {
                val confirmation = directActionExecutor.canExecuteDirect(firstDirect)
                if (confirmation is ActionConfirmation.Required) {
                    // Zapisz akcje do późniejszego wykonania
                    _pendingActionConfirmation.value = PendingActionConfirmation(
                        actions = actions,
                        title = confirmation.title,
                        message = confirmation.message,
                        confirmText = confirmation.confirmText,
                        cancelText = confirmation.cancelText
                    )
                    // Mów o oczekiwaniu
                    audio.speak(
                        confirmation.title + ". " + confirmation.message,
                        language = settings.getResponseLanguage()
                    )
                    return
                }
            }
        }

        // Tryb SAFE lub brak potwierdzenia - wykonaj
        executeActionsList(actions)
    }

    /**
     * Wykonuje listę akcji (używane zarówno po wykryciu jak i po potwierdzeniu).
     */
    private fun executeActionsList(actions: List<Action>) {
        // Obsługa accessibility (nie wymaga trybu DIRECT/SAFE)
        val accessibilityActions = actions.filter {
            it is Action.ReadText || it is Action.DescribeScene ||
            it is Action.StartNavigation || it is Action.StopAccessibility
        }

        if (accessibilityActions.isNotEmpty()) {
            scope.launch {
                accessibilityActions.forEach { action ->
                    when (action) {
                        is Action.ReadText -> accessibility.enableReadText()
                        is Action.DescribeScene -> {
                            // Jednorazowy opis
                            val desc = accessibility.describeOnce()
                            if (desc != null) {
                                audio.speak(desc, language = "pl")
                            }
                        }
                        is Action.StartNavigation -> accessibility.enableNavigate()
                        is Action.StopAccessibility -> accessibility.disable()
                        // Lista jest wcześniej przefiltrowana do akcji dostępności,
                        // ale Kotlin wymaga wyczerpania when po typie Action.
                        else -> Log.w(TAG, "Nieoczekiwana akcja w trybie dostępności: ${action.type}")
                    }
                }
            }
            // Jeśli wszystko to accessibility, zwracamy bezpośrednio
            if (actions.size == accessibilityActions.size) {
                _state.value = OrchestratorState.Idle
                return
            }
        }

        val mode = ActionMode.fromName(settings.getActionMode())

        scope.launch {
            val results = actions.map { action ->
                // Spróbuj DIRECT jeśli tryb DIRECT i akcja to obsługuje
                val result = if (mode == ActionMode.DIRECT &&
                    (action is Action.SendSms || action is Action.MakeCall)) {
                    val direct = directActionExecutor.executeDirect(action)
                    // Fallback do SAFE jeśli direct się nie udało
                    if (direct is ActionResult.Failed) {
                        Log.w(TAG, "DIRECT failed, falling back to SAFE: ${direct.reason}")
                        actionExecutor.execute(action)
                    } else direct
                } else {
                    actionExecutor.execute(action)
                }
                Log.d(TAG, "Action ${action.type}: $result")
                action to result
            }

            // Zbuduj odpowiedź głosową
            val speech = when {
                results.all { it.second is ActionResult.Success } -> {
                    "OK, ${results.joinToString { it.first.description.lowercase() }}"
                }
                results.any { it.second is ActionResult.Failed } -> {
                    val failed = results.filter { it.second is ActionResult.Failed }
                    "Nie udało się: ${failed.joinToString { (it.second as ActionResult.Failed).reason }}"
                }
                else -> "Wykonano"
            }

            // Mów i pokaż
            audio.speak(speech, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(speech)
        }
    }

    /**
     * Potwierdzenie akcji przez usera (z dialogu).
     */
    fun confirmAction() {
        val pending = _pendingActionConfirmation.value
        if (pending != null) {
            Log.i(TAG, "User confirmed action: ${pending.actions}")
            _pendingActionConfirmation.value = null
            executeActionsList(pending.actions)
        }
    }

    /**
     * Anulowanie akcji przez usera.
     */
    fun cancelAction() {
        val pending = _pendingActionConfirmation.value
        if (pending != null) {
            Log.i(TAG, "User cancelled action: ${pending.actions}")
            _pendingActionConfirmation.value = null
            audio.speak("Anulowano", language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Idle
        }
    }

    /**
     * Sprawdza czy user chce informacji o URL z QR.
     * Słowa kluczowe: "co to", "co tam jest", "co na stronie", "co jest na stronie",
     * "co na tej", "co to za", "powiedz mi o", "czytaj", "streść"
     */
    private fun shouldFetchUrl(text: String, codes: List<ScannedCode>): Boolean {
        if (codes.isEmpty()) return false
        if (urlAnalyzer.extractUrls(codes).isEmpty()) return false

        val triggers = listOf(
            "co to", "co tam", "co na", "co jest", "czytaj", "streść",
            "streszcz", "powiedz o", "informacje", "info", "opowiedz"
        )
        val lower = text.lowercase()
        return triggers.any { lower.contains(it) } || text.isBlank()
    }

    /**
     * Sprawdza czy user chce OCR - czytanie tekstu z otoczenia.
     * Słowa kluczowe: "przeczytaj", "co pisze", "co jest napisane",
     * "przetłumacz", "menu", "etykieta", "tablica"
     */
    private fun shouldRunOcr(text: String): Boolean {
        val triggers = listOf(
            "przeczytaj", "co pisze", "co napisane", "co tu pisze",
            "przetłumacz", "tłumacz", "menu", "etykieta", "tablica",
            "napis", "tekst", "czytaj", "wytłumacz"
        )
        val lower = text.lowercase()
        return triggers.any { lower.contains(it) } || text.isBlank()
    }

    /**
     * Wyczyść kontekst rozmowy (nowa sesja).
     */
    fun clearConversation() {
        conversationContext.clear()
    }

    /**
     * Ile jest wymian w kontekście.
     */
    fun getConversationSize(): Int = conversationContext.size()

    // === Private ===

    private suspend fun getOrCreateProvider(): AIProvider {
        val providerId = settings.getActiveProvider()
        val apiKey = settings.getApiKey(providerId)
            ?: throw AIProviderException(
                "Brak klucza API dla $providerId. Ustaw go w ustawieniach.",
                providerId = providerId,
                isRetryable = false
            )

        val preferredModel = settings.getSelectedModel(providerId)

        // Sprawdź czy trzeba odświeżyć (provider lub model się zmienił)
        if (currentProviderId != providerId || activeModelId != preferredModel) {

            // Walidacja modeli u providera (async, nie blokuje UI)
            val available = try {
                val validator = RemoteModelValidator(apiKey, providerId)
                validator.fetchAvailableModels()
            } catch (e: Exception) {
                Log.w(TAG, "Could not validate models", e)
                emptyList()
            }

            // Stwórz provider z resolverem
            val withMetadata = AIProviderFactory.create(
                providerId = providerId,
                apiKey = apiKey,
                preferredModelId = preferredModel,
                availableFromProvider = available
            )

            currentProvider = withMetadata.provider
            currentProviderId = providerId
            activeModelId = withMetadata.modelId
            _currentModelId.value = withMetadata.modelId  // publikuj dla UI

            // Pokaż ostrzeżenie jeśli model jest deprecated lub zmigrowany
            withMetadata.resolution.warning?.let { warning ->
                _modelWarning.value = warning.toUserMessage()
                Log.w(TAG, "Model warning: ${warning.toUserMessage()}")
            }

            // Jeśli automatyczna migracja - zaktualizuj ustawienia
            if (withMetadata.resolution.source ==
                pl.jarvis.app.data.ModelSource.AUTO_MIGRATED) {
                withMetadata.resolution.warning?.let { warning ->
                    if (warning is pl.jarvis.app.data.ModelWarning.AutoMigrated) {
                        settings.setSelectedModel(providerId, warning.newModelId)
                    }
                }
            }
        }
        return currentProvider!!
    }

    /**
     * Czyści aktywne ostrzeżenie (po wyświetleniu użytkownikowi).
     */
    fun clearModelWarning() {
        _modelWarning.value = null
    }

    /**
     * Pobiera aktywną personę na podstawie ustawień.
     * Jeśli wybrano "custom" - używa wpisanego promptu.
     */
    fun getActivePersona(): Persona {
        val personaId = settings.getSelectedPersonaId()
        return when (personaId) {
            "custom" -> {
                val customPrompt = settings.getCustomPersonaPrompt()
                if (customPrompt.isBlank()) {
                    PersonaRegistry.default()
                } else {
                    PersonaRegistry.customFromPrompt(customPrompt)
                }
            }
            else -> PersonaRegistry.findById(personaId) ?: PersonaRegistry.default()
        }
    }

    /**
     * Szuka w historii rozmów podobnych do bieżącego pytania i buduje z nich kontekst.
     * Respektuje przełącznik "pamięć długoterminowa" w ustawieniach.
     *
     * @return fragment promptu albo `null` gdy wyłączone lub brak trafień
     */
    private suspend fun buildMemoryContext(question: String): String? {
        if (!settings.isLongTermMemoryEnabled()) return null
        return try {
            val entries = history.getRecent(MEMORY_SEARCH_POOL)
            if (entries.isEmpty()) return null

            val matches = longTermMemory
                .findSimilar(question, entries, limit = MEMORY_MAX_MATCHES)
                .filter { it.score >= MEMORY_MIN_SCORE }
            if (matches.isEmpty()) return null

            Log.i(TAG, "Pamięć: ${matches.size} podobnych rozmów (najlepsza ${matches.first().score})")
            buildString {
                append("Wcześniejsze rozmowy z tym użytkownikiem na podobny temat:\n")
                matches.forEach { match ->
                    append("- Pytanie: ").append(match.entry.userQuestion.take(200)).append('\n')
                    append("  Odpowiedź: ").append(match.entry.aiResponse.take(300)).append('\n')
                }
                append("Wykorzystaj to jeśli pomaga, ale nie powtarzaj bez potrzeby.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pamięć długoterminowa niedostępna", e)
            null
        }
    }

    /**
     * Tłumaczy tekst odczytany przez OCR na język docelowy z ustawień,
     * ale tylko gdy użytkownik faktycznie prosi o tłumaczenie.
     */
    private suspend fun translateOcrIfRequested(question: String, ocr: OCRResult?): String? {
        if (ocr == null || !ocr.isSuccess || ocr.fullText.isBlank()) return null
        if (!wantsTranslation(question)) return null

        val target = settings.getTranslationTarget()
        return try {
            val source = settings.getResponseLanguage().take(2).lowercase()
            val translated = translator.translate(ocr.fullText.take(1000), source, target)
            if (translated.isBlank() || translated == ocr.fullText) return null
            val targetName = pl.jarvis.app.translation.SimultaneousTranslator.languageName(target)
            Log.i(TAG, "Przetłumaczono OCR na $target")
            "Tłumaczenie odczytanego tekstu ($targetName):\n$translated"
        } catch (e: Exception) {
            Log.w(TAG, "Tłumaczenie OCR nie powiodło się", e)
            null
        }
    }

    /** Czy pytanie użytkownika dotyczy tłumaczenia. */
    private fun wantsTranslation(question: String): Boolean {
        val q = question.lowercase()
        return TRANSLATION_KEYWORDS.any { q.contains(it) }
    }

    companion object {
        /**
         * Wspólny prompt systemowy dla trybów dostępności.
         * Model widzi pojedyncze zdjęcie z okularów - nie ma czujnika odległości
         * ani podglądu na żywo, więc nie wolno mu udawać systemu bezpieczeństwa.
         */
        private const val ACCESSIBILITY_SYSTEM_PROMPT =
            "Jesteś asystentem osoby niewidomej. Widzisz pojedyncze zdjęcie z kamery " +
                "w okularach, zrobione kilka sekund temu. Nie masz czujnika odległości " +
                "i nie widzisz ruchu. Nigdy nie podawaj odległości w metrach ani " +
                "centymetrach i nigdy nie zapewniaj, że droga jest wolna lub bezpieczna. " +
                "Gdy czegoś nie widzisz wyraźnie - powiedz to. Mów krótko, rzeczowo, po polsku."

        /** Ile ostatnich rozmów przeszukiwać w pamięci długoterminowej. */
        private const val MEMORY_SEARCH_POOL = 50
        private const val MEMORY_MAX_MATCHES = 3
        private const val MEMORY_MIN_SCORE = 0.15f

        private val TRANSLATION_KEYWORDS = listOf(
            "przetłumacz", "tłumacz", "translate", "po angielsku", "po niemiecku",
            "po polsku", "co to znaczy", "what does it mean"
        )

        private const val TAG = "AIOrchestrator"
    }
}

/**
 * Stan orkiestratora - obserwowany przez UI
 */
sealed class OrchestratorState {
    object Idle : OrchestratorState()
    data class Capturing(val progress: Int, val total: Int) : OrchestratorState()
    object Thinking : OrchestratorState()
    data class Streaming(val text: String) : OrchestratorState()  // nowy - streaming partial
    data class Completed(val text: String) : OrchestratorState()
    data class Error(val message: String) : OrchestratorState()
}

enum class TriggerSource { BUTTON, TEXT_INPUT, WAKE_WORD, VOICE }

/**
 * Akcja oczekująca na potwierdzenie użytkownika.
 */
data class PendingActionConfirmation(
    val actions: List<Action>,
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String
)
