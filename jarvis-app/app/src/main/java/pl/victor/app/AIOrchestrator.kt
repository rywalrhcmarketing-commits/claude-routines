package pl.victor.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.ai.AIProvider
import pl.victor.app.ai.AIProviderException
import pl.victor.app.ai.AIProviderFactory
import pl.victor.app.ai.AIResponse
import pl.victor.app.actions.Action
import pl.victor.app.actions.ActionConfirmation
import pl.victor.app.actions.ActionExecutor
import pl.victor.app.actions.ActionMode
import pl.victor.app.actions.ActionResult
import pl.victor.app.actions.ContactResolver
import pl.victor.app.actions.DirectActionExecutor
import pl.victor.app.actions.SmartActionDetector
import pl.victor.app.audio.AudioManager
import pl.victor.app.audio.GlassesVoiceCapture
import pl.victor.app.ble.ButtonAction
import pl.victor.app.ble.ButtonActionDetector
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.GlassesProtocol
import pl.victor.app.ble.VictorManager
import pl.victor.app.camera.BurstCaptureManager
import pl.victor.app.conversation.ConversationContext
import pl.victor.app.conversation.ConversationalMode
import pl.victor.app.data.HistoryRepository
import pl.victor.app.data.RemoteModelValidator
import pl.victor.app.data.SettingsRepository
import pl.victor.app.persona.Persona
import pl.victor.app.persona.PersonaRegistry
import pl.victor.app.storage.PhotoStorage
import pl.victor.app.vision.OCRReader
import pl.victor.app.vision.OCRResult
import pl.victor.app.vision.QRScanner
import pl.victor.app.vision.ScannedCode
import pl.victor.app.web.URLAnalyzer
import pl.victor.app.web.WebContent

/**
 * Orkiestrator - koordynuje cały flow:
 * 1. Trigger (przycisk, text input, wake word)
 * 2. Capture (5 zdjęć co 1s przez VictorManager)
 * 3. AI analysis (multimodal)
 * 4. TTS playback
 * 5. Zapis do historii
 */
