package pl.jarvis.app.data

/**
 * Katalog predefiniowanych komend głosowych (wake words / trigger phrases).
 *
 * Każda komenda ma:
 * - id (do zapisu w settings)
 * - phrase (to co user mówi)
 * - description (co robi)
 * - language (jakiego języka komenda)
 * - emoji (ikona w UI)
 */
data class WakeWord(
    val id: String,
    val phrase: String,
    val description: String,
    val language: String,
    val emoji: String
)

object WakeWordRegistry {

    /**
     * Predefiniowane komendy - obejmują klasyki (Jarvis/Computer) + polskie + neutralne.
     */
    val PRESET_WAKE_WORDS: List<WakeWord> = listOf(
        // === Główna - Jarvis (Iron Man / Marvel) ===
        WakeWord(
            id = "jarvis_start",
            phrase = "Jarvis Start",
            description = "Jarvis (Iron Man). Kultowa komenda - po polsku wymowa OK.",
            language = "pl",
            emoji = "🦾"
        ),
        WakeWord(
            id = "jarvis",
            phrase = "Jarvis",
            description = "Krótsza wersja - samo 'Jarvis'",
            language = "en",
            emoji = "🦾"
        ),

        // === Klasyki sci-fi / kultury ===
        WakeWord(
            id = "computer",
            phrase = "Computer",
            description = "Star Trek - 'Computer, ...' - uniwersalna komenda",
            language = "en",
            emoji = "🖖"
        ),
        WakeWord(
            id = "ok_glass",
            phrase = "OK Glass",
            description = "Google Glass - klasyk wearable",
            language = "en",
            emoji = "👓"
        ),

        // === Polskie naturalne ===
        WakeWord(
            id = "hej_cyan",
            phrase = "Hej Jarvis",
            description = "Polska, dedykowana dla HeyCyan (nazwa + 'hej')",
            language = "pl",
            emoji = "👋"
        ),
        WakeWord(
            id = "cześć",
            phrase = "Cześć",
            description = "Uniwersalne polskie przywitanie - naturalne i krótkie",
            language = "pl",
            emoji = "👋"
        ),
        WakeWord(
            id = "witaj",
            phrase = "Witaj",
            description = "Polskie, formalne",
            language = "pl",
            emoji = "👋"
        ),

        // === Neutralne / krótkie ===
        WakeWord(
            id = "halo",
            phrase = "Halo",
            description = "Polskie 'halo' - proste, krótkie",
            language = "pl",
            emoji = "📞"
        ),
        WakeWord(
            id = "słuchaj",
            phrase = "Słuchaj",
            description = "'Słuchaj' - rozkazujące, jasne",
            language = "pl",
            emoji = "👂"
        ),
        WakeWord(
            id = "asystencie",
            phrase = "Asystencie",
            description = "Polskie, zwraca się do AI jak do osoby",
            language = "pl",
            emoji = "🤖"
        ),

        // === Angielskie klasyki ===
        WakeWord(
            id = "hey_siri",
            phrase = "Hey Siri",
            description = "Znany wzorzec z iPhone'a (ale bez konfliktu z Siri)",
            language = "en",
            emoji = "🗣️"
        ),
        WakeWord(
            id = "ok_google",
            phrase = "OK Google",
            description = "Google Assistant - znany wzorzec",
            language = "en",
            emoji = "🗣️"
        ),
        WakeWord(
            id = "alexa",
            phrase = "Alexa",
            description = "Amazon Echo styl (uwaga: konflikt z Alexa)",
            language = "en",
            emoji = "🗣️"
        ),

        // === Cyberpunk / futurystyczne ===
        WakeWord(
            id = "neo",
            phrase = "Neo",
            description = "Matrix - 'Neo, ...' - dla fanów cyberpunk",
            language = "en",
            emoji = "🕶️"
        ),
        WakeWord(
            id = "glados",
            phrase = "GlaDOS",
            description = "Portal - 'GlaDOS' - sarkastyczny styl",
            language = "en",
            emoji = "🌀"
        ),

        // === Własna (placeholder) ===
        WakeWord(
            id = "custom",
            phrase = "",
            description = "Własna komenda - wpisz swoją",
            language = "custom",
            emoji = "✏️"
        )
    )

    fun findById(id: String): WakeWord? = PRESET_WAKE_WORDS.find { it.id == id }

    /**
     * Domyślna komenda.
     */
    fun default(): WakeWord = PRESET_WAKE_WORDS.first { it.id == "jarvis_start" }

    /**
     * Wszystkie (do UI).
     */
    fun all(): List<WakeWord> = PRESET_WAKE_WORDS
}
