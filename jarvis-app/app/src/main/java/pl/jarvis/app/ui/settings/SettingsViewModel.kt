package pl.jarvis.app.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jarvis.app.AIOrchestrator
import pl.jarvis.app.ai.AIProviderFactory
import pl.jarvis.app.data.SettingsRepository
import pl.jarvis.app.JarvisApplication
import pl.jarvis.app.ai.AIProviderException

/**
 * ViewModel dla ekranu ustawień.
 * Ładuje i zapisuje preferencje przez SettingsRepository.
 * Testuje połączenie z aktywnym providerem AI.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApplication
    private val settings: SettingsRepository = app.settings

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun loadState(): SettingsState = SettingsState(
        activeProviderId = settings.getActiveProvider(),
        selectedModelId = settings.getSelectedModel(settings.getActiveProvider()),
        apiKeys = loadAllKeys(),
        webSearchEnabled = settings.isWebSearchEnabled(),
        responseLanguage = settings.getResponseLanguage(),
        wakeWordEnabled = settings.isWakeWordEnabled(),
        wakeWordId = settings.getSelectedWakeWordId(),
        customWakeWord = settings.getCustomWakeWord(),
        customKeywordPath = settings.getCustomKeywordPath(),
        customModelPath = settings.getCustomModelPath(),
        picovoiceAccessKey = settings.getPicovoiceAccessKey(),
        captureCount = settings.getCaptureCount(),
        captureIntervalMs = settings.getCaptureIntervalMs(),
        ttsVoice = settings.getTtsVoiceName(),
        ttsSpeechRate = settings.getTtsSpeechRate(),
        ttsPitch = settings.getTtsPitch(),
        availableVoices = app.audio.availableVoices.value,
        currentVoice = app.audio.currentVoice.value,
        selectedPersonaId = settings.getSelectedPersonaId(),
        customPersonaPrompt = settings.getCustomPersonaPrompt(),
        statusMessage = null,
        isTestRunning = false
    )

    private fun loadAllKeys(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        AIProviderFactory.supportedProviders().forEach { provider ->
            map[provider.id] = settings.getApiKey(provider.id) ?: ""
        }
        return map
    }

    init {
        // Obserwuj listę głosów (może się załadować z opóźnieniem)
        viewModelScope.launch {
            app.audio.availableVoices.collect { voices ->
                _state.value = _state.value.copy(availableVoices = voices)
            }
        }

        // Obserwuj aktualny głos
        viewModelScope.launch {
            app.audio.currentVoice.collect { voice ->
                _state.value = _state.value.copy(currentVoice = voice)
            }
        }

        // Przy starcie - zastosuj zapisane ustawienia głosu
        applyStoredVoiceSettings()
    }

    private fun applyStoredVoiceSettings() {
        val storedVoice = settings.getTtsVoiceName()
        if (!storedVoice.isNullOrBlank()) {
            app.audio.setVoice(storedVoice)
        }
        app.audio.setSpeechRate(settings.getTtsSpeechRate())
        app.audio.setPitch(settings.getTtsPitch())
    }

    fun setTtsVoice(voiceName: String) {
        settings.setTtsVoiceName(voiceName)
        app.audio.setVoice(voiceName)
    }

    fun setTtsRate(rate: Float) {
        settings.setTtsSpeechRate(rate)
        app.audio.setSpeechRate(rate)
        _state.value = _state.value.copy(ttsSpeechRate = rate)
    }

    fun setTtsPitch(pitch: Float) {
        settings.setTtsPitch(pitch)
        app.audio.setPitch(pitch)
        _state.value = _state.value.copy(ttsPitch = pitch)
    }

    fun testVoice() {
        app.audio.testCurrentVoice()
        _state.value = _state.value.copy(statusMessage = "🔊 Odsłuchuję...")
    }

    fun setPersona(personaId: String) {
        settings.setSelectedPersonaId(personaId)
        _state.value = _state.value.copy(selectedPersonaId = personaId)
    }

    fun setCustomPersonaPrompt(prompt: String) {
        settings.setCustomPersonaPrompt(prompt)
        _state.value = _state.value.copy(customPersonaPrompt = prompt)
    }

    fun getApiKey(providerId: String): String =
        _state.value.apiKeys[providerId] ?: ""

    fun setActiveProvider(providerId: String) {
        settings.setActiveProvider(providerId)
        // Załaduj model dla nowego providera
        val model = settings.getSelectedModel(providerId)
        _state.value = _state.value.copy(
            activeProviderId = providerId,
            selectedModelId = model,
            statusMessage = null
        )
    }

    fun setSelectedModel(modelId: String?) {
        val providerId = _state.value.activeProviderId
        if (modelId == null) {
            settings.clearSelectedModel(providerId)
        } else {
            settings.setSelectedModel(providerId, modelId)
        }
        _state.value = _state.value.copy(selectedModelId = modelId)
    }

    fun setApiKey(providerId: String, key: String) {
        if (key.isBlank()) {
            settings.setApiKey(providerId, key)  // zapisze pusty? nie, setApiKey tego nie pozwala
            // actually: sprawdzimy i pokażemy error
            _state.value = _state.value.copy(
                statusMessage = "Klucz API nie może być pusty"
            )
            return
        }
        settings.setApiKey(providerId, key)
        val newKeys = _state.value.apiKeys.toMutableMap()
        newKeys[providerId] = key
        _state.value = _state.value.copy(
            apiKeys = newKeys,
            statusMessage = "Zapisano klucz dla ${AIProviderFactory.supportedProviders().find { it.id == providerId }?.displayName}"
        )
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        settings.setWebSearchEnabled(enabled)
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }

    fun setResponseLanguage(lang: String) {
        settings.setResponseLanguage(lang)
        _state.value = _state.value.copy(responseLanguage = lang)
    }

    /**
     * Włącza albo wyłącza wykrywanie komendy głosowej.
     *
     * Sam zapis preferencji nie wystarcza: detektor startuje w `MainActivity.onCreate`,
     * więc przełącznik działałby dopiero po ponownym uruchomieniu aplikacji.
     * Dlatego zmiana od razu podnosi albo zatrzymuje Porcupine.
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        settings.setWakeWordEnabled(enabled)
        _state.value = _state.value.copy(wakeWordEnabled = enabled)

        viewModelScope.launch {
            val detector = app.wakeWordDetector
            if (!enabled) {
                runCatching { detector.stopListening() }
                    .onFailure { Log.w(TAG, "Nie udało się zatrzymać wykrywania komendy", it) }
                return@launch
            }

            val accessKey = settings.getPicovoiceAccessKey()
            if (accessKey.isBlank()) {
                // Przełącznik jest zablokowany bez klucza, ale stan mógł przyjść
                // z preferencji zapisanych wcześniej.
                _state.value = _state.value.copy(
                    statusMessage = "Brak klucza Picovoice - wykrywanie komendy nie ruszy."
                )
                return@launch
            }

            val entry = settings.getSelectedWakeWordEntry()
            val result = runCatching {
                detector.initialize(
                    accessKey = accessKey,
                    keyword = entry.porcupineKeyword ?: settings.getSelectedWakeWord(),
                    keywordPath = settings.getCustomKeywordPath(),
                    modelPath = settings.getCustomModelPath()
                )
            }.getOrElse {
                Log.e(TAG, "Inicjalizacja Porcupine nie powiodła się", it)
                pl.jarvis.app.wakeword.InitResult.Failed(it.message ?: "nieznany błąd")
            }

            if (result.isSuccess) {
                detector.startListening()
            }
            // Każdy przypadek niesie własną instrukcję - inaczej użytkownik
            // widzi tylko, że nic się nie dzieje.
            _state.value = _state.value.copy(statusMessage = result.message())
        }
    }

    /** Ścieżka do własnego modelu `.ppn` - bez niego fraza spoza listy nie ruszy. */
    fun setCustomKeywordPath(path: String) {
        settings.setCustomKeywordPath(path.trim())
        _state.value = _state.value.copy(customKeywordPath = path.trim())
    }

    /** Ścieżka do modelu językowego `.pv` - tylko dla fraz nieangielskich. */
    fun setCustomModelPath(path: String) {
        settings.setCustomModelPath(path.trim())
        _state.value = _state.value.copy(customModelPath = path.trim())
    }

    fun setWakeWordId(id: String) {
        settings.setSelectedWakeWordId(id)
        _state.value = _state.value.copy(wakeWordId = id)
    }

    fun setCustomWakeWord(phrase: String) {
        settings.setCustomWakeWord(phrase)
        _state.value = _state.value.copy(customWakeWord = phrase)
    }

    fun setPicovoiceAccessKey(key: String) {
        settings.setPicovoiceAccessKey(key)
        _state.value = _state.value.copy(picovoiceAccessKey = key)
    }

    /**
     * Backward compat - stare API
     */
    fun setWakeWord(phrase: String) {
        setCustomWakeWord(phrase)
    }

    fun setCaptureCount(count: Int) {
        val clamped = count.coerceIn(1, 20)
        settings.setCaptureCount(clamped)
        _state.value = _state.value.copy(captureCount = clamped)
    }

    fun setCaptureInterval(ms: Long) {
        val clamped = ms.coerceIn(500, 10000)
        settings.setCaptureIntervalMs(clamped)
        _state.value = _state.value.copy(captureIntervalMs = clamped)
    }

    /**
     * Testuje połączenie z aktywnym providerem - wysyła "hello" do AI.
     */
    fun testConnection() {
        val providerId = _state.value.activeProviderId
        val apiKey = _state.value.apiKeys[providerId]

        if (apiKey.isNullOrBlank()) {
            _state.value = _state.value.copy(
                statusMessage = "Brak klucza API dla aktywnego providera. Wpisz go powyżej."
            )
            return
        }

        _state.value = _state.value.copy(isTestRunning = true, statusMessage = "Testuję...")

        viewModelScope.launch {
            try {
                val provider = withContext(Dispatchers.IO) {
                    AIProviderFactory.createSimple(
                        providerId = providerId,
                        apiKey = apiKey,
                        preferredModelId = settings.getSelectedModel(providerId)
                    )
                }
                val response = provider.analyze(
                    textQuestion = "Odpowiedz krótko: dostałeś tę wiadomość?",
                    images = emptyList(),
                    audioBytes = null,
                    scannedCodes = emptyList(),
                    enableWebSearch = false
                )
                _state.value = _state.value.copy(
                    isTestRunning = false,
                    statusMessage = "✓ Sukces! ${response.providerId} odpowiedział: \"${response.text.take(80)}...\""
                )
            } catch (e: AIProviderException) {
                Log.e("SettingsVM", "Test failed", e)
                _state.value = _state.value.copy(
                    isTestRunning = false,
                    statusMessage = "✗ Błąd: ${e.message}"
                )
            } catch (e: Exception) {
                Log.e("SettingsVM", "Test failed", e)
                _state.value = _state.value.copy(
                    isTestRunning = false,
                    statusMessage = "✗ Nieoczekiwany błąd: ${e.message}"
                )
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }

}

/**
 * Stan ekranu ustawień.
 */
data class SettingsState(
    val activeProviderId: String,
    val selectedModelId: String? = null,
    val apiKeys: Map<String, String>,
    val webSearchEnabled: Boolean,
    val responseLanguage: String,
    val wakeWordEnabled: Boolean,
    // Wartość początkowa stanu przed pierwszym odczytem preferencji.
    // Musi wskazywać komendę, która istnieje w katalogu.
    val wakeWordId: String = "jarvis",
    val customWakeWord: String = "",
    val customKeywordPath: String = "",
    val customModelPath: String = "",
    val picovoiceAccessKey: String = "",
    val captureCount: Int,
    val captureIntervalMs: Long,
    // TTS
    val ttsVoice: String? = null,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val availableVoices: List<pl.jarvis.app.audio.VoiceInfo> = emptyList(),
    val currentVoice: pl.jarvis.app.audio.VoiceInfo? = null,
    // Persona
    val selectedPersonaId: String = "default",
    val customPersonaPrompt: String = "",
    val statusMessage: String? = null,
    val isTestRunning: Boolean = false
)