class AIOrchestrator(
    private val context: Context,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val wakeWord: pl.victor.app.wakeword.WakeWordDetector? = null
) {
    /**
     * Ostatnia siatka bezpieczeństwa dla korutyn orkiestratora.
     *
     * Bez niej wyjątek, którego nie złapała żadna gałąź, leci do systemowego
     * handlera - czyli **wywraca aplikację**. Dla asystenta noszonego na głowie
     * to najgorszy możliwy wynik: telefon jest w kieszeni, użytkownik nie widzi
     * ekranu i nie ma jak się dowiedzieć, że coś się stało. Lepiej pokazać błąd
     * i wrócić do gotowości.
     *
     * Anulowanie przepuszczamy bez śladu - to normalne przerwanie tury, nie awaria.
     */
    private val coroutineErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        if (error is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
        Log.e(TAG, "Nieobsłużony błąd w korutynie orkiestratora", error)
        _state.value = OrchestratorState.Error(
            "Coś poszło nie tak: " + (error.message ?: error::class.simpleName ?: "nieznany błąd")
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + coroutineErrors)

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
    private val glassesManager: VictorManager = VictorManager.getInstance(context)
    private val photoStorage: PhotoStorage = PhotoStorage(context)
    private val capture: BurstCaptureManager = BurstCaptureManager(
        context = context,
        photoStorage = photoStorage,
        glassesManager = glassesManager
    )

    // Power management - kontroluje co może działać
    val powerManager = pl.victor.app.power.PowerManager(context, settings)
    private val aiCache = pl.victor.app.ai.AIResponseCache()
    private val wakeLock = pl.victor.app.power.WakelockHelper(context)
    private val audio: AudioManager = AudioManager.getInstance(context)
    private val qrScanner: QRScanner = QRScanner()
    private val ocrReader: OCRReader = OCRReader()
    private val actionDetector = SmartActionDetector()
    private val actionExecutor = ActionExecutor(context)
    private val directActionExecutor = DirectActionExecutor(context)
    private val contactResolver = ContactResolver(context)
    private val urlAnalyzer = URLAnalyzer()
    private val translator = pl.victor.app.translation.SimultaneousTranslator()
    private val longTermMemory = pl.victor.app.memory.LongTermMemory(context, history)
    private val conversationContext = ConversationContext()
    private val captureModeSelector = pl.victor.app.camera.CaptureModeSelector()

    // Tryb konwersacyjny (continuous listening)
    /** Rozpoznawanie mowy - wspólne dla trybu konwersacyjnego i wybudzenia z okularów. */
    private val speechToText = pl.victor.app.conversation.SpeechToText(context)

    private val conversationalMode = ConversationalMode(
        audio = audio,
        wakeWord = wakeWord,
        speechToText = speechToText,
        onUserSpoke = { text -> handleUserTrigger(TriggerSource.VOICE, text) },
        onActivated = { Log.i(TAG, "Tryb konwersacyjny ON") },
        onDeactivated = { Log.i(TAG, "Tryb konwersacyjny OFF") }
    ).apply {
        // Rozpoznawanie ma słuchać w tym języku, w którym użytkownik mówi.
        recognitionLanguageTag = languageTagFor(settings.getResponseLanguage())
    }

    // Accessibility - dla niewidomych/słabowidzących
    val accessibility = pl.victor.app.accessibility.AccessibilityService(
        audio = audio,
        ocrReader = ocrReader,
        glassesManager = glassesManager,
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
    /**
     * Rozwiązuje nazwę kontaktu na numer telefonu dla SendSms/MakeCall.
     * Inne typy akcji przechodzą bez zmian.
     *
     * @return akcja z numerem zamiast nazwy, ta sama akcja gdy `to` już jest
     *         numerem, albo `null` gdy kontaktu nie udało się znaleźć
     */
    private suspend fun resolveContactIfNeeded(action: Action): Action? = when (action) {
        is Action.SendSms -> {
            if (contactResolver.isPhoneNumber(action.to)) {
                action
            } else {
                // "smsto:" nie ma żadnego mechanizmu wyszukiwania po nazwie -
                // bez rozwiązania SMS nigdy by nie doszedł do adresata, więc
                // lepiej zgłosić to wprost niż cicho otworzyć aplikację SMS
                // z odbiorcą, którego nikt nie rozpozna.
                contactResolver.findPhoneNumber(action.to)?.let { action.copy(to = it) }
            }
        }
        is Action.MakeCall -> {
            if (contactResolver.isPhoneNumber(action.to)) {
                action
            } else {
                // ACTION_DIAL z nazwą czasem trafia w wyszukiwanie T9 dialera
                // (wpisanie liter na klawiaturze telefonu też sugeruje kontakty).
                // Gdy nie mamy dostępu do książki albo kontaktu nie ma, zostawiamy
                // oryginalną nazwę zamiast twardo failować - to jedyna ścieżka,
                // która wcześniej działała bez READ_CONTACTS.
                contactResolver.findPhoneNumber(action.to)?.let { action.copy(to = it) } ?: action
            }
        }
        else -> action
    }

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

    /**
     * Wydarzenia z kalendarza urządzenia jako kontekst dla modelu.
     *
     * Czyta kalendarz systemowy (dowolny zsynchronizowany, w tym Google), więc
     * nie wymaga logowania OAuth. Wcześniej kalendarz był odczytywany wyłącznie
     * przez alerty pogodowe - V.I.C.T.O.R. nie potrafił odpowiedzieć na „co mam dziś
     * w planach", mimo że dane były na wyciągnięcie ręki.
     *
     * @return fragment promptu albo `null`, gdy pytanie nie dotyczy planów,
     *         brakuje uprawnienia albo nie ma nadchodzących wydarzeń
     */
    /**
     * Bieżąca data i godzina jako kontekst dla modelu.
     *
     * Model nie ma zegara, a jego wiedza kończy się na dacie treningu - bez tego
     * "jaki dziś dzień", "ile zostało do piątku" albo "umów spotkanie na jutro"
     * są zgadywaniem, podanym tym samym pewnym tonem co prawdziwa odpowiedź.
     *
     * Format ISO obok zapisu słownego, bo ten sam blok obsługuje dwie różne
     * potrzeby: człowiek pyta "jaki dziś dzień", a znacznik
     * `[[ACTION: type=create_calendar_event start=...]]` potrzebuje daty, którą
     * da się sparsować bez zgadywania (patrz [SmartActionDetector.parseStartTime]).
     *
     * Doklejany ZAWSZE - w odróżnieniu od pogody czy kalendarza nie da się
     * wykryć słowami kluczowymi, kiedy jest potrzebny ("ile mam czasu?",
     * "zdążę?", "jutro" w środku zdania), a kosztuje dwie linijki promptu.
     */
    private fun buildTimeContext(): String {
        val now = java.time.ZonedDateTime.now()
        val polish = java.util.Locale("pl", "PL")
        val spoken = now.format(
            java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", polish)
        )
        return buildString {
            append("=== TERAZ ===\n")
            append(spoken).append('\n')
            append("ISO: ").append(
                now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            ).append(" (strefa ").append(now.zone.id).append(")\n")
        }
    }

    private suspend fun buildCalendarContext(question: String): String? {
        if (!pl.victor.app.proactive.CalendarContext.isAboutSchedule(question)) return null

        val calendar = pl.victor.app.proactive.CalendarService(context)
        if (!calendar.hasPermission()) {
            Log.d(TAG, "Pytanie o plany, ale brak uprawnienia READ_CALENDAR")
            return null
        }
        return try {
            val events = calendar.getUpcomingEvents(limit = 8, hoursAhead = 48)
            pl.victor.app.proactive.CalendarContext.buildPromptContext(events)
                ?.also { Log.i(TAG, "Doklejam ${events.size} wydarzeń z kalendarza") }
        } catch (e: Exception) {
            // Brak kalendarza nie może wywrócić odpowiedzi na pytanie.
            Log.w(TAG, "Odczyt kalendarza nie powiódł się", e)
            null
        }
    }

    /**
     * Prognoza pogody jako kontekst dla modelu.
     *
     * Do tej pory pogoda żyła wyłącznie w alertach w tle: aplikacja sprawdzała
     * ją co jakiś czas i wysyłała powiadomienie, gdy coś było nie tak. Zapytana
     * wprost - "jaka jest pogoda?" - odpowiadała z pamięci modelu, czyli
     * ZMYŚLAŁA. Teraz pytania o pogodę dostają prawdziwe dane.
     *
     * Wymaga klucza OpenWeatherMap i lokalizacji z ustawień; bez nich po prostu
     * nic nie dokleja, zamiast wywracać odpowiedź.
     */
    private suspend fun buildWeatherContext(question: String): String? {
        if (!pl.victor.app.proactive.WeatherContext.isAboutWeather(question)) return null

        val apiKey = settings.getOpenWeatherApiKey()
        if (apiKey.isBlank()) {
            Log.d(TAG, "Pytanie o pogodę, ale brak klucza OpenWeatherMap")
            return null
        }
        val place = settings.getWeatherLocation()
        if (place.isBlank()) {
            Log.d(TAG, "Pytanie o pogodę, ale brak ustawionej lokalizacji")
            return null
        }
        return try {
            val service = pl.victor.app.proactive.WeatherService(apiKey)
            val geo = service.geocode(place) ?: return null
            val forecast = service.getForecast(geo.lat, geo.lon)
            val air = runCatching { service.getAirQuality(geo.lat, geo.lon) }.getOrNull()
            pl.victor.app.proactive.WeatherContext.buildPromptContext(forecast, air)
                ?.also { Log.i(TAG, "Doklejam prognozę pogody dla $place") }
        } catch (e: Exception) {
            // Padnięte API pogodowe nie może wywrócić odpowiedzi na pytanie.
            Log.w(TAG, "Pobranie pogody nie powiodło się", e)
            null
        }
    }

    /**
     * Ostatnie maile jako kontekst dla modelu.
     *
     * Tak jak kalendarz - doklejane tylko gdy pytanie faktycznie dotyczy
     * poczty. Wymaga połączonego konta Google (patrz [pl.victor.app.google.GoogleAccountManager]);
     * bez tego po prostu nic nie dokleja, zamiast pokazywać błąd.
     *
     * @return fragment promptu albo `null`, gdy pytanie nie dotyczy maili,
     *         konto nie jest połączone albo nie ma żadnych wiadomości
     */
    private suspend fun buildGmailContext(question: String): String? {
        if (!pl.victor.app.proactive.GmailContext.isAboutEmail(question)) return null

        val gmail = pl.victor.app.google.GmailService(context)
        if (!gmail.isSignedIn()) {
            Log.d(TAG, "Pytanie o maile, ale brak połączonego konta Google")
            return null
        }
        return try {
            val messages = gmail.getRecentMessages(maxResults = 8)
            pl.victor.app.proactive.GmailContext.buildPromptContext(messages)
                ?.also { Log.i(TAG, "Doklejam ${messages.size} maili") }
        } catch (e: Exception) {
            // Brak dostępu do Gmaila nie może wywrócić odpowiedzi na pytanie.
            Log.w(TAG, "Odczyt Gmaila nie powiódł się", e)
            null
        }
    }

    fun enableConversationalMode() {
        // Język mógł się zmienić w ustawieniach od czasu utworzenia orkiestratora.
        conversationalMode.recognitionLanguageTag =
            languageTagFor(settings.getResponseLanguage())
        conversationalMode.enable()
        // Ten sam sygnał, co przy pojedynczej turze - użytkownik ma jeden znak
        // "mów teraz", niezależnie od tego, którym trybem trafił do nasłuchu.
        audio.playListeningCue()
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

    /** Korutyna bieżącej tury - do przerwania przez [cancelCurrentTurn]. */
    private var activeTurnJob: kotlinx.coroutines.Job? = null

    private var currentProvider: AIProvider? = null
    private var currentProviderId: String? = null
    private var activeModelId: String? = null

    init {
        // Preferencja mikrofonu okularów musi trafić do routera PRZED pierwszą
        // turą - inaczej wyłączenie działałoby dopiero po restarcie aplikacji.
        audio.setGlassesMicEnabled(settings.isGlassesMicEnabled())

        // Nasłuch trybu konwersacyjnego wraca ZAWSZE, gdy tura się kończy.
        // Wcześniej zależało to od tego, czy dana ścieżka wyjścia pamiętała o
        // onAiFinishedSpeaking() - a wyjść jest kilkanaście: brak klucza API,
        // odłączone okulary, nieudane zdjęcie, akcja z warstwy 0. Każde
        // zapomnienie zostawiało tryb konwersacyjny głuchym na stałe, bo nasłuch
        // wstrzymywaliśmy PRZED turą, a wznawiali dopiero po odpowiedzi.
        // Sterowanie stanem końcowym zamyka tę dziurę raz, dla wszystkich ścieżek.
        //
        // Celowo bez stanu Idle: startVoiceTurn ustawia go W TRAKCIE tury, zaraz
        // po nasłuchu, więc wznowienie w tym miejscu wysyłałoby mikrofon po
        // kolejne pytanie, gdy model dopiero zaczyna odpowiadać.
        scope.launch {
            _state.collect { current ->
                if (current is OrchestratorState.Completed ||
                    current is OrchestratorState.Error
                ) {
                    conversationalMode.onAiFinishedSpeaking()
                }
            }
        }

        // Nasłuchuj akcji przycisku fizycznego
        scope.launch {
            glassesManager.buttonEvent.collect { event ->
                event?.let {
                    buttonDetector.processEvent(it)
                    glassesManager.consumeButtonEvent()
                }
            }
        }

        // Nasłuchuj zdetektowane akcje
        scope.launch {
            buttonDetector.action.collect { action ->
                handleButtonAction(action)
            }
        }

        // === Wybudzenie po stronie okularów ===
        // Okulary same wykrywają swoje słowo kluczowe (włączane przez
        // VictorManager.setGlassesWakeWord). Wcześniej detekcja była włączona,
        // ale zdarzenie nie miało odbiorcy - czyli "wake word nie działał".
        scope.launch {
            glassesManager.aiSessionRequest.collect { realtimeText ->
                startGlassesConversation(realtimeText)
            }
        }

        // Dotknięcie zauszników w trakcie mówienia = "cicho".
        scope.launch {
            glassesManager.speechInterrupted.collect {
                Log.i(TAG, "Okulary: użytkownik przerwał wypowiedź")
                cancelCurrentTurn()
            }
        }
    }

    /** Czy mamy zgodę na mikrofon - patrz [startVoiceTurn]. */
    private fun hasMicrophonePermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Czy można zacząć nową turę - a jeśli poprzednia już się skończyła, sprząta
     * po niej stan.
     *
     * TO BYŁA PRZYCZYNA "aplikacja nie reaguje na komendy". Stany `Completed` i
     * `Error` NIE oznaczają, że coś trwa - to ślad po turze, która się
     * zakończyła. Zostawały jednak na ekranie do czasu, aż użytkownik kliknął
     * "OK" albo "Spróbuj ponownie", a warunek `stan != Idle` odrzucał w tym
     * czasie KAŻDY trigger: przycisk na okularach, wybudzenie, komendę. Z
     * perspektywy użytkownika okulary przestawały działać po pierwszym błędzie,
     * a jedynym ratunkiem było sięgnięcie po telefon.
     *
     * Zajęte są wyłącznie stany, w których coś faktycznie leci.
     */
    /**
     * Ile tur z rzędu poszło przez profil rozmowy zestawu Bluetooth i skończyło
     * się kompletną ciszą - patrz [noteSilentTurn].
     */
    private var silentScoTurns = 0

    private fun claimIdle(): Boolean {
        return when (_state.value) {
            is OrchestratorState.Idle -> true
            is OrchestratorState.Completed, is OrchestratorState.Error -> {
                // Poprzednia tura się skończyła - sprzątamy i wchodzimy.
                _state.value = OrchestratorState.Idle
                true
            }
            else -> false
        }
    }

    /**
     * Przerywa bieżącą turę na żądanie użytkownika - dotykiem zauszników,
     * przyciskiem "Przerwij" w aplikacji albo komendą.
     *
     * Samo uciszenie syntezatora nie wystarcza: gdy przerwanie przyjdzie, zanim
     * model skończy generować, odpowiedź dojdzie chwilę później i i tak zostanie
     * wypowiedziana - czyli "cicho" wyglądałoby na zignorowane. Dlatego kasujemy
     * całą korutynę tury.
     */
    fun cancelCurrentTurn() {
        audio.stopSpeaking()
        activeTurnJob?.cancel()
        activeTurnJob = null
        _state.value = OrchestratorState.Idle
        conversationalMode.onAiFinishedSpeaking()
    }

    /**
     * Rozmowa zainicjowana przez same okulary - słowem kluczowym albo
     * przytrzymaniem zausznika. Właściwa tura jest w [startVoiceTurn].
     */
    private fun startGlassesConversation(realtimeText: Boolean) {
        if (realtimeText) {
            // Tryb tekstu na żywo (tłumaczenie) nie ma jeszcze osobnej ścieżki -
            // traktujemy go jak zwykłe pytanie, żeby wybudzenie w ogóle coś
            // robiło, zamiast milczeć.
            Log.i(TAG, "Tryb tekstu na żywo - obsługuję jak zwykłe pytanie")
        }
        startVoiceTurn(fromGlasses = true)
    }

    /**
     * Rozmowa zainicjowana z aplikacji - przycisk "zapytaj głosem" na ekranie
     * głównym.
     *
     * Bez tego jedyną drogą do rozmowy było wybudzenie okularami albo pisanie
     * z klawiatury. Asystent głosowy, do którego trzeba pisać, mija się z celem -
     * a okularów nie zawsze ma się na sobie.
     */
    fun startVoiceQuestion() = startVoiceTurn(fromGlasses = false)

    /**
     * „Pokaż" z ekranu głównego: zrób zdjęcie i powiedz, co na nim jest.
     *
     * Wcześniej szło to jako trigger BUTTON z PUSTYM pytaniem - model dostawał
     * sam obraz i musiał się domyślić, po co. Zwykle się domyślał, ale wynik
     * zależał od modelu i od tego, co akurat było w kontekście. Jawne polecenie
     * jest przewidywalne, a `forceVision` pomija warstwę 0, więc słowo „opisz"
     * w treści polecenia nie odpali przypadkiem trybu dostępności.
     */
    fun askAboutView() =
        handleUserTrigger(TriggerSource.BUTTON, PHOTO_ON_DEMAND_QUESTION, forceVision = true)

    /**
     * Jedna tura rozmowy mówionej: słuchaj -> zrozum -> odpowiedz głosem.
     *
     * Kolejność jest istotna i wzorowana na aplikacji producenta: najpierw
     * bierzemy łącze audio (bez niego mikrofon okularów jest niedostępny, a
     * odpowiedź poszłaby w głośnik telefonu), potem uciszamy to, co okulary
     * akurat odtwarzają, i dopiero wtedy nagrywamy.
     *
     * @param fromGlasses czy turę zaczęły okulary (wtedy dodatkowo sterujemy ich
     *   odtwarzaniem i sygnalizujemy im niepowodzenie)
     */
    private fun startVoiceTurn(fromGlasses: Boolean) {
        if (!claimIdle()) {
            Log.w(TAG, "Nasłuch zignorowany - trwa inna operacja")
            return
        }
        if (!speechToText.isAvailable()) {
            _state.value = OrchestratorState.Error(
                "To urządzenie nie ma rozpoznawania mowy. Wpisz pytanie z klawiatury."
            )
            return
        }
        // Bez uprawnienia do mikrofonu rozpoznawanie zwraca po prostu ciszę, a
        // wybudzenie okularami wyglądało wtedy dokładnie tak, jak zgłoszono:
        // dźwięk w okularach jest, po czym NIC. Ścieżka z okularów nie ma jak
        // pokazać systemowego okienka o zgodę, więc trzeba powiedzieć wprost,
        // czego brakuje - i to na głos, bo użytkownik patrzy przed siebie, a nie
        // w telefon.
        if (!hasMicrophonePermission()) {
            val message = "Brak zgody na mikrofon. Otwórz aplikację i naciśnij " +
                "przycisk Powiedz - system zapyta o uprawnienie."
            Log.w(TAG, "Nasłuch niemożliwy - brak RECORD_AUDIO")
            _state.value = OrchestratorState.Error(message)
            audio.speak(message, language = settings.getResponseLanguage())
            return
        }
        scope.launch {
            // Strumień z mikrofonu okularów podpinamy JAKO PIERWSZY, przed
            // zestawianiem łącza audio. Producent (Prism Pro) subskrybuje go
            // dokładnie w chwili wybudzenia, a negocjacja SCO potrafi trwać
            // kilka sekund - gdyby szła przodem, początek pytania przepadłby,
            // zanim zdążylibyśmy zacząć słuchać.
            val glassesCapture =
                if (fromGlasses && glassesManager.isConnected()) {
                    GlassesVoiceCapture(glassesManager).also { it.start() }
                } else {
                    null
                }
            if (fromGlasses) {
                // Uciszenie okularów to komenda BLE - idzie natychmiast, więc
                // musi pójść PRZED zestawianiem łącza audio. Za nim czekałoby
                // na negocjację SCO i okulary grałyby dalej przez ten czas.
                // Producent nie gra tu żadnego dźwięku powitalnego, tylko
                // ucisza to, co leci - i my robimy tak samo.
                glassesManager.playGlassesTone(GlassesProtocol.TONE_STOP_PLAYBACK)
            }
            var held = audio.beginConversationRouting()
            val overSco = held && audio.isRoutedToBluetooth()
            try {
                conversationalMode.onAiStartedSpeaking()
                _state.value = OrchestratorState.Listening
                // Sygnał "teraz mów" DOKŁADNIE w chwili startu nasłuchu, nie
                // wcześniej. Dźwięk wybudzenia gra firmware okularów, a między
                // nim a tym momentem mija okno rozpoznawania kliknięć plus
                // zestawienie łącza SCO - na starszym Androidzie nawet kilka
                // sekund. Bez tego znaku pierwsze słowa idą w nic, co wygląda
                // dokładnie jak "wybudzam, mówię, a on nie reaguje".
                audio.playListeningCue()
                val language = settings.getResponseLanguage()
                // Przez conversationalMode, NIE bezpośrednio przez speechToText:
                // mikrofon jest wyłączny, a wykrywanie słowa kluczowego trzyma
                // AudioRecord. Bez zwolnienia go rozpoznawanie dostaje
                // ERROR_RECOGNIZER_BUSY - czyli "mikrofon nie działa".
                val heard = listenUntilSpeechEnds(languageTagFor(language), glassesCapture)
                _state.value = OrchestratorState.Idle

                if (heard.isNullOrBlank()) {
                    // Zanim ogłosimy porażkę: może okulary jednak przysłały
                    // dźwięk po BLE. Jeśli tak i model umie słuchać, pytanie
                    // idzie do niego jako nagranie - bez rozpoznawania mowy.
                    val captured = glassesCapture?.stop()
                    val recording = captured?.takeIf { it.hasAudio }?.wav
                    val seconds = captured?.audioSeconds ?: 0.0

                    // NAJPIERW próbujemy przepisać nagranie na tekst u siebie.
                    // Nagranie w załączniku jest ostatecznością, nie planem:
                    // dopiero tekst uruchamia całą resztę aplikacji - wykrywanie
                    // komend, pamięć rozmowy i rozpoznanie pytania "co widzę",
                    // po którym lecimy po zdjęcie. Zgłoszono to dwoma zdaniami:
                    // "dostaje nagranie zamiast transkrypcji" oraz "nie robi
                    // transkrypcji, gdy telefon zablokowany" - to jest odpowiedź
                    // na oba, bo rozpoznawanie na urządzeniu nie potrzebuje ani
                    // sieci, ani odblokowanego ekranu, ani wolnego mikrofonu.
                    val transcript = captured?.pcm?.let { pcm ->
                        // Opus rozkodowuje się na 48 kHz, a rozpoznawanie mowy
                        // pracuje na 16 kHz. Przeliczamy sami - patrz
                        // PcmResampler; podanie 48 kHz i liczenie na to, że
                        // usługa sobie poradzi, byłoby zakładem o całą
                        // transkrypcję.
                        val speechPcm = pl.victor.app.audio.PcmResampler.resample(
                            pcm = pcm,
                            sourceRate = pl.victor.app.audio.OpusDecoder.SAMPLE_RATE
                        )
                        speechToText.transcribe(
                            pcm = speechPcm,
                            sampleRate = pl.victor.app.audio.PcmResampler.SPEECH_SAMPLE_RATE,
                            languageTag = languageTagFor(language)
                        )
                    }
                    if (!transcript.isNullOrBlank()) {
                        Log.i(TAG, "Nagranie z okularów przepisane lokalnie: $transcript")
                        silentScoTurns = 0
                        conversationalMode.onAiFinishedSpeaking()
                        handleUserTrigger(TriggerSource.WAKE_WORD, transcript)
                        return@launch
                    }
                    // Capabilities z tabeli, NIE z getOrCreateProvider(): to
                    // drugie rzuca wyjątkiem przy braku klucza API i potrafi
                    // pójść do sieci po listę modeli. Tutaj potrzebujemy tylko
                    // odpowiedzi "czy ten model przyjmuje nagrania", a wyjątek
                    // z korutyny bez catcha wywróciłby aplikację.
                    val providerId = settings.getActiveProvider()
                    val modelHearsAudio = settings.hasApiKey(providerId) &&
                        AIProviderFactory.getCapabilitiesFor(providerId).supportsAudio
                    if (recording != null && modelHearsAudio) {
                        Log.i(
                            TAG,
                            "Rozpoznawanie nic nie usłyszało, ale mam " +
                                "%.1f s".format(seconds) +
                                " dźwięku z okularów - pytam modelu nagraniem"
                        )
                        silentScoTurns = 0
                        conversationalMode.onAiFinishedSpeaking()
                        handleUserTrigger(
                            TriggerSource.WAKE_WORD,
                            AUDIO_QUESTION_PROMPT,
                            audioQuestion = recording
                        )
                        return@launch
                    }

                    val switched = noteSilentTurn(overSco)
                    val message = switched ?: silenceMessage(captured)
                    Log.i(TAG, "Nasłuch bez wypowiedzi: $message")
                    if (fromGlasses) glassesManager.playGlassesTone(GlassesProtocol.TONE_ERROR)
                    if (switched != null) {
                        // Łącze SCO trzeba rozebrać OD RAZU, zanim cokolwiek
                        // powiemy. Zwykłe zwolnienie ma karencję (patrz
                        // BluetoothAudioRouter.release), więc komunikat o
                        // przełączeniu poszedłby dokładnie tą martwą drogą,
                        // którą właśnie wyłączamy - i nikt by go nie usłyszał.
                        audio.resetConversationRouting()
                        held = false
                        audio.speak(message, language = settings.getResponseLanguage())
                    }
                    // Stan błędu ustawiamy RÓWNIEŻ dla tury z okularów. Sam sygnał
                    // dźwiękowy nie mówi, co poszło nie tak - a to była dokładnie
                    // zgłoszona sytuacja: "słychać dźwięk wybudzenia, ale nic więcej".
                    // Po sięgnięciu po telefon ma tam czekać odpowiedź, nie pusty ekran.
                    // Nie blokuje to kolejnych tur - claimIdle() sprząta stan błędu.
                    _state.value = OrchestratorState.Error(message)
                    // Bez tego tryb konwersacyjny zostawał uciszony na stałe:
                    // onAiStartedSpeaking() wyżej anulował nasłuch, a nikt by go
                    // już nie wznowił - jedna cisza kończyłaby całą rozmowę.
                    conversationalMode.onAiFinishedSpeaking()
                    return@launch
                }
                silentScoTurns = 0
                Log.i(TAG, "Usłyszałem: \"$heard\"")
                handleUserTrigger(
                    if (fromGlasses) TriggerSource.WAKE_WORD else TriggerSource.VOICE,
                    heard
                )
            } finally {
                // detach(), nie stop(): ten blok wykonuje się także po
                // ANULOWANIU tury, a wtedy każde wywołanie zawieszalne
                // natychmiast rzuca - subskrypcja BLE zostałaby zarejestrowana
                // na zawsze. Przerwana tura nie ma zresztą czego dekodować;
                // gałąź ciszy wyżej zdążyła już wziąć wynik przez stop().
                glassesCapture?.detach()
                if (held) audio.endConversationRouting()
            }
        }
    }

    /**
     * Zlicza tury przez profil rozmowy (SCO/HFP), które skończyły się ciszą - i
     * po serii takich sam przełącza się na mikrofon telefonu.
     *
     * ## Dlaczego to jest potrzebne
     * Zestawienie SCO **zawiesza odtwarzanie A2DP**. Zestaw, który zgłasza
     * profil rozmowy, ale go porządnie nie obsługuje, daje więc najgorszy
     * możliwy wynik naraz: okulary milkną (A2DP stoi) i nic nie słyszą (SCO nie
     * niesie dźwięku). Z zewnątrz to dokładnie zgłoszony objaw - "dźwięk
     * wybudzenia jest, po czym cisza i brak reakcji" - i nie ma z tego wyjścia,
     * bo każda kolejna tura powtarza ten sam błąd.
     *
     * Trzy tury z rzędu to nie przypadek: raz można się rozmyślić, dwa razy
     * można nie zdążyć, trzy razy pod rząd znaczy, że tą drogą dźwięk nie idzie.
     * Wyłączenie jest zapamiętane i odwracalne w Ustawieniach.
     *
     * @return komunikat do pokazania, gdy właśnie doszło do przełączenia
     */
    private fun noteSilentTurn(overSco: Boolean): String? {
        if (!overSco) return null
        silentScoTurns++
        if (silentScoTurns < SILENT_SCO_LIMIT) return null
        silentScoTurns = 0
        settings.setGlassesMicEnabled(false)
        audio.setGlassesMicEnabled(false)
        Log.w(TAG, "Trzy ciche tury przez SCO - przechodzę na mikrofon telefonu")
        // Samo mówienie należy do wołającego: komunikat musi pójść DOPIERO po
        // rozebraniu łącza SCO, inaczej nie da się go usłyszeć.
        return "Mikrofon okularów nie zbiera dźwięku, więc przełączam się na " +
            "mikrofon telefonu. Odpowiedzi dalej będą słyszalne w okularach. " +
            "Możesz to cofnąć w Ustawieniach."
    }

    /**
     * Nasłuchuje, ale nie dłużej, niż użytkownik faktycznie mówi.
     *
     * ## Dlaczego to wyścig, a nie zwykłe wywołanie
     * Rozpoznawanie mowy ma własny limit - piętnaście sekund - i czeka do końca,
     * gdy nic nie słyszy. Przy telefonie zablokowanym w kieszeni to reguła, nie
     * wyjątek: użytkownik mówi trzy sekundy, a tura stoi jeszcze dwanaście.
     * Zgłoszono to wprost: "przestaje mówić, a nagranie dalej długo trwa".
     *
     * Okulary nadają pakiety tylko wtedy, gdy w mikrofonie coś jest, więc cisza
     * w ICH strumieniu jest lepszym sygnałem końca wypowiedzi niż zegar
     * rozpoznawania. Wygrywa to, co przyjdzie pierwsze: rozpoznany tekst albo
     * cisza z okularów. Gdy okulary nie nadają, drugi tor nigdy nie kończy i
     * decyduje samo rozpoznawanie - czyli zachowanie sprzed tej zmiany.
     */
    private suspend fun listenUntilSpeechEnds(
        languageTag: String,
        capture: GlassesVoiceCapture?
    ): String? {
        if (capture == null) return conversationalMode.listenOnce(languageTag)

        return coroutineScope {
            val listening = async {
                conversationalMode.listenOnce(languageTag)
            }
            val glassesQuiet = async { capture.awaitSpeechEnd() }
            val result = select<String?> {
                listening.onAwait { it }
                glassesQuiet.onAwait {
                    Log.i(TAG, "Okulary ucichły przed rozpoznawaniem - kończę nasłuch")
                    null
                }
            }
            // Przegrany tor nie ma już nic do zrobienia. Anulowanie zwycięzcy
            // jest bezpieczne - zakończona korutyna ignoruje cancel().
            listening.cancel()
            glassesQuiet.cancel()
            result
        }
    }

    /**
     * Co powiedzieć po nasłuchu, który nic nie usłyszał.
     *
     * "Nic nie usłyszałem" jest prawdziwe, ale bezużyteczne - nie odróżnia trzech
     * zupełnie różnych awarii: użytkownik się nie odezwał, okulary nie przesłały
     * dźwięku, albo przesłały, a my nie umiemy go rozkodować. Licznik pakietów
     * BLE rozstrzyga to jednoznacznie - i to bez wchodzenia w diagnostykę.
     */
    private fun silenceMessage(capture: GlassesVoiceCapture.Result?): String {
        // Najpierw prawdziwa awaria, jeśli była. Zajęty mikrofon, brak sieci
        // czy odmowa uprawnienia to NIE jest "nic nie usłyszałem" - a właśnie
        // tak wyglądały do tej pory, bo rozpoznawanie zwraca przy każdym błędzie
        // to samo puste `null`.
        speechToText.lastFailureReason()?.let { reason ->
            return "Rozpoznawanie mowy nie zadziałało: $reason."
        }
        if (capture == null) return "Nic nie usłyszałem."
        return when {
            capture.packets == 0 -> {
                val route = if (audio.hasConversationMic()) {
                    "Telefon widzi bluetoothowy mikrofon, więc pytanie miało którędy " +
                        "pójść - mów wyraźnie zaraz po sygnale."
                } else {
                    "Telefon NIE widzi mikrofonu okularów (brak profilu rozmowy) - " +
                        "sparuj je dodatkowo jako zestaw słuchawkowy w ustawieniach Bluetooth."
                }
                "Nic nie usłyszałem, a okulary nie przysłały dźwięku po BLE. $route"
            }
            capture.decodedPackets == 0 ->
                "Nic nie usłyszałem. Okulary przysłały ${capture.packets} pakietów " +
                    "dźwięku po BLE, ale nie dały się rozkodować - szczegóły w " +
                    "Diagnostyce, pomiar strumienia z mikrofonu."
            // Rozkodowane, ale za krótkie, żeby cokolwiek z tego wynikało - to
            // NIE jest wina modelu, więc nie odsyłamy do zmiany providera.
            !capture.hasAudio ->
                "Nic nie usłyszałem. Z okularów przyszedł tylko urywek dźwięku (" +
                    "%.1f s".format(capture.audioSeconds) + ") - za mało na pytanie. " +
                    "Zacznij mówić zaraz po sygnale wybudzenia."
            else ->
                "Nic nie usłyszałem. Z okularów przyszło " +
                    "%.1f s".format(capture.audioSeconds) + " dźwięku, ale wybrany " +
                    "model nie przyjmuje nagrań - przełącz się na Gemini albo mów " +
                    "wyraźniej do mikrofonu telefonu."
        }
    }

    private fun handleButtonAction(action: ButtonAction) {
        Log.i(TAG, "Button action: $action")
        when (action) {
            // Pojedyncze kliknięcie = "chcę o coś zapytać", więc SŁUCHAMY, a nie
            // od razu robimy zdjęcie. Ma to dodatkowe, bardzo praktyczne
            // znaczenie: na tym egzemplarzu okularów WŁASNE słowo wybudzenia
            // przychodzi tą samą ramką co przycisk (notify 0x03, w dzienniku
            // "wciśnięto przycisk AI") - a nie 0x17/0x18, jak w aplikacji
            // producenta. Gdyby to od razu wywoływało aparat, wybudzenie głosem
            // kończyłoby się cichym zdjęciem zamiast rozmowy.
            // Zdjęcie i tak poleci, jeśli model uzna, że bez obrazu nie odpowie.
            ButtonAction.QUICK_QUESTION -> startVoiceTurn(fromGlasses = true)
            // Podwójne kliknięcie = JEDYNY pewny, fizyczny gest, który ZAWSZE
            // robi zdjęcie. Odkąd pojedyncze kliknięcie zaczęło słuchać, aparat
            // ruszał wyłącznie wtedy, gdy model sam o obraz poprosił - czyli z
            // punktu widzenia użytkownika "zdjęć w ogóle nie robi". Tu nie ma
            // żadnego wnioskowania: dwa kliknięcia to obraz i opis tego, co
            // widać, niezależnie od tego, co akurat myśli model.
            ButtonAction.LOOK_AND_DESCRIBE -> askAboutView()
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
     *
     * Routing decyduje się w trzech warstwach (od najtańszej do najbogatszej):
     *
     * - **Warstwa 0 - odruch.** [SmartActionDetector.detectCritical]: garść komend,
     *   które muszą zadziałać natychmiast i offline ("stop", "zrób zdjęcie",
     *   "włącz latarkę"). Dopasowanie jest ścisłe - całe zdanie, nie fragment.
     * - **Warstwa 1 - rozumienie.** Wszystko inne idzie do AI, które jest routerem:
     *   samo odpowiada, samo prosi o narzędzia znacznikiem `[[ACTION: ...]]`
     *   (patrz [SmartActionDetector.AI_ACTION_CAPABILITIES_PROMPT]) i samo prosi
     *   o zdjęcie (`take_photo`), gdy bez obrazu nie odpowie.
     * - **Warstwa 2 - awaria.** Gdy AI jest niedostępne (brak klucza, brak
     *   pobranego modelu lokalnego), wracamy do pełnej detekcji wzorcami
     *   [SmartActionDetector.detect]. Lepsza niedoskonała komenda niż komunikat
     *   o błędzie.
     *
     * @param forceVision wymusza zrobienie zdjęcia niezależnie od źródła triggera.
     *   Używane, gdy warstwa 1 poprosiła o obraz - patrz obsługa [Action.TakePhoto].
     */
    fun handleUserTrigger(
        trigger: TriggerSource,
        textQuestion: String = "",
        forceVision: Boolean = false,
        audioQuestion: ByteArray? = null
    ) {
        if (!claimIdle()) {
            Log.w(TAG, "Already processing, ignoring trigger")
            return
        }

        // Z nagraniem zamiast tekstu warstwy 0 i 2 nie mają czego dopasowywać:
        // `textQuestion` jest wtedy instrukcją dla modelu, a nie tym, co
        // powiedział użytkownik. Sprawdzanie ich na takim tekście mogłoby
        // odpalić przypadkową komendę - dlatego wszystko idzie prosto do modelu.
        val textIsQuestion = audioQuestion == null

        // === KOMENDY STERUJĄCE ROZMOWĄ (persona, reset) - zanim cokolwiek innego ===
        // Muszą być sprawdzone przed detekcją akcji: "bądź Sterna" nie pasuje do
        // żadnego wzorca akcji, więc poleciałoby jako zwykłe pytanie do AI.
        if (textIsQuestion && textQuestion.isNotBlank() && handleMetaCommand(textQuestion)) {
            return
        }

        // === WARSTWA 0: ODRUCH ===
        // Tylko komendy krytyczne czasowo. Reszta ma iść do AI, bo wzorce nie
        // rozumieją intencji ("daj znać Ani, że się spóźnię" to też SMS).
        if (!forceVision && textIsQuestion) {
            val critical = actionDetector.detectCritical(textQuestion)
            if (critical.isNotEmpty()) {
                // "Zrób zdjęcie" nie jest akcją do wykonania przez Intent - to
                // wejście w ścieżkę obrazu, tyle że bez pytania od usera.
                if (critical.any { it.type == pl.victor.app.actions.ActionType.TAKE_PHOTO }) {
                    Log.i(TAG, "Warstwa 0: zdjęcie na komendę")
                    handleUserTrigger(trigger, PHOTO_ON_DEMAND_QUESTION, forceVision = true)
                } else {
                    Log.i(TAG, "Warstwa 0: ${critical.joinToString { it.type.name }}")
                    handleActions(critical, textQuestion)
                }
                return
            }
        }

        // === WARSTWA 2: AWARIA (gdy nie ma czym uruchomić warstwy 1) ===
        // Sprawdzane tu, a nie po teście okularów, bo "wyślij SMS do Ani" ma
        // zadziałać także wtedy, gdy okularów nie ma w pobliżu.
        val providerId = settings.getActiveProvider()
        if (!settings.hasApiKey(providerId)) {
            val fallback = if (textIsQuestion) actionDetector.detect(textQuestion) else emptyList()
            if (fallback.isNotEmpty()) {
                Log.i(TAG, "Warstwa 2 (brak AI): ${fallback.joinToString { it.type.name }}")
                handleActions(fallback, textQuestion)
                return
            }
            _state.value = OrchestratorState.Error("Brak klucza API. Ustawienia → Klucz API.")
            return
        }

        // Zdjęcie z góry robimy tylko wtedy, gdy user sam o nie poprosił - fizycznym
        // przyciskiem na okularach - albo gdy zleciła to warstwa 1 znacznikiem
        // take_photo (forceVision). Głos, wake word i tekst idą najpierw jako samo
        // pytanie; jeśli model potrzebuje zobaczyć, sam się o to upomni. Wcześniej
        // aparat startował przy każdym pytaniu i nawet "ile to 20 euro w złotych"
        // czekało na transfer pięciu zdjęć, zanim poszło do modelu.
        val glassesReady = glassesManager.connectionState.value == ConnectionState.READY

        // "Co właśnie widzę", "przeczytaj to", "co to za budynek" - pytania,
        // których BEZ obrazu nie da się sensownie odpowiedzieć. Projekt zakładał,
        // że model sam o zdjęcie poprosi znacznikiem take_photo; w praktyce robi
        // to niesystematycznie i zgłoszono, że takie pytania w ogóle nie
        // uruchamiają aparatu. Rozpoznajemy je więc sami - pewnie i bez
        // dodatkowej tury. Prośba modelu zostaje jako uzupełnienie dla zdań,
        // których wzorce nie łapią.
        //
        // Tylko przy połączonych okularach: bez nich wymuszenie obrazu zamieniło
        // by zwykłe pytanie w błąd "okulary nie są połączone".
        val wantsToLook = glassesReady && audioQuestion == null &&
            actionDetector.needsVision(textQuestion)
        if (wantsToLook) Log.i(TAG, "Pytanie o to, co widać - robię zdjęcie bez pytania modelu")

        val useVision = forceVision || trigger == TriggerSource.BUTTON || wantsToLook

        if (useVision && !glassesReady) {
            // Przycisk na okularach to z założenia pytanie o otoczenie -
            // bez okularów nie ma o czym rozmawiać.
            _state.value = OrchestratorState.Error("Okulary nie są połączone. Ustawienia → Połącz.")
            return
        }
        if (!useVision) {
            Log.i(TAG, "Pytam AI bez zdjęcia - obraz dojdzie tylko jeśli model o niego poprosi")
        }

        activeTurnJob = scope.launch {
            // Łącze audio do okularów bierzemy na CAŁĄ turę - i na słuchanie, i
            // na mówienie. Zestawienie SCO trwa nawet kilka sekund, więc
            // podnoszenie go osobno pod każdy fragment rwałoby rozmowę.
            // Zwraca false, gdy okulary nie są sparowane jako zestaw audio -
            // wtedy wszystko idzie przez telefon, tak jak dotąd.
            val audioHeld = audio.beginConversationRouting()
            try {
                // 1. CAPTURE - adaptacyjny tryb
                val provider = getOrCreateProvider()
                val capabilities = provider.capabilities

                val captureResult = if (useVision) {
                    val preferredMode = pl.victor.app.ai.CaptureMode.valueOf(
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
                    // Powód bierzemy od okularów. Samo "nie udało się pobrać
                    // zdjęcia" nie mówiło NIC - a przyczyny są różne i wymagają
                    // różnych rzeczy: pełna pamięć okularów, zbyt duża
                    // odległość, trwające nagranie wideo.
                    val why = glassesManager.lastPhotoFailure
                    _state.value = OrchestratorState.Error(
                        if (why != null) "Nie udało się zrobić zdjęcia. $why"
                        else "Nie udało się pobrać żadnego zdjęcia"
                    )
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

                // Pobierz aktywną personę (system prompt) i dołóż instrukcję o
                // znaczniku [[ACTION: ...]] - patrz SmartActionDetector.detectAiMarkedActions
                // i executeAiDetectedActions niżej, gdzie odpowiedź jest tym skanowana.
                val persona = getActivePersona()
                // Model musi wiedzieć, czy w TEJ wiadomości dostał obraz i czy w
                // ogóle ma jak go dostać - inaczej albo prosi o zdjęcie, które już
                // ma, albo prosi o nie przy odłączonych okularach.
                val visionStatus = when {
                    photos.isNotEmpty() || (video != null && video.isNotEmpty()) ->
                        "\n\nOBRAZ: masz zdjęcie z kamery okularów w tej wiadomości - " +
                            "odpowiadaj na jego podstawie i NIE proś o kolejne."
                    glassesManager.connectionState.value == ConnectionState.READY ->
                        "\n\nOBRAZ: nie masz zdjęcia, ale okulary są połączone - " +
                            "jeśli musisz zobaczyć, o co pyta user, użyj [[ACTION: type=take_photo]]."
                    else ->
                        "\n\nOBRAZ: nie masz zdjęcia i okulary nie są połączone - " +
                            "nie proś o take_photo, powiedz wprost, że nie możesz tego zobaczyć."
                }
                val effectiveSystemPrompt = persona.systemPrompt +
                    "\n\n" + pl.victor.app.actions.SmartActionDetector.AI_ACTION_CAPABILITIES_PROMPT +
                    visionStatus
                Log.d(TAG, "Using persona: ${persona.name}")

                // 1d2. Wizytówka vCard z kodu QR
                val contactCard = scannedCodes
                    .asSequence()
                    .map { it.rawValue }
                    .filter { pl.victor.app.vision.VCardParser.looksLikeContact(it) }
                    .mapNotNull { pl.victor.app.vision.VCardParser.parse(it) }
                    .firstOrNull()
                if (contactCard != null) {
                    Log.i(TAG, "Odczytano wizytówkę: ${contactCard.name}")
                }

                // 1e0. Data i godzina. Model ich NIE ZNA - nie ma zegara, a jego
                // wiedza kończy się na dacie treningu. Bez tego "jaki dziś
                // dzień", "ile zostało do piątku" czy "umów na jutro na 15"
                // były zgadywaniem podanym pewnym głosem.
                val timeContext = buildTimeContext()

                // 1e. Pamięć długoterminowa - poszukaj podobnych rozmów w historii
                val memoryContext = buildMemoryContext(textQuestion)

                // 1e2. Kalendarz - tylko gdy pytanie faktycznie dotyczy planów
                val calendarContext = buildCalendarContext(textQuestion)

                // 1e3. Gmail - tylko gdy pytanie faktycznie dotyczy poczty
                val gmailContext = buildGmailContext(textQuestion)

                // 1e4. Pogoda - tylko gdy pytanie faktycznie jej dotyczy
                val weatherContext = buildWeatherContext(textQuestion)

                // 1e5. Gdzie jesteśmy - tylko przy pytaniach ZE ZDJĘCIEM.
                // Model patrzący na sam obraz widzi "kościół"; ten sam obraz plus
                // "Rzym, okolice Piazza Navona" pozwala powiedzieć, KTÓRY kościół.
                // Przy pytaniu bez obrazu lokalizacja nic nie wnosi, a kosztuje
                // odczyt pozycji i geokodowanie.
                val locationContext = if (photos.isNotEmpty()) {
                    pl.victor.app.proactive.LocationContext.buildPromptContext(context)
                        ?.also { Log.i(TAG, "Doklejam kontekst lokalizacji") }
                } else {
                    null
                }

                // 1f. Tłumaczenie tekstu z OCR (gdy user prosi o tłumaczenie)
                val translatedOcr = translateOcrIfRequested(textQuestion, ocrContext)

                // Buduj prompt z kontekstem: pamięć + URL + OCR + kontekst rozmowy
                val enhancedPrompt = buildString {
                    append(timeContext).append("\n\n")
                    if (memoryContext != null) {
                        append(memoryContext)
                        append("\n\n")
                    }
                    if (calendarContext != null) {
                        append(calendarContext)
                        append("\n\n")
                    }
                    if (gmailContext != null) {
                        append(gmailContext)
                        append("\n\n")
                    }
                    if (weatherContext != null) {
                        append(weatherContext)
                        append("\n\n")
                    }
                    if (locationContext != null) {
                        append(locationContext)
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
                val useVideoStream = video != null && video.isNotEmpty() && capabilities.supportsVideo

                // Buduje strumień dla danego providera - wywoływane raz na próbę,
                // bo Flow jest leniwy (błąd połączenia wyskakuje dopiero na collect()).
                fun buildStream(p: AIProvider) = if (useVideoStream) {
                    Log.i(TAG, "Używam analyzeVideo (${video!!.size} bytes, ${videoDurationMs}ms)")
                    p.analyzeVideoStream(
                        textQuestion = enhancedPrompt,
                        videoBytes = video,
                        videoDurationMs = videoDurationMs,
                        audioBytes = audioQuestion,
                        scannedCodes = scannedCodes,
                        enableWebSearch = settings.isWebSearchEnabled(),
                        systemPrompt = effectiveSystemPrompt
                    )
                } else {
                    Log.i(TAG, "Używam analyzeStream (${photos.size} zdjęć)")
                    p.analyzeStream(
                        textQuestion = enhancedPrompt,
                        images = photos,
                        audioBytes = audioQuestion,
                        scannedCodes = scannedCodes,
                        enableWebSearch = settings.isWebSearchEnabled(),
                        systemPrompt = effectiveSystemPrompt
                    )
                }

                // Cache jest zawsze kluczowany providerem wybranym w Ustawieniach, nie
                // tym, który faktycznie odpowiedział - inaczej trafienie w cache po
                // fallbacku nigdy by się nie powtórzyło (kolejne zapytanie sprawdza
                // cache pod aktywnym providerem, nie pod tym z fallbacku).
                val cacheProviderId = settings.getActiveProvider()
                val cacheModelId = settings.getSelectedModel(cacheProviderId) ?: "default"
                // Nagranie nie jest kluczem cache'a: dwa różne pytania mają tę
                // samą instrukcję tekstową, więc trafienie byłoby czystym
                // przypadkiem - i odpowiedzią na cudze pytanie.
                val cacheEligible = aiCache.shouldCache(textQuestion) &&
                    photos.isEmpty() && video == null && audioQuestion == null

                // CACHE CHECK - może już mamy odpowiedź?
                val cachedAnswer = if (cacheEligible) {
                    aiCache.get(textQuestion, cacheProviderId, cacheModelId)
                } else null

                var successfulProvider = provider

                if (cachedAnswer != null) {
                    Log.i(TAG, "✅ Odpowiedź z cache (zaoszczędzony request!)")
                    accumulatedText.append(cachedAnswer)
                    // TU CELOWO NIE MÓWIMY. Wspólna ścieżka niżej i tak wypowiada
                    // odpowiedź - ale dopiero PO wycięciu znacznika [[ACTION: ...]].
                    // Wcześniej odpowiedź z cache szła do syntezatora surowa, więc
                    // użytkownik słyszał znacznik przeczytany na głos, a zaraz potem
                    // drugi raz tę samą odpowiedź (QUEUE_FLUSH ucinał pierwszą).
                    _state.value = OrchestratorState.Streaming(cachedAnswer)
                } else {

                // Kolejni kandydaci, gdy aktywny provider zawiedzie zanim wypowiedział
                // choć jeden fragment - tylko providerzy, dla których user już ma klucz.
                // Wyłączalne w Ustawieniach (isAutoProviderFallbackEnabled), bo to
                // zmiana zachowania, nie tylko naprawa - user może wolieć jasny błąd
                // od cichej podmiany providera.
                val candidates = if (settings.isAutoProviderFallbackEnabled()) {
                    fallbackProviderOrder()
                } else {
                    listOf(settings.getActiveProvider())
                }

                var attemptIndex = 0
                while (true) {
                    val attemptProviderId = candidates[attemptIndex]
                    if (attemptIndex > 0 && attemptProviderId == AIProviderFactory.LOCAL_PROVIDER_ID) {
                        // Cichy fallback na model lokalny byłby mylący - to realny spadek
                        // jakości (mały model offline), user powinien wiedzieć, że o to chodzi.
                        audio.speak("Przechodzę na model lokalny, offline.", language = settings.getResponseLanguage())
                    }
                    val attemptProvider = if (attemptIndex == 0) provider else buildProviderForFallback(attemptProviderId)
                    try {
                        // Streaming - każdy fragment natychmiast mówimy
                        buildStream(attemptProvider).collect { chunk ->
                            accumulatedText.append(chunk.text)

                            if (chunk.isFinal) {
                                Log.i(TAG, "Stream complete, ${chunk.tokensUsed} tokens, text len=${accumulatedText.length}")
                                // Wymuś wypowiedzenie ostatniego fragmentu
                                audio.flushStream()

                                // Zapisz do cache pod providerem z Ustawień - patrz komentarz wyżej
                                if (cacheEligible) {
                                    aiCache.put(textQuestion, accumulatedText.toString(), cacheProviderId, cacheModelId)
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
                        successfulProvider = attemptProvider
                        break  // sukces - koniec prób
                    } catch (e: Exception) {
                        // Bezpieczne do ponowienia tylko, gdy nic jeszcze nie zostało
                        // powiedziane - inaczej user usłyszałby dwa zaczątki odpowiedzi.
                        val canRetryWithNext = firstChunk && attemptIndex < candidates.lastIndex
                        if (canRetryWithNext) {
                            Log.w(TAG, "Provider $attemptProviderId zawiódł przed pierwszym fragmentem " +
                                "(próba ${attemptIndex + 1}/${candidates.size}), próbuję kolejnego", e)
                            attemptIndex++
                        } else {
                            throw e
                        }
                    }
                }  // while (próby providerów)
                }  // else dla cache check

                // AI mogło oznaczyć akcję znacznikiem [[ACTION: ...]] (patrz
                // AI_ACTION_CAPABILITIES_PROMPT wyżej) - wytnij go z tego, co user
                // zobaczy/usłyszy, i zapamiętaj wykrytą akcję na potem.
                val (responseText, aiDetectedActions) =
                    actionDetector.detectAiMarkedActions(accumulatedText.toString().trim())

                // === WARSTWA 1 ZLECA WARSTWIE 0: "muszę to zobaczyć" ===
                // Model odpowiedział znacznikiem take_photo, bo bez obrazu nie
                // odpowie na pytanie. Robimy zdjęcie i zadajemy TO SAMO pytanie
                // jeszcze raz, już z obrazem. Ta odpowiedź ("Chwila, spojrzę.")
                // celowo nie trafia do historii ani do kontekstu rozmowy - to nie
                // odpowiedź, tylko prośba o obraz.
                val wantsPhoto = aiDetectedActions.any {
                    it.type == pl.victor.app.actions.ActionType.TAKE_PHOTO
                }
                val executableActions = aiDetectedActions.filterNot {
                    it.type == pl.victor.app.actions.ActionType.TAKE_PHOTO
                }

                if (wantsPhoto && !useVision) {
                    // useVision == false gwarantuje, że ta gałąź nie zapętli się:
                    // powtórka leci z forceVision = true, więc drugi raz tu nie wejdzie.
                    if (glassesManager.connectionState.value == ConnectionState.READY) {
                        Log.i(TAG, "Warstwa 1 poprosiła o zdjęcie - powtarzam pytanie z obrazem")
                        val bridge = responseText.ifBlank { "Chwila, spojrzę." }
                        conversationalMode.onAiStartedSpeaking()
                        audio.speakAndAwait(bridge, language = language)
                        _state.value = OrchestratorState.Idle
                        // Nagranie MUSI polecieć razem z powtórką. Gdy pytanie
                        // przyszło głosem z okularów, `textQuestion` jest tylko
                        // instrukcją ("odpowiedz na pytanie z nagrania") - bez
                        // dźwięku model dostałby zdjęcie i polecenie odnoszące
                        // się do czegoś, czego nie ma.
                        handleUserTrigger(
                            trigger,
                            textQuestion,
                            forceVision = true,
                            audioQuestion = audioQuestion
                        )
                        return@launch
                    }
                    Log.i(TAG, "Warstwa 1 poprosiła o zdjęcie, ale okulary nie są połączone")
                }

                // Gdy model chciał zobaczyć, a okularów nie ma - powiedz to wprost,
                // zamiast wypuścić samo "Chwila, spojrzę." i zamilknąć.
                val answerText = if (wantsPhoto && !useVision) {
                    "Musiałbym to zobaczyć, ale okulary nie są połączone."
                } else {
                    responseText
                }

                val response = AIResponse(
                    text = answerText,
                    providerId = successfulProvider.id
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
                // speakAndAwait, NIE speak: to drugie wraca natychmiast (zleca
                // tylko wypowiedź silnikowi), więc nasłuch startował w trakcie
                // mówienia V.I.C.T.O.R.-a i nagrywał jego własny głos jako
                // kolejne pytanie użytkownika.
                audio.speakAndAwait(response.text, language = language)
                conversationalMode.onAiFinishedSpeaking()

                // Akcja, którą AI oznaczyło znacznikiem [[ACTION: ...]] - ten sam
                // handleActions co dla głosu/przycisku, więc DIRECT nadal pyta o
                // potwierdzenie, a SAFE nadal tylko otwiera zewnętrzną apkę.
                if (executableActions.isNotEmpty()) {
                    handleActions(executableActions, textQuestion)
                }

                // 4. Historia - zapisz pierwsze zdjęcie do pliku (miniatura)
                try {
                    val firstPhotoPath = photos.firstOrNull()?.let { bytes ->
                        photoStorage.savePhoto(bytes, prefix = "conv_${System.currentTimeMillis()}")
                    }
                    history.save(
                        // Puste pytanie zdarza się już tylko przy fizycznym przycisku
                        // na okularach ("popatrz i powiedz, co widzisz"). Głos ma
                        // od tej wersji transkrypcję, więc dawny komunikat o jej
                        // braku byłby dziś nieprawdą.
                        question = textQuestion.ifBlank { "(przycisk na okularach - opis obrazu)" },
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

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Przerwanie na żądanie użytkownika to nie awaria. Bez tej gałęzi
                // ogólny catch niżej złapałby je (CancellationException JEST
                // wyjątkiem) i pokazał "Nieoczekiwany błąd" po każdym "cicho".
                Log.i(TAG, "Tura przerwana")
                throw e
            } catch (e: AIProviderException) {
                Log.e(TAG, "AI error", e)
                _state.value = OrchestratorState.Error(
                    "Błąd AI: ${e.message}" + if (e.isRetryable) " (spróbuj ponownie)" else ""
                )
                // Wznów nasłuch: nasłuch został wstrzymany przed mówieniem, a
                // błąd nie może zostawić trybu konwersacyjnego głuchym na stałe.
                conversationalMode.onAiFinishedSpeaking()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                _state.value = OrchestratorState.Error("Nieoczekiwany błąd: ${e.message}")
                conversationalMode.onAiFinishedSpeaking()
            } finally {
                if (audioHeld) audio.endConversationRouting()
            }
        }
    }

    /**
     * Obsługuje komendy sterujące samą rozmową - zmianę persony i reset kontekstu.
     * Sprawdzane przed detekcją akcji i przed AI - patrz wywołanie w [handleUserTrigger].
     *
     * @return `true` gdy komenda została obsłużona (nic więcej nie powinno się zdarzyć
     *         dla tego triggera)
     */
    private fun handleMetaCommand(text: String): Boolean {
        // "Stop"/"cicho" ucisza V.I.C.T.O.R.-a, a nie steruje odtwarzaczem muzyki.
        // Osobno od warstwy 0, bo tam wszystko kończy się akcją przez Intent,
        // a tu chodzi tylko o zamknięcie ust syntezatorowi.
        if (SILENCE_COMMAND_REGEX.matches(text.lowercase().trim().trimEnd('.', '!', '?'))) {
            Log.i(TAG, "Komenda ciszy: \"$text\"")
            audio.stopSpeaking()
            conversationalMode.onAiFinishedSpeaking()
            _state.value = OrchestratorState.Idle
            return true
        }

        if (pl.victor.app.conversation.MetaCommands.detectContextReset(text)) {
            conversationContext.clear()
            val speech = "Zaczynamy od nowa."
            audio.speak(speech, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(speech)
            return true
        }

        when (val attempt = pl.victor.app.conversation.MetaCommands.detectPersonaSwitchAttempt(text)) {
            is pl.victor.app.conversation.PersonaSwitchAttempt.Recognized -> {
                settings.setSelectedPersonaId(attempt.personaId)
                val persona = PersonaRegistry.findById(attempt.personaId) ?: PersonaRegistry.default()
                val speech = "OK, jestem teraz ${persona.name}."
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return true
            }
            is pl.victor.app.conversation.PersonaSwitchAttempt.Unrecognized -> {
                val speech = "Nie znam persony „${attempt.requestedName}”. Dostępne: " +
                    PersonaRegistry.all().joinToString(", ") { it.name }
                audio.speak(speech, language = settings.getResponseLanguage())
                _state.value = OrchestratorState.Completed(speech)
                return true
            }
            null -> return false
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
            // Jeśli wszystko to accessibility, zwracamy bezpośrednio.
            // Idle nie przechodzi przez wznowienie nasłuchu (patrz init), więc
            // tryb konwersacyjny wznawiamy tu wprost.
            if (actions.size == accessibilityActions.size) {
                _state.value = OrchestratorState.Idle
                conversationalMode.onAiFinishedSpeaking()
                return
            }
        }

        val mode = ActionMode.fromName(settings.getActionMode())

        scope.launch {
            val results = actions.map { rawAction ->
                // SmartActionDetector wyciąga z mowy samo słowo po "do"/"pod" - to
                // zwykle nazwa kontaktu, nie numer. ActionExecutor (tryb SAFE) wsadza
                // ten tekst wprost do intencji "tel:"/"smsto:" - Android nie rozwiązuje
                // tam nazw, więc "zadzwoń do mamy" bez tego kroku nigdy nie działało
                // w domyślnym trybie. Rozwiązanie robimy raz, przed obiema ścieżkami.
                val action = resolveContactIfNeeded(rawAction)
                if (action == null) {
                    val name = (rawAction as? Action.SendSms)?.to
                        ?: (rawAction as? Action.MakeCall)?.to
                    val failure = ActionResult.Failed("Nie znalazłem kontaktu „$name” w książce adresowej.")
                    Log.w(TAG, "Kontakt nierozwiązany: $name")
                    return@map rawAction to failure
                }

                // Spróbuj DIRECT jeśli tryb DIRECT i akcja to obsługuje
                val result = if (mode == ActionMode.DIRECT &&
                    (action is Action.SendSms || action is Action.MakeCall ||
                        action is Action.CreateCalendarEvent)) {
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

            // Mów i pokaż. Czekamy na koniec wypowiedzi, bo w trybie
            // konwersacyjnym zaraz potem wraca nasłuch - inaczej mikrofon
            // łapałby potwierdzenie akcji jako kolejne pytanie.
            conversationalMode.onAiStartedSpeaking()
            audio.speakAndAwait(speech, language = settings.getResponseLanguage())
            _state.value = OrchestratorState.Completed(speech)
            conversationalMode.onAiFinishedSpeaking()
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
            // Idle nie wznawia nasłuchu samo (patrz init) - a anulowanie kończy
            // turę tak samo jak wykonanie akcji.
            conversationalMode.onAiFinishedSpeaking()
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

    /**
     * Publikuje model wynikający z BIEŻĄCYCH ustawień, bez tworzenia providera i bez sieci.
     *
     * [_currentModelId] było dotąd aktualizowane wyłącznie w [getOrCreateProvider], czyli
     * dopiero przy pierwszym realnym zapytaniu do AI. Po zmianie modelu w ustawieniach
     * badge na ekranie głównym pokazywał więc poprzedni model aż do następnego pytania.
     * Ekran główny woła to przy wejściu i po powrocie z ustawień.
     */
    fun publishConfiguredModel() {
        val providerId = settings.getActiveProvider()
        _currentModelId.value = if (providerId == AIProviderFactory.LOCAL_PROVIDER_ID) {
            pl.victor.app.localmodel.LocalModelCatalog.QWEN_0_8B.id
        } else {
            settings.getSelectedModel(providerId)
                ?: pl.victor.app.data.ModelRegistry.defaultFor(providerId)?.id
        }
    }

    // === Private ===

    private suspend fun getOrCreateProvider(): AIProvider {
        val providerId = settings.getActiveProvider()

        if (providerId == AIProviderFactory.LOCAL_PROVIDER_ID) {
            // Bez klucza, bez walidacji modeli u zdalnego providera - po co
            // próbować sieci dla trybu, którego cały sens to działanie offline.
            if (currentProviderId != providerId) {
                currentProvider = AIProviderFactory.create(providerId, "", context).provider
                currentProviderId = providerId
                activeModelId = null
            }
            // Publikuj realny model lokalny, nie null. Przy null badge na ekranie głównym
            // wpadał w swój fallback i pokazywał Gemini, mimo że aktywny był model offline.
            _currentModelId.value = pl.victor.app.localmodel.LocalModelCatalog.QWEN_0_8B.id
            return currentProvider!!
        }

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
                context = context,
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
                pl.victor.app.data.ModelSource.AUTO_MIGRATED) {
                withMetadata.resolution.warning?.let { warning ->
                    if (warning is pl.victor.app.data.ModelWarning.AutoMigrated) {
                        settings.setSelectedModel(providerId, warning.newModelId)
                    }
                }
            }
        }
        return currentProvider!!
    }

    /**
     * Kolejność providerów do próby: aktywny z Ustawień pierwszy, potem reszta
     * providerów, dla których user w ogóle ma wpisany klucz API - w kolejności
     * z [AIProviderFactory.supportedProviders]. Nie próbujemy providera bez klucza,
     * bo to i tak od razu by zawiodło.
     */
    private fun fallbackProviderOrder(): List<String> {
        val primary = settings.getActiveProvider()
        val others = AIProviderFactory.supportedProviders()
            .map { it.id }
            .filter { it != primary && settings.hasApiKey(it) }
        return listOf(primary) + others
    }

    /**
     * Buduje provider dla próby fallbacku - celowo NIE dotyka `currentProvider`/
     * `currentProviderId` ani ustawień. Fallback jest jednorazowy, dla tego
     * konkretnego zapytania - nie zmienia trwale wybranego providera usera.
     */
    private suspend fun buildProviderForFallback(providerId: String): AIProvider {
        if (providerId == AIProviderFactory.LOCAL_PROVIDER_ID) {
            return AIProviderFactory.create(providerId, "", context).provider
        }

        val apiKey = settings.getApiKey(providerId)
            ?: throw AIProviderException(
                "Brak klucza API dla $providerId",
                providerId = providerId,
                isRetryable = false
            )
        val preferredModel = settings.getSelectedModel(providerId)
        val available = try {
            RemoteModelValidator(apiKey, providerId).fetchAvailableModels()
        } catch (e: Exception) {
            Log.w(TAG, "Could not validate models for fallback provider $providerId", e)
            emptyList()
        }
        return AIProviderFactory.create(
            providerId = providerId,
            apiKey = apiKey,
            context = context,
            preferredModelId = preferredModel,
            availableFromProvider = available
        ).provider
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
            val targetName = pl.victor.app.translation.SimultaneousTranslator.languageName(target)
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

        /**
         * Po tylu cichych turach z rzędu przez SCO aplikacja sama wraca na
         * mikrofon telefonu - patrz [noteSilentTurn].
         */
        private const val SILENT_SCO_LIMIT = 3

        /**
         * Co powiedzieć modelowi, gdy pytanie idzie NAGRANIEM, a nie tekstem.
         *
         * Model dostaje wtedy dźwięk z mikrofonu okularów zamiast transkrypcji -
         * musi więc wiedzieć, że pytania ma szukać w nagraniu, a nie w tym
         * zdaniu, i że odpowiedź będzie odczytana na głos.
         *
         * ## Dlaczego jest tu ostrzeżenie o danych na żywo
         * Kalendarz, poczta i prognoza są doklejane do promptu tylko wtedy, gdy
         * PYTANIE ich dotyczy - a przy nagraniu nie wiemy, o co użytkownik pyta,
         * dopóki model go nie wysłucha. Bez tego zdania model dostałby pytanie
         * "jaka jutro pogoda", nie dostałby prognozy i odpowiedziałby z własnej
         * pamięci, czyli zmyślił. Lepiej, żeby powiedział wprost, że tej drogi
         * trzeba użyć inaczej.
         */
        private const val AUDIO_QUESTION_PROMPT =
            "W załączonym nagraniu użytkownik zadaje pytanie. Wysłuchaj go i " +
                "odpowiedz na nie. Nie transkrybuj nagrania i nie opisuj, co " +
                "słyszysz - po prostu odpowiedz, krótko i tak, jak się mówi na " +
                "głos. Jeśli nagranie jest niewyraźne albo nie ma w nim pytania, " +
                "powiedz to jednym zdaniem.\n" +
                "UWAGA: przy pytaniu zadanym nagraniem NIE masz dołączonej " +
                "prognozy pogody, kalendarza ani poczty. Jeśli pytanie ich " +
                "dotyczy, powiedz krótko, że tego akurat nie sprawdzisz i " +
                "poproś o powtórzenie pytania w aplikacji - NIE zgaduj " +
                "temperatury, godzin spotkań ani treści maili."

        /** Komendy uciszające syntezator - patrz [handleMetaCommand]. */
        private val SILENCE_COMMAND_REGEX =
            Regex("""^(stop|przesta[nń]|cicho|zamilcz|anuluj|dosy[cć])$""")

        /**
         * Pytanie podstawiane, gdy user powiedział samo "zrób zdjęcie" (warstwa 0).
         * Bez niego model dostałby jako pytanie polecenie "zrób zdjęcie" razem ze
         * zdjęciem i odpowiadałby na nie dosłownie.
         */
        /**
         * Pytanie dla ścieżki „popatrz i powiedz": przycisk „Pokaż" i komenda
         * „zrób zdjęcie".
         *
         * Zdanie o tekście nie jest ozdobnikiem. Zdjęcia z okularów idą domyślnie
         * jako miniatury, a na miniaturze model potrafi źle odczytać napis, który
         * lokalny OCR (ML Kit, na urządzeniu, ułamek sekundy) odczyta pewnie.
         * To zdanie włącza tę ścieżkę - [shouldRunOcr] szuka w pytaniu słowa
         * „przeczytaj" - i jednocześnie mówi modelowi, czego od niego chcemy.
         */
        private const val PHOTO_ON_DEMAND_QUESTION =
            "Opisz krótko, co widać na tym zdjęciu. Jeśli jest na nim tekst, " +
                "przeczytaj to, co istotne."
    }
}

/**
 * Stan orkiestratora - obserwowany przez UI
 */
sealed class OrchestratorState {
    object Idle : OrchestratorState()

    /** Mikrofon otwarty - czekamy, aż użytkownik powie, o co mu chodzi. */
    object Listening : OrchestratorState()
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
