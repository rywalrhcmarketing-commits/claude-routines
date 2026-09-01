package pl.victor.app.ui.onboarding

/**
 * Stan onboardingu - śledzi postęp użytkownika przez setup.
 */
data class OnboardingState(
    val currentStep: Int = 0,
    val totalSteps: Int = 9,
    val providerId: String = "gemini",
    val apiKey: String = "",
    val openWeatherKey: String = "",
    val picovoiceKey: String = "",
    val city: String = "",
    val hasCalendarPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasBatteryExemption: Boolean = false,
    val glassesPaired: Boolean = false,
    val finished: Boolean = false
) {
    /**
     * Czy dany krok jest "gotowy" - przejście dalej jest możliwe.
     */
    fun isStepReady(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.WELCOME -> true
        OnboardingStep.PROVIDER -> providerId.isNotBlank()
        OnboardingStep.API_KEY -> apiKey.isNotBlank()
        OnboardingStep.WEATHER -> true  // opcjonalne
        OnboardingStep.PICOVOICE -> true  // opcjonalne
        OnboardingStep.LOCATION -> true  // opcjonalne
        OnboardingStep.PERMISSIONS -> true  // uprawnienia można dać później
        OnboardingStep.GLASSES -> true  // opcjonalne
        OnboardingStep.DONE -> true
    }
}

/**
 * Kroki onboardingu - wyświetlane w określonej kolejności.
 */
enum class OnboardingStep(val title: String, val emoji: String) {
    WELCOME("Witaj w V.I.C.T.O.R.", "👋"),
    PROVIDER("Wybierz AI", "🧠"),
    API_KEY("Klucz API", "🔑"),
    WEATHER("Pogoda", "🌦️"),
    PICOVOICE("Wake word", "🎙️"),
    LOCATION("Lokalizacja", "📍"),
    PERMISSIONS("Uprawnienia", "🔐"),
    GLASSES("Okulary", "👓"),
    DONE("Gotowe!", "🎉")
}

/**
 * Czy onboarding został ukończony.
 */
object OnboardingPrefs {
    fun isCompleted(): Boolean = false  // sprawdzane w SettingsRepository
    fun markCompleted() { /* zapis */ }
}
