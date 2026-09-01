package pl.victor.app.data

/**
 * Katalog komend głosowych (wake words).
 *
 * ## Dlaczego to wygląda inaczej niż wcześniej
 * Poprzednia wersja oferowała 16 fraz, w tym polskie („Cześć", „Słuchaj",
 * „Asystencie") i własną. Porcupine ma **14 wbudowanych komend i wszystkie są
 * angielskie**, a nieznana nazwa schodziła po cichu na `JARVIS`. Skutek: 11 z 16
 * pozycji - łącznie z domyślną „Jarvis Start" - reagowało na coś innego, niż
 * mówiła etykieta, i nie było jak tego zauważyć poza logiem.
 *
 * Teraz każda pozycja niesie [porcupineKeyword]: nazwę wbudowanej komendy albo
 * `null`, gdy fraza wymaga własnego modelu `.ppn` z konsoli Picovoice. UI
 * pokazuje tę różnicę, a [pl.victor.app.wakeword.WakeWordDetector] odmawia
 * uruchomienia zamiast podmieniać frazę bez pytania.
 *
 * ## "Hey Victor"
 * Docelowa fraza aplikacji (V.I.C.T.O.R.) to pozycja `hey_victor` niżej -
 * i **nie jest** jedną z 14 wbudowanych komend Porcupine. Wymaga wytrenowania
 * własnego modelu `.ppn` na console.picovoice.ai (wpisz dokładnie „Hey Victor"),
 * a potem wgrania pliku w Ustawieniach → Wake word. Dopóki tego nie zrobisz,
 * [default] zostaje na komendzie, która działa od razu - świadomie nie udajemy,
 * że „Hey Victor" już nasłuchuje, skoro jeszcze nie ma czym.
 */
data class WakeWord(
    val id: String,
    val phrase: String,
    val description: String,
    val language: String,
    val emoji: String,
    /**
     * Nazwa wbudowanej komendy Porcupine albo `null`, gdy fraza wymaga
     * wytrenowanego pliku `.ppn`.
     */
    val porcupineKeyword: String? = null
) {
    /** Czy da się jej użyć bez wgrywania własnego modelu. */
    val worksOutOfTheBox: Boolean get() = porcupineKeyword != null
}

object WakeWordRegistry {

    /**
     * Wszystkie komendy wbudowane w Porcupine 3.0 (sprawdzone w `BuiltInKeyword`).
     * Poza tą listą nic nie zadziała bez własnego modelu.
     */
    val BUILT_IN_KEYWORDS: Set<String> = setOf(
        "alexa", "americano", "blueberry", "bumblebee", "computer",
        "grapefruit", "grasshopper", "hey google", "hey siri", "jarvis",
        "ok google", "picovoice", "porcupine", "terminator"
    )

