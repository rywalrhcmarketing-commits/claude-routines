package pl.victor.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repozytorium ustawień - klucze API, preferencje użytkownika.
 *
 * Wszystkie klucze API są szyfrowane przez EncryptedSharedPreferences (AES-256-GCM).
 * NIGDY nie loguj kluczy, NIGDY nie wysyłaj ich do analityki.
 */
class SettingsRepository(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "victor_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // === Provider AI ===

    fun getActiveProvider(): String =
        prefs.getString(KEY_ACTIVE_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER

    fun setActiveProvider(providerId: String) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, providerId).apply()
    }

    /**
     * Wybrany model dla danego providera (null = użyj domyślnego).
     */
    fun getSelectedModel(providerId: String): String? =
        prefs.getString("$KEY_MODEL_PREFIX$providerId", null)

    fun setSelectedModel(providerId: String, modelId: String) {
        prefs.edit().putString("$KEY_MODEL_PREFIX$providerId", modelId).apply()
    }

    fun clearSelectedModel(providerId: String) {
        prefs.edit().remove("$KEY_MODEL_PREFIX$providerId").apply()
    }

    /**
     * Model lokalny nie ma klucza API - reszta apki (fallback, ekran testu
     * połączenia) jest napisana pod założenie "pusty klucz = provider
     * niedostępny", więc zwracamy stały placeholder zamiast rozsiewać
     * specjalne przypadki po całym kodzie. Prawdziwym warunkiem gotowości
     * jest pobrany plik modelu - patrz [hasApiKey].
     */
    fun getApiKey(providerId: String): String? =
        if (providerId == LOCAL_PROVIDER_ID) LOCAL_PROVIDER_PLACEHOLDER_KEY
        else prefs.getString("$KEY_API_PREFIX$providerId", null)

    fun setApiKey(providerId: String, key: String) {
        require(key.isNotBlank()) { "API key cannot be blank" }
        prefs.edit().putString("$KEY_API_PREFIX$providerId", key).apply()
    }

    fun hasApiKey(providerId: String): Boolean =
        if (providerId == LOCAL_PROVIDER_ID) {
            pl.victor.app.localmodel.LocalModelStorage.isDownloaded(context, pl.victor.app.localmodel.LocalModelCatalog.QWEN_0_8B)
        } else {
            !getApiKey(providerId).isNullOrBlank()
        }

    /**
     * Kiedy ostatnio sprawdzono modele u providera (ms since epoch).
     */
    fun getLastModelValidation(providerId: String): Long =
        prefs.getLong("$KEY_VALIDATION_PREFIX$providerId", 0L)

    fun setLastModelValidation(providerId: String, timestamp: Long) {
        prefs.edit().putLong("$KEY_VALIDATION_PREFIX$providerId", timestamp).apply()
    }

    // === Funkcje AI ===

    fun isWebSearchEnabled(): Boolean =
        prefs.getBoolean(KEY_WEB_SEARCH, true)  // domyślnie włączone

    fun setWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_SEARCH, enabled).apply()
    }

    fun getResponseLanguage(): String =
        prefs.getString(KEY_RESPONSE_LANG, "pl") ?: "pl"

    fun setResponseLanguage(lang: String) {
        prefs.edit().putString(KEY_RESPONSE_LANG, lang).apply()
    }

    // === Wake word (v1.1) ===

    /**
     * Reaktywne odbicie [KEY_WAKE_WORD_ENABLED]. [VictorApplication] nasłuchuje tego
     * flow razem ze stanem połączenia BLE, żeby wiedzieć, kiedy uruchomić/zatrzymać
     * [pl.victor.app.ble.VictorForegroundService] - bez tego trzeba by pamiętać o
     * wywołaniu usługi z każdego miejsca, które przełącza wake word (onboarding,
     * ustawienia, automatyczny PowerManager), co łatwo pominąć.
     */
    private val _wakeWordEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
    )
    val wakeWordEnabledFlow: StateFlow<Boolean> = _wakeWordEnabledFlow.asStateFlow()

    fun isWakeWordEnabled(): Boolean = _wakeWordEnabledFlow.value

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
        _wakeWordEnabledFlow.value = enabled
    }

    // === Conversational mode (v1.2) ===

    fun isConversationalModeEnabled(): Boolean =
        prefs.getBoolean(KEY_CONVERSATIONAL_MODE, false)

    fun setConversationalModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONVERSATIONAL_MODE, enabled).apply()
    }

    // === Long-term memory (v1.2) ===

    fun isLongTermMemoryEnabled(): Boolean =
        prefs.getBoolean(KEY_LONG_TERM_MEMORY, true)  // domyślnie ON

    fun setLongTermMemoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LONG_TERM_MEMORY, enabled).apply()
    }

    // === Dostępność ===

    /** Wysoki kontrast - czerń/biel zamiast dynamic color. */
    fun isHighContrastEnabled(): Boolean =
        prefs.getBoolean(KEY_HIGH_CONTRAST, false)

    fun setHighContrastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }

    /** Powiększony tekst w całym interfejsie. */
    fun isLargeTextEnabled(): Boolean =
        prefs.getBoolean(KEY_LARGE_TEXT, false)

    fun setLargeTextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LARGE_TEXT, enabled).apply()
    }

    // === Translation (v1.2) ===

    fun getTranslationTarget(): String =
        prefs.getString(KEY_TRANSLATION_TARGET, "en") ?: "en"  // domyślnie angielski

    fun setTranslationTarget(lang: String) {
        prefs.edit().putString(KEY_TRANSLATION_TARGET, lang).apply()
    }

    // === Konto Google - Calendar + Gmail, jedno logowanie (v1.2, rozszerzone) ===

    /** Nazwa klucza zostaje z czasów gdy dotyczyła tylko kalendarza - flaga już nie. */
    fun isGoogleAccountConnected(): Boolean =
        prefs.getBoolean(KEY_GCAL_CONNECTED, false)

    fun setGoogleAccountConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_GCAL_CONNECTED, connected).apply()
    }

    // === Capture mode (v1.3) ===

    /**
     * Preferowany tryb capture (BURST_PHOTO / HIGH_QUALITY_SINGLE / VIDEO_SHORT itd).
     * Domyślnie: BURST_PHOTO
     */
    fun getPreferredCaptureMode(): String =
        prefs.getString(KEY_CAPTURE_MODE, pl.victor.app.ai.CaptureMode.BURST_PHOTO.name)
            ?: pl.victor.app.ai.CaptureMode.BURST_PHOTO.name

    fun setPreferredCaptureMode(mode: String) {
        prefs.edit().putString(KEY_CAPTURE_MODE, mode).apply()
    }

    /**
     * Czy auto-degradacja z wideo na zdjęcia jest włączona.
     */
    fun isAutoDegradeCaptureEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_DEGRADE_CAPTURE, true)

    fun setAutoDegradeCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DEGRADE_CAPTURE, enabled).apply()
    }

    // === Power management (v1.4) ===

    fun getPowerMode(): String = prefs.getString(KEY_POWER_MODE, "NORMAL") ?: "NORMAL"
    fun setPowerMode(mode: String) {
        prefs.edit().putString(KEY_POWER_MODE, mode).apply()
    }

    fun isAutoPowerModeEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_POWER, true)
    fun setAutoPowerModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_POWER, enabled).apply()
    }

    /**
     * Czy próbować kolejnego providera AI, gdy aktywny zawiedzie zanim padnie
     * pierwszy fragment odpowiedzi (patrz [pl.victor.app.AIOrchestrator]). Domyślnie
     * włączone - próbuje tylko providerów, dla których user już wpisał klucz API,
     * więc nic nowego nie wysyła się donikąd.
     */
    fun isAutoProviderFallbackEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_PROVIDER_FALLBACK, true)
    fun setAutoProviderFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PROVIDER_FALLBACK, enabled).apply()
    }

    fun setProactiveIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_PROACTIVE_INTERVAL, minutes).apply()
    }
    fun getProactiveIntervalMinutes(): Int = prefs.getInt(KEY_PROACTIVE_INTERVAL, 15)

    fun setHistoryLimit(limit: Int) {
        prefs.edit().putInt(KEY_HISTORY_LIMIT, limit).apply()
    }
    fun getHistoryLimit(): Int = prefs.getInt(KEY_HISTORY_LIMIT, 20)

    /**
     * Tryb symulowanych okularów - pozwala przejść całą ścieżkę aplikacji
     * bez sprzętu. Domyślnie wyłączony.
     */
    fun isGlassesSimulationEnabled(): Boolean =
        prefs.getBoolean(KEY_GLASSES_SIMULATION, false)

    fun setGlassesSimulationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLASSES_SIMULATION, enabled).apply()
    }

    /**
     * ID wybranej komendy głosowej. Domyślnie "computer" - działa od razu,
     * bez wgrywania własnego modelu. "Hey Victor" (id `hey_victor`) to fraza
     * docelowa, ale wymaga wytrenowania - patrz [WakeWordRegistry].
     *
     * Wcześniejsze wersje zapisywały tu identyfikatory fraz, których Porcupine
     * nie obsługuje (np. "jarvis_start", "hej_cyan"). Takie zapisy nie istnieją
     * już w katalogu, więc [getSelectedWakeWordEntry] schodzi wtedy na domyślną.
     */
    fun getSelectedWakeWordId(): String =
        prefs.getString(KEY_WAKE_WORD, WakeWordRegistry.default().id)
            ?: WakeWordRegistry.default().id

    fun setSelectedWakeWordId(id: String) {
        prefs.edit().putString(KEY_WAKE_WORD, id).apply()
    }

