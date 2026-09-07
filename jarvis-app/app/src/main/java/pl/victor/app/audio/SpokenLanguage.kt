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
            for (i in start..end) english[i] = true
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
    private val FUNCTION_WORDS = setOf(
        "the", "and", "of", "is", "are", "was", "were", "be", "been", "to", "in",
        "on", "at", "by", "for", "from", "with", "as", "it", "its", "this", "that",
        "these", "those", "you", "your", "we", "our", "they", "their", "he", "she",
        "his", "her", "have", "has", "had", "will", "would", "can", "could",
        "should", "shall", "may", "might", "must", "not", "but", "or", "if",
        "how", "what", "why", "when", "where", "who", "which", "all", "any",
        "some", "more", "most", "new", "best", "good", "great", "very", "just",
        "now", "here", "there", "about", "into", "over", "under", "after",
        "before", "than", "then", "also", "only", "out", "up", "down", "off"
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
        Regex("ing$"),
        Regex("tion$"),
        Regex("ness$"),
        Regex("ment$"),
        Regex("able$"),
        Regex("'s$"),
        Regex("ck$"),
        Regex("ee"),
        Regex("oo"),
        Regex("^qu")
    )
}