    val PRESET_WAKE_WORDS: List<WakeWord> = listOf(
        // === Działają od razu (wbudowane w Porcupine, wymowa angielska) ===
        WakeWord(
            id = "computer",
            phrase = "Computer",
            description = "Domyślna, dopóki nie wgrasz „Hey Victor” niżej. Star Trek - " +
                "wyraźna i rzadko myli się z mową potoczną.",
            language = "en",
            emoji = "🖥️",
            porcupineKeyword = "computer"
        ),
        WakeWord(
            id = "jarvis",
            phrase = "Jarvis",
            description = "Wymowa angielska: „dżarwis”.",
            language = "en",
            emoji = "🦾",
            porcupineKeyword = "jarvis"
        ),
        WakeWord(
            id = "picovoice",
            phrase = "Picovoice",
            description = "Nietypowa fraza - najmniej fałszywych wykryć.",
            language = "en",
            emoji = "🎙️",
            porcupineKeyword = "picovoice"
        ),
        WakeWord(
            id = "porcupine",
            phrase = "Porcupine",
            description = "Domyślna fraza biblioteki, bardzo dobrze rozpoznawana.",
            language = "en",
            emoji = "🦔",
            porcupineKeyword = "porcupine"
        ),
        WakeWord(
            id = "terminator",
            phrase = "Terminator",
            description = "Wyraźna, trudna do przypadkowego wypowiedzenia.",
            language = "en",
            emoji = "🤖",
            porcupineKeyword = "terminator"
        ),
        WakeWord(
            id = "bumblebee",
            phrase = "Bumblebee",
            description = "Transformers. Dwie sylaby, dobra skuteczność.",
            language = "en",
            emoji = "🐝",
            porcupineKeyword = "bumblebee"
        ),
        WakeWord(
            id = "grasshopper",
            phrase = "Grasshopper",
            description = "Długa fraza - mało fałszywych wykryć.",
            language = "en",
            emoji = "🦗",
            porcupineKeyword = "grasshopper"
        ),
        WakeWord(
            id = "americano",
            phrase = "Americano",
            description = "Krótka, łatwa do wymówienia po polsku.",
            language = "en",
            emoji = "☕",
            porcupineKeyword = "americano"
        ),
        WakeWord(
            id = "blueberry",
            phrase = "Blueberry",
            description = "Trzy sylaby, stabilne rozpoznawanie.",
            language = "en",
            emoji = "🫐",
            porcupineKeyword = "blueberry"
        ),
        WakeWord(
            id = "grapefruit",
            phrase = "Grapefruit",
            description = "Rzadka w mowie potocznej.",
            language = "en",
            emoji = "🍊",
            porcupineKeyword = "grapefruit"
        ),
        WakeWord(
            id = "alexa",
            phrase = "Alexa",
            description = "Uwaga: obudzi też głośnik Amazona, jeśli stoi obok.",
            language = "en",
            emoji = "🔊",
            porcupineKeyword = "alexa"
        ),
        WakeWord(
            id = "hey_siri",
            phrase = "Hey Siri",
            description = "Uwaga: obudzi też iPhone'a w pobliżu.",
            language = "en",
            emoji = "🍎",
            porcupineKeyword = "hey siri"
        ),
        WakeWord(
            id = "ok_google",
            phrase = "OK Google",
            description = "Uwaga: obudzi też Asystenta Google w telefonie.",
            language = "en",
            emoji = "🔍",
            porcupineKeyword = "ok google"
        ),
        WakeWord(
            id = "hey_google",
            phrase = "Hey Google",
            description = "Uwaga: obudzi też Asystenta Google w telefonie.",
            language = "en",
            emoji = "🔍",
            porcupineKeyword = "hey google"
        ),

        // === Wymagają własnego modelu .ppn z konsoli Picovoice ===
        WakeWord(
            id = "hey_victor",
            phrase = "Hey Victor",
            description = "Docelowa fraza aplikacji. Wytrenuj na console.picovoice.ai " +
                "(wpisz dokładnie „Hey Victor”) i wgraj plik .ppn w Ustawieniach.",
            language = "custom",
            emoji = "🎯",
            porcupineKeyword = null
        ),
        WakeWord(
            id = "custom",
            phrase = "",
            description = "Inna własna fraza - wymaga pliku .ppn wytrenowanego " +
                "na console.picovoice.ai (dla polskiej frazy także modelu .pv).",
            language = "custom",
            emoji = "✏️",
            porcupineKeyword = null
        )
    )

    fun findById(id: String): WakeWord? = PRESET_WAKE_WORDS.find { it.id == id }

    /**
     * Domyślna komenda - musi działać bez żadnej konfiguracji poza kluczem.
     * "Hey Victor" nie może być domyślna, bo wymaga własnego modelu - patrz
     * dokumentacja klasy wyżej.
     */
    fun default(): WakeWord = PRESET_WAKE_WORDS.first { it.id == "computer" }

    /** Wszystkie (do UI). */
    fun all(): List<WakeWord> = PRESET_WAKE_WORDS

    /** Tylko te, które zadziałają bez wgrywania własnego modelu. */
    fun builtIn(): List<WakeWord> = PRESET_WAKE_WORDS.filter { it.worksOutOfTheBox }
}