/**
     * Wybrana komenda jako wpis katalogu - z niego wiadomo nie tylko jaka fraza,
     * ale też czy Porcupine ją zna, czy potrzebny jest własny model.
     */
    fun getSelectedWakeWordEntry(): WakeWord {
        val id = getSelectedWakeWordId()
        return WakeWordRegistry.findById(id) ?: WakeWordRegistry.default()
    }

    /**
     * Pełna fraza komendy (rozwiązana z ID + custom jeśli potrzeba).
     * Do wyświetlania; do inicjalizacji detektora służy [getSelectedWakeWordEntry].
     */
    fun getSelectedWakeWord(): String {
        val entry = getSelectedWakeWordEntry()
        return if (entry.id == "custom") getCustomWakeWord() else entry.phrase
    }

    /**
     * Ścieżka do własnego pliku `.ppn` z konsoli Picovoice.
     * Bez niego fraza spoza wbudowanej listy nie zadziała.
     */
    fun getCustomKeywordPath(): String = prefs.getString(KEY_KEYWORD_PATH, "") ?: ""

    fun setCustomKeywordPath(path: String) {
        prefs.edit().putString(KEY_KEYWORD_PATH, path).apply()
    }

    /**
     * Ścieżka do modelu językowego `.pv` - potrzebna tylko dla fraz
     * w językach innych niż angielski (np. polskich).
     */
    fun getCustomModelPath(): String = prefs.getString(KEY_MODEL_PATH, "") ?: ""

    fun setCustomModelPath(path: String) {
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
    }

    fun setSelectedWakeWord(phrase: String) {
        // Stara metoda - zapisuje jako custom
        // Backward compatibility
        setCustomWakeWord(phrase)
    }

    /**
     * Własna komenda (dla "custom").
     */
    fun getCustomWakeWord(): String =
        prefs.getString(KEY_CUSTOM_WAKE_WORD, "") ?: ""

    fun setCustomWakeWord(phrase: String) {
        prefs.edit().putString(KEY_CUSTOM_WAKE_WORD, phrase).apply()
    }

    // === Picovoice (wake word) ===

    /**
     * Picovoice AccessKey z konsoli https://console.picovoice.ai/
     * Darmowy tier: 3 wake words / urządzenie.
     */
    fun getPicovoiceAccessKey(): String =
        prefs.getString(KEY_PICOVOICE_KEY, "") ?: ""

    fun setPicovoiceAccessKey(key: String) {
        prefs.edit().putString(KEY_PICOVOICE_KEY, key).apply()
    }

    // === Action mode ===

    /**
     * Tryb wykonywania akcji: SAFE (Intent) lub DIRECT (bezpośrednio).
     */
    fun getActionMode(): String =
        prefs.getString(KEY_ACTION_MODE, "SAFE") ?: "SAFE"

    fun setActionMode(mode: String) {
        prefs.edit().putString(KEY_ACTION_MODE, mode).apply()
    }

    // === Proactive alerts (pogoda + kalendarz) ===

    /**
     * Czy proaktywne alerty są włączone.
     */
    fun isProactiveAlertsEnabled(): Boolean =
        prefs.getBoolean(KEY_PROACTIVE_ENABLED, true)  // domyślnie włączone

    fun setProactiveAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, enabled).apply()
    }

    /**
     * OpenWeatherMap API key (darmowy z https://openweathermap.org/api).
     */
    fun getOpenWeatherApiKey(): String =
        prefs.getString(KEY_OWM_KEY, "") ?: ""

    fun setOpenWeatherApiKey(key: String) {
        prefs.edit().putString(KEY_OWM_KEY, key).apply()
    }

    /**
     * Lokalizacja dla pogody - "Warszawa,PL" / "Kraków" / "52.23,21.01"
     */
    fun getWeatherLocation(): String =
        prefs.getString(KEY_WEATHER_LOCATION, "") ?: ""

    fun setWeatherLocation(location: String) {
        prefs.edit().putString(KEY_WEATHER_LOCATION, location).apply()
    }

    /**
     * Cache "alert już wysłany" - żeby nie spamować.
     * Klucz: "{type}-{eventId}-{beginMs/30min}"
     */
    fun isAlertAlreadyShown(key: String): Boolean =
        prefs.getBoolean("${KEY_ALERT_SHOWN_PREFIX}$key", false)

    fun markAlertShown(key: String) {
        prefs.edit().putBoolean("${KEY_ALERT_SHOWN_PREFIX}$key", true).apply()
    }

    /**
     * Czyści cache alertów (np. po restarcie apki).
     */
    fun clearAlertCache() {
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_ALERT_SHOWN_PREFIX) }
        val editor = prefs.edit()
        allKeys.forEach { editor.remove(it) }
        editor.apply()
    }

    // === Onboarding ===

    /**
     * Czy onboarding został ukończony. False = pokaż onboarding przy starcie.
     */
    fun isOnboardingCompleted(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, completed).apply()
    }

    /**
     * Reset onboardingu - przyda się do testów.
     */
    fun resetOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, false).apply()
    }

    // === Capture ===

    fun getCaptureCount(): Int =
        prefs.getInt(KEY_CAPTURE_COUNT, DEFAULT_CAPTURE_COUNT)

    fun setCaptureCount(count: Int) {
        prefs.edit().putInt(KEY_CAPTURE_COUNT, count).apply()
    }

    fun getCaptureIntervalMs(): Long =
        prefs.getLong(KEY_CAPTURE_INTERVAL, DEFAULT_CAPTURE_INTERVAL_MS)

    fun setCaptureIntervalMs(ms: Long) {
        prefs.edit().putLong(KEY_CAPTURE_INTERVAL, ms).apply()
    }

    // === TTS Voice ===

    fun getTtsVoiceName(): String? =
        prefs.getString(KEY_TTS_VOICE, null)

    fun setTtsVoiceName(name: String?) {
        prefs.edit().putString(KEY_TTS_VOICE, name).apply()
    }

    fun getTtsSpeechRate(): Float =
        prefs.getFloat(KEY_TTS_RATE, 1.0f)

    fun setTtsSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_TTS_RATE, rate.coerceIn(0.5f, 2.0f)).apply()
    }

    fun getTtsPitch(): Float =
        prefs.getFloat(KEY_TTS_PITCH, 1.0f)

    fun setTtsPitch(pitch: Float) {
        prefs.edit().putFloat(KEY_TTS_PITCH, pitch.coerceIn(0.5f, 2.0f)).apply()
    }

    // === Persona ===

    /**
     * ID wybranej persony ("default", "sternik", "przyjaciel", ..., "custom").
     */
    fun getSelectedPersonaId(): String =
        prefs.getString(KEY_PERSONA_ID, "default") ?: "default"

    fun setSelectedPersonaId(id: String) {
        prefs.edit().putString(KEY_PERSONA_ID, id).apply()
    }

    /**
     * Własny system prompt (dla persony "custom").
     * Pusty = brak własnego, użyj domyślnej persony.
     */
    fun getCustomPersonaPrompt(): String =
        prefs.getString(KEY_CUSTOM_PERSONA, "") ?: ""

    fun setCustomPersonaPrompt(prompt: String) {
        prefs.edit().putString(KEY_CUSTOM_PERSONA, prompt).apply()
    }

    companion object {
        private const val DEFAULT_PROVIDER = "gemini"
        private const val LOCAL_PROVIDER_ID = "local"
        private const val LOCAL_PROVIDER_PLACEHOLDER_KEY = "local-model-no-key-needed"
        const val DEFAULT_CAPTURE_COUNT = 5
        const val DEFAULT_CAPTURE_INTERVAL_MS = 1000L

        private const val KEY_ACTIVE_PROVIDER = "active_provider"
        private const val KEY_API_PREFIX = "api_key_"
        private const val KEY_MODEL_PREFIX = "selected_model_"
        private const val KEY_VALIDATION_PREFIX = "last_validation_"
        private const val KEY_WEB_SEARCH = "web_search_enabled"
        private const val KEY_RESPONSE_LANG = "response_lang"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_CONVERSATIONAL_MODE = "conversational_mode"
        private const val KEY_LONG_TERM_MEMORY = "long_term_memory"
        private const val KEY_TRANSLATION_TARGET = "translation_target"
        private const val KEY_GCAL_CONNECTED = "gcal_connected"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_AUTO_DEGRADE_CAPTURE = "auto_degrade_capture"
        private const val KEY_POWER_MODE = "power_mode"
        private const val KEY_AUTO_POWER = "auto_power_mode"
        private const val KEY_AUTO_PROVIDER_FALLBACK = "auto_provider_fallback"
        private const val KEY_PROACTIVE_INTERVAL = "proactive_interval_min"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
        private const val KEY_PICOVOICE_KEY = "picovoice_access_key"
        private const val KEY_ACTION_MODE = "action_mode"
        private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        private const val KEY_OWM_KEY = "owm_api_key"
        private const val KEY_WEATHER_LOCATION = "weather_location"
        private const val KEY_ALERT_SHOWN_PREFIX = "alert_shown_"
        private const val KEY_ONBOARDING_DONE = "onboarding_completed"
        private const val KEY_CAPTURE_COUNT = "capture_count"
        private const val KEY_CAPTURE_INTERVAL = "capture_interval_ms"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_LARGE_TEXT = "large_text"
        private const val KEY_PERSONA_ID = "persona_id"
        private const val KEY_CUSTOM_PERSONA = "custom_persona"
        private const val KEY_GLASSES_SIMULATION = "glasses_simulation"
        private const val KEY_KEYWORD_PATH = "wake_word_keyword_path"
        private const val KEY_MODEL_PATH = "wake_word_model_path"
    }
}
