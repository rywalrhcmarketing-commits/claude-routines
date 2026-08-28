package pl.jarvis.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.jarvis.app.JarvisApplication
import pl.jarvis.app.ai.AIProviderFactory

/**
 * ViewModel onboardingu - zarządza stanem i zapisuje do SettingsRepository.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApplication

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private fun loadInitialState(): OnboardingState {
        val s = app.settings
        return OnboardingState(
            providerId = s.getActiveProvider(),
            apiKey = s.getApiKey(s.getActiveProvider()) ?: "",
            openWeatherKey = s.getOpenWeatherApiKey(),
            picovoiceKey = s.getPicovoiceAccessKey(),
            city = s.getWeatherLocation()
        )
    }

    fun nextStep() {
        val current = _state.value
        if (current.currentStep < current.totalSteps - 1) {
            _state.value = current.copy(currentStep = current.currentStep + 1)
        } else {
            finish()
        }
    }

    fun previousStep() {
        val current = _state.value
        if (current.currentStep > 0) {
            _state.value = current.copy(currentStep = current.currentStep - 1)
        }
    }

    fun skipStep() {
        // Dla opcjonalnych kroków
        val current = _state.value
        val step = OnboardingStep.values().getOrNull(current.currentStep)
        if (step == OnboardingStep.WEATHER ||
            step == OnboardingStep.PICOVOICE ||
            step == OnboardingStep.LOCATION ||
            step == OnboardingStep.GLASSES) {
            nextStep()
        }
    }

    fun setProvider(providerId: String) {
        _state.value = _state.value.copy(providerId = providerId)
    }

    fun setApiKey(key: String) {
        _state.value = _state.value.copy(apiKey = key)
    }

    fun setOpenWeatherKey(key: String) {
        _state.value = _state.value.copy(openWeatherKey = key)
    }

    fun setPicovoiceKey(key: String) {
        _state.value = _state.value.copy(picovoiceKey = key)
    }

    fun setCity(city: String) {
        _state.value = _state.value.copy(city = city)
    }

    fun setCalendarPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasCalendarPermission = granted)
    }

    fun setNotificationPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasNotificationPermission = granted)
    }

    fun setBluetoothPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasBluetoothPermission = granted)
    }

    fun setLocationPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasLocationPermission = granted)
    }

    fun setGlassesPaired(paired: Boolean) {
        _state.value = _state.value.copy(glassesPaired = paired)
    }

    /**
     * Zapisuje wszystko do SettingsRepository.
     */
    fun saveAndFinish() {
        val s = _state.value
        val settings = app.settings

        // Provider
        settings.setActiveProvider(s.providerId)
        if (s.apiKey.isNotBlank()) {
            settings.setApiKey(s.providerId, s.apiKey)
        }

        // OpenWeatherMap
        if (s.openWeatherKey.isNotBlank()) {
            settings.setOpenWeatherApiKey(s.openWeatherKey)
        }
        if (s.city.isNotBlank()) {
            settings.setWeatherLocation(s.city)
        }

        // Picovoice
        if (s.picovoiceKey.isNotBlank()) {
            settings.setPicovoiceAccessKey(s.picovoiceKey)
        }

        // Wake word - włącz jeśli jest klucz
        if (s.picovoiceKey.isNotBlank()) {
            settings.setWakeWordEnabled(true)
        }

        settings.setOnboardingCompleted(true)
        _state.value = s.copy(finished = true)
    }

    fun finish() {
        saveAndFinish()
    }

    /**
     * Sprawdza czy dany krok jest gotowy (ma wymagane dane).
     * Używane przez UI do enable/disable przycisku "Dalej".
     */
    fun isStepReady(step: OnboardingStep): Boolean {
        val s = _state.value
        return when (step) {
            OnboardingStep.WELCOME -> true
            OnboardingStep.PROVIDER -> s.providerId.isNotBlank()
            OnboardingStep.API_KEY -> s.apiKey.isNotBlank() && s.apiKey.length > 5
            OnboardingStep.WEATHER -> true  // opcjonalne
            OnboardingStep.PICOVOICE -> true  // opcjonalne
            OnboardingStep.LOCATION -> true   // opcjonalne
            OnboardingStep.PERMISSIONS -> true  // nawet bez uprawnień idź dalej
            OnboardingStep.GLASSES -> true    // opcjonalne
            OnboardingStep.DONE -> true
        }
    }
}
