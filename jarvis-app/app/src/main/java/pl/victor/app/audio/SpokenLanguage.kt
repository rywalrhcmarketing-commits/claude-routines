package pl.victor.app.audio

/**
 * Dzieli tekst na fragmenty polskie i angielskie.
 *
 * ## Po co
 * Polski głos czytający angielski tekst wymawia go tak, jak się pisze:
 * "the best" brzmi jak "te best", "management" jak "managemeent". Zgłoszone
 * wprost. Rozwiązaniem nie jest lepszy głos polski - żaden nie zna angielskiej
 * wymowy - tylko PRZEŁĄCZENIE na głos angielski na czas tego fragmentu.
 *
 * ## Dlaczego to czysta funkcja z testami
 * Bo pomyłka w obie strony jest słyszalna i denerwująca: polskie zdanie
 * przeczytane po angielsku brzmi gorzej niż angielskie przeczytane po polsku.
 * Dlatego próg jest wysoki - fragment musi mieć MOCNY sygnał angielskości, a
 * same wyrazy neutralne (bez polskich znaków) to za mało: "start", "sport",
 * "internet", "komputer" wyglądają neutralnie, a są polskimi słowami.
 */
object SpokenLanguage {

    /** Kawałek tekstu do wypowiedzenia jednym głosem. */
    data class Segment(val text: String, val english: Boolean)

    /**
     * Dzieli tekst na kolejne fragmenty.
     *
     * @return fragmenty w kolejności; przy tekście jednorodnym jeden element
     */
    fun split(text: String): List<Segment> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        // 1. Każde słowo dostaje etykietę: mocno angielskie, neutralne, polskie.
        val marks = words.map { word ->
            when {
                looksPolish(word) -> Mark.POLISH
                looksEnglish(word) -> Mark.ENGLISH
                else -> Mark.NEUTRAL
            }
        }

        // 2. Neutralne przyklejają się do sąsiadującego ciągu angielskiego -
        //    inaczej "the best of all" rozpadłoby się na trzy przełączenia
        //    głosu w środku jednej frazy.
        val english = BooleanArray(words.size)
        var index = 0
        while (index < words.size) {
            if (marks[index] != Mark.ENGLISH) {
                index++
                continue
            }
            var start = index
            while (start > 0 && marks[start - 1] == Mark.NEUTRAL) start--
            var end = index
            while (end + 1 < words.size && marks[end + 1] != Mark.POLISH) end++
            // Ciąg jest angielski dopiero przy DWÓCH mocnych sygnałach -
            // patrz [MIN_ENGLISH_SIGNALS]. Jedno zapożyczenie w polskim zdaniu
            // nie może przełączyć głosu.
            val signals = (start..end).count { marks[it] == Mark.ENGLISH }
            if (signals >= MIN_ENGLISH_SIGNALS) {
                for (i in start..end) english[i] = true
            } else {
                // Cudzysłów jest dowodem, że to wtręt, a nie zdanie - wtedy
                // wystarczy JEDEN sygnał. Zgłoszone z użycia: "jak jest
                // cudzysłów, to czyta dobrze".
                //
                // Ale w cudzysłowie bywa też POLSKI tekst - cytat wypowiedzi,
                // polski tytuł, nazwa przycisku. Dlatego liczy się cudzysłów
                // obejmujący WIĘCEJ NIŻ JEDNO słowo: znak otwierający i
                // zamykający muszą wypaść w różnych wyrazach. Pojedyncze
                // "quiz" czy "rock" w polskim zdaniu zostaje po polsku,
                // a "quiz night" przechodzi.
                //
                // I przełączamy wyłącznie to, co jest MIĘDZY cudzysłowami -
                // nie cały ciąg z przyklejonymi polskimi wyrazami obok.
                val quoteMarked = (start..end).filter { i -> words[i].any { it in QUOTES } }
                if (quoteMarked.size >= MIN_QUOTE_MARKED_WORDS) {
                    val span = quoteMarked.first()..quoteMarked.last()
                    if (span.any { marks[it] == Mark.ENGLISH }) {
                        for (i in span) english[i] = true
                    }
                }
            }
            index = end + 1
        }

