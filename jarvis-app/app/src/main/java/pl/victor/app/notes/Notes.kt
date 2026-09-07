package pl.victor.app.notes

/**
 * Notatki dyktowane głosem.
 *
 * ## Po co osobny plik na rozpoznawanie frazy
 * Bo to jest jedyna część, która musi być PEWNA. Reszta (zapis, lista, ekran)
 * jest odwracalna jednym kliknięciem, a błędne rozpoznanie nie: albo notatka
 * ginie w rozmowie z modelem, albo - gorzej - zwykłe pytanie ląduje w
 * notatniku zamiast dostać odpowiedź. Dlatego dopasowanie jest tu czystą
 * funkcją z testami, a nie warunkiem schowanym w orkiestratorze.
 */
object Notes {

    /**
     * Zwroty otwierające notatkę. Liczy się PRZEDROSTEK wypowiedzi, nie
     * fragment: "zapisz, że mam oddać książkę" to notatka, ale "co zapisałeś
     * wczoraj" jest pytaniem i musi nim zostać.
     */
    private val PREFIXES = listOf(
        "zapisz że ",
        "zapisz ze ",
        "zapisz sobie że ",
        "zapisz sobie ze ",
        "zapisz ",
        "zanotuj że ",
        "zanotuj ze ",
        "zanotuj ",
        "dodaj do notatek ",
        "dodaj notatkę ",
        "dodaj notatke ",
        "nowa notatka ",
        "przypomnij mi że ",
        "przypomnij mi ze ",
        "przypomnij mi o ",
        "przypomnij mi ",
        "dodaj do listy zakupów ",
        "dodaj do listy zakupow ",
        "dopisz do listy ",
        "dodaj "
    )

    /**
     * Wyciąga treść notatki z wypowiedzi.
     *
     * @return treść albo `null`, gdy to nie jest prośba o notatkę
     */
    fun extract(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        // Kalendarz ma pierwszeństwo przed notatnikiem. "Zapisz mi spotkanie na
        // piątek" to prośba o wydarzenie, nie o notatkę - a przechwycenie jej
        // tutaj kończyłoby się notatką zamiast wpisu w kalendarzu i cichym
        // brakiem przypomnienia.
        if (CALENDAR_WORDS.any { lower.contains(it) }) return null

        val prefix = PREFIXES.firstOrNull { lower.startsWith(it) } ?: return null
        val body = trimmed.substring(prefix.length).trim().trimStart(',').trim()
        // Sam czasownik bez treści to nie notatka, tylko urwane zdanie -
        // zapisanie pustki byłoby gorsze niż przyznanie, że nie zrozumiałem.
        if (body.length < MIN_BODY) return null
        return body.replaceFirstChar { it.uppercase() }
    }

    /** Krótsza treść to najpewniej przesłyszenie, a nie notatka. */
    private const val MIN_BODY = 3

    /** Słowa, po których wypowiedź należy do kalendarza, nie do notatnika. */
    private val CALENDAR_WORDS = listOf(
        "kalendarz", "spotkanie", "spotkania", "wydarzenie", "w kalendarzu"
    )

    /** Czy wypowiedź prosi o odczytanie notatek. */
    fun isListRequest(text: String): Boolean {
        val normalized = text.lowercase().trim().trimEnd('.', '!', '?').trim()
        return normalized in LIST_PHRASES
    }

    private val LIST_PHRASES = setOf(
        "notatki",
        "moje notatki",
        "przeczytaj notatki",
        "przeczytaj moje notatki",
        "przejrzyj notatki",
        "sprawdź notatki",
        "sprawdz notatki",
        "sprawdź w notatkach",
        "sprawdz w notatkach",
        "co mam w notatkach",
        "co mam zapisane",
        "lista zakupów",
        "lista zakupow",
        "co mam do zrobienia"
    )

