package pl.victor.app.data

/**
 * Informacje o modelu AI.
 *
 * @param id identyfikator techniczny (np. "gemini-2.5-flash")
 * @param displayName nazwa wyświetlana użytkownikowi
 * @param providerId provider ("gemini", "openai", "claude", "minimax")
 * @param releaseDate przybliżona data wydania (YYYY-MM) - do wyświetlania
 * @param contextWindow ile tokenów kontekstu obsługuje
 * @param supportsVision czy obsługuje obrazy
 * @param supportsAudio czy obsługuje audio IN
 * @param supportsWebSearch czy ma wbudowany web search
 * @param deprecated czy model jest wycofywany
 * @param replacementId id modelu który go zastępuje (jeśli deprecated)
 * @param deprecationDate kiedy zostanie wycofany (opcjonalne)
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val providerId: String,
    val releaseDate: String? = null,
    val contextWindow: Int? = null,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsWebSearch: Boolean = false,
    val deprecated: Boolean = false,
    val replacementId: String? = null,
    val deprecationDate: String? = null,
    val description: String = ""
) {
    val isUsable: Boolean get() = !deprecated
    val needsUpdate: Boolean get() = deprecated && replacementId != null
}