        // 3. Sklejamy sąsiadów o tej samej etykiecie w jeden fragment.
        val segments = mutableListOf<Segment>()
        var bufferStart = 0
        for (i in words.indices) {
            val last = i == words.size - 1
            if (last || english[i] != english[i + 1]) {
                segments += Segment(
                    text = words.subList(bufferStart, i + 1).joinToString(" "),
                    english = english[i]
                )
                bufferStart = i + 1
            }
        }
        return segments
    }

    private enum class Mark { POLISH, ENGLISH, NEUTRAL }

    private fun core(word: String): String =
        word.lowercase().trim { !it.isLetter() && it != '\'' }

    /** Polskie znaki i zbitki, których angielski nie ma. */
    private fun looksPolish(word: String): Boolean {
        val w = core(word)
        if (w.any { it in "ąćęłńóśźż" }) return true
        return POLISH_PATTERNS.any { w.contains(it) }
    }

    private val POLISH_PATTERNS = listOf("sz", "cz", "rz", "dz", "ść", "prz", "wsz")

    /**
     * Czy słowo jest MOCNYM sygnałem angielskości.
     *
     * Albo należy do zamkniętej listy wyrazów funkcyjnych (które w polskim nie
     * istnieją), albo ma zbitkę, której polski nie używa.
     */
    fun looksEnglish(word: String): Boolean {
        val w = core(word)
        if (w.length < 2) return false
        if (w.any { it in "ąćęłńóśźż" }) return false
        if (w in FUNCTION_WORDS) return true
        return STRONG_PATTERNS.any { it.containsMatchIn(w) }
    }

    /** Wyrazy funkcyjne - w polskim nie występują, więc są jednoznaczne. */
    /**
     * Wyrazy funkcyjne angielskie, których NIE MA w polszczyźnie.
     *
     * Lista jest krótsza, niż podpowiada intuicja, i to jest celowe. Pierwsza
     * wersja zawierała "to", "on", "we", "by", "most" i "as" - a to wszystko są
     * bardzo częste POLSKIE słowa. Każde zdanie z "to" było przez to uznawane
     * za angielskie i czytane angielskim głosem. Zgłoszone jako "często próbuje
     * czytać polskie słowa i zdania po angielsku - to duży problem".
     */
    private val FUNCTION_WORDS = setOf(
        "the", "and", "of", "is", "are", "was", "were", "been", "in",
        "for", "from", "with", "its", "this", "that", "these", "those",
        "you", "your", "our", "they", "their", "she", "his", "her",
        "have", "has", "had", "will", "would", "could", "should",
        "shall", "might", "but", "if", "how", "what", "why", "when",
        "where", "who", "which", "any", "more", "new", "best", "very",
        "just", "here", "there", "about", "into", "over", "under",
        "after", "before", "than", "then", "also", "only"
    )

    /**
     * Zbitki typowo angielskie.
     *
     * Świadomie wąskie: każdy fałszywy alarm to polskie słowo przeczytane
     * angielskim głosem, czyli błąd gorszy niż ten, który naprawiamy.
     */
    private val STRONG_PATTERNS = listOf(
        Regex("th"),
        Regex("wh"),
        Regex("ough"),
        Regex("tion$"),
        Regex("ness$"),
        Regex("able$"),
        Regex("'s$"),
        Regex("ck$"),
        Regex("^qu")
    )

    /**
     * Ile MOCNYCH sygnałów musi mieć ciąg, żeby uznać go za angielski.
     *
     * Dwa, nie jeden. Pojedyncze trafienie to za mało: "dokument" kończy się na
     * "ment", "marketing" na "ing", a "moment" jest i polskie, i angielskie.
     * Przy progu jeden takie słowo w polskim zdaniu przełączało cały fragment
     * na angielski głos. Prawdziwa angielska fraza ma tych sygnałów kilka -
     * pojedyncze zapożyczenie nie ma żadnego poza sobą.
     */
    private const val MIN_ENGLISH_SIGNALS = 2

    /**
     * Ile wyrazów musi nieść znak cudzysłowu, żeby uznać go za obejmujący frazę.
     *
     * Dwa: otwierający w jednym wyrazie, zamykający w innym. Gdy oba znaki są
     * w tym samym wyrazie, cudzysłów obejmuje jedno słowo - a jedno słowo to
     * za słaby dowód, bo w polskim zdaniu cytuje się tak nazwy przycisków
     * ("quiz", "rock") i nie należy ich czytać po angielsku.
     */
    private const val MIN_QUOTE_MARKED_WORDS = 2

    /** Znaki cudzysłowu we wszystkich formach, jakie zwracają modele. */
    private const val QUOTES = "\"\u201e\u201d\u201c\u00ab\u00bb"
}
