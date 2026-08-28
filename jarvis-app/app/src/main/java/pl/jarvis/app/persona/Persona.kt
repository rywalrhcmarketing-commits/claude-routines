package pl.jarvis.app.persona

/**
 * Persona = styl komunikacji AI.
 *
 * Zawiera system prompt który ustala:
 * - Jak AI się zwraca (per Ty / per Pan)
 * - Czy wykonuje polecenia czy tylko odpowiada
 * - Czy dodaje coś od siebie (suchary, komentarze)
 * - Jak długie odpowiedzi
 * - Jaki ton (oficjalny / luźny / sarkastyczny)
 */
data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val systemPrompt: String,
    val isCustom: Boolean = false
) {
    /** Maksymalna sugerowana długość odpowiedzi (w znakach) - hint dla AI */
    val suggestedResponseLength: Int = when {
        systemPrompt.contains("krótk") || systemPrompt.contains("zwięźl") || systemPrompt.contains("maks") -> 200
        systemPrompt.contains("rozbudowan") || systemPrompt.contains("szczegół") -> 800
        else -> 400
    }
}