    /**
     * Czy wypowiedź w ogóle DOTYCZY notatek.
     *
     * Luźniejsze niż [isListRequest] i służy do czegoś innego: [isListRequest]
     * decyduje, czy odczytać notatki od razu, a to - czy doklejać je jako
     * kontekst dla modelu. Dzięki temu "czy mam coś do kupienia?" albo "co
     * miałem zrobić w piątek?" trafia do modelu RAZEM z notatkami i dostaje
     * prawdziwą odpowiedź, zamiast wyliczanki wszystkiego po kolei.
     */
    fun mentionsNotes(text: String): Boolean {
        val lower = text.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }

    private val KEYWORDS = listOf(
        // "zapisa" łapie zapisane, zapisałem, zapisał - a to jest dokładnie ta
        // forma, w której pada pytanie "co zapisałem wczoraj?".
        "notatk", "notatek", "zapisa", "zanotow",
        "lista zakup", "do zrobienia", "do kupienia"
    )

    /**
     * Notatki jako sekcja kontekstu dla modelu.
     *
     * ## Dlaczego każda notatka niesie datę
     * Bez niej "co zapisałem wczoraj?" jest pytaniem bez odpowiedzi - model
     * widzi listę zdań bez osi czasu i albo zgaduje, albo mówi, że nie wie.
     * Data idzie w dwóch postaciach naraz: dokładnej (do liczenia) i słownej
     * ("wczoraj", "dziś"), bo modele mylą się w arytmetyce kalendarzowej
     * znacznie częściej niż w czytaniu gotowej etykiety.
     *
     * @param nowMs "teraz" podawane z zewnątrz, żeby dało się to sprawdzić
     *   testem - zegar systemowy w czystej funkcji znaczy test, który psuje
     *   się o północy
     * @return sekcja albo `null`, gdy nie ma ani jednej notatki - pusta sekcja
     *   tylko zajmowałaby miejsce w poleceniu
     */
    fun buildPromptContext(notes: List<Note>, nowMs: Long = System.currentTimeMillis()): String? {
        if (notes.isEmpty()) return null
        return buildString {
            append("=== NOTATKI UŻYTKOWNIKA ===\n")
            notes.forEach { note ->
                append("- ")
                if (note.createdAtMs > 0L) {
                    append('[').append(stamp(note.createdAtMs, nowMs)).append("] ")
                }
                append(note.text).append('\n')
            }
            append("To są notatki zapisane przez użytkownika, najnowsze pierwsze. ")
            append("W nawiasie kwadratowym jest data zapisania notatki. ")
            append("Odpowiadaj na ich podstawie, gdy pyta, co ma zapisane, do zrobienia ")
            append("albo do kupienia, i korzystaj z dat, gdy pyta o konkretny dzień. ")
            append("Nie wymyślaj notatek, których tu nie ma.")
        }
    }

    /** Data notatki: dokładna do liczenia i słowna do czytania. */
    private fun stamp(createdAtMs: Long, nowMs: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(createdAtMs).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(date.toLocalDate(), today)
        val label = when (days) {
            0L -> "dziś"
            1L -> "wczoraj"
            2L -> "przedwczoraj"
            in 3L..6L -> "$days dni temu"
            else -> null
        }
        val exact = date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return if (label != null) "$exact, $label" else exact
    }

    /** Jedna notatka: treść i kiedy powstała. */
    data class Note(val text: String, val createdAtMs: Long)

    /**
     * Składa notatki w zdanie do wypowiedzenia.
     *
     * Numerowane, bo bez numerów kilka notatek pod rząd zlewa się w jedno
     * zdanie i nie da się ich rozdzielić ze słuchu.
     */
    fun speak(notes: List<Note>): String {
        if (notes.isEmpty()) return "Nie masz jeszcze żadnych notatek."
        val body = notes.take(SPOKEN_LIMIT)
            .mapIndexed { index, note -> "${index + 1}. ${note.text}" }
            .joinToString(" ")
        val header = if (notes.size == 1) "Masz jedną notatkę." else "Masz ${notes.size} notatek."
        val tail = if (notes.size > SPOKEN_LIMIT) {
            " Resztę zobaczysz w aplikacji."
        } else {
            ""
        }
        return "$header $body$tail"
    }

    /** Ile notatek czytamy na głos, zanim odeślemy do aplikacji. */
    private const val SPOKEN_LIMIT = 10
}
