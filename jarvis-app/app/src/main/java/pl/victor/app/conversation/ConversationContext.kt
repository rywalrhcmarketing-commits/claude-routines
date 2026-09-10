package pl.victor.app.conversation

/**
 * Pojedyncza wymiana w konwersacji (pytanie + odpowiedź).
 */
data class ConversationTurn(
    val question: String,
    val answer: String,
    val timestamp: Long = System.currentTimeMillis(),
    val photos: Int = 0,
    val tokensUsed: Int = 0
) {
    /** Ile znaków zajmuje w kontekście (do limitu) */
    fun estimateTokens(): Int = (question.length + answer.length) / 4
}

/**
 * Kontekst rozmowy - ostatnie N wymian w pamięci (nie persystentnie).
 *
 * Limity:
 * - Max 10 wymian (~20 wiadomości)
 * - Max 4000 tokenów (zależy od modelu)
 * - Wymiany starsze niż [staleAfterMs] wypadają same
 * - Auto-trim starszych
 *
 * ## Dlaczego wymiany muszą się starzeć
 * Bez tego kontekst trzymał dziesięć ostatnich wymian DOWOLNIE DŁUGO - aż do
 * restartu aplikacji albo komendy "nowy temat". Pytanie zadane rano wracało
 * więc do modelu wieczorem, opisane jako "poprzednia rozmowa", i przy
 * niedosłyszanym albo urwanym pytaniu model odpowiadał właśnie na nie.
 * Zgłoszone wprost: "czasem odpowiada na pytanie zadane dużo wcześniej".
 *
 * Rozmowa to coś, co się dzieje TERAZ. Po kwadransie ciszy nie ma już czego
 * kontynuować, a stary kontekst tylko myli - i to w sposób, którego z zewnątrz
 * nie da się rozpoznać, bo odpowiedź brzmi sensownie, tylko dotyczy czegoś
 * innego.
 *
 * @param staleAfterMs po ilu milisekundach BEZCZYNNOŚCI wymiana przestaje być
 *   kontekstem. Liczy się wiek wymiany, nie odstęp między nimi: rozmowa
 *   prowadzona bez przerw naturalnie zostaje w całości.
 * @param clock źródło czasu - wstrzykiwane, żeby dało się to sprawdzić testem
 */
class ConversationContext(
    private val maxTurns: Int = 10,
    private val maxTokens: Int = 4000,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _turns = mutableListOf<ConversationTurn>()
    private val _lock = Any()

    /**
     * Dodaje nową wymianę (pytanie + odpowiedź).
     */
    fun addTurn(question: String, answer: String, photos: Int = 0, tokens: Int = 0) {
        synchronized(_lock) {
            _turns.add(ConversationTurn(
                question = question,
                answer = answer,
                timestamp = clock(),
                photos = photos,
                tokensUsed = tokens
            ))
            trim()
        }
    }

    /**
     * Czyści kontekst (np. nowa rozmowa).
     */
    fun clear() {
        synchronized(_lock) {
            _turns.clear()
        }
    }

    /**
     * Ile jest wymian.
     */
    fun size(): Int = synchronized(_lock) { dropStale(); _turns.size }

    /**
     * Zwraca wymiany jako tekst do wstrzyknięcia do promptu.
     * Format: "Użytkownik: ...\nV.I.C.T.O.R.: ...\n..."
     */
    fun asContextString(): String {
        val turns = synchronized(_lock) { dropStale(); _turns.toList() }
        if (turns.isEmpty()) return ""

        return turns.joinToString("\n\n") { turn ->
            val photosNote = if (turn.photos > 0) " [z ${turn.photos} zdjęciami]" else ""
            "Użytkownik$photosNote: ${turn.question}\nV.I.C.T.O.R.: ${turn.answer}"
        }
    }

    /**
     * Szacuje ile tokenów zajmuje kontekst.
     */
    fun estimatedTokens(): Int = synchronized(_lock) {
        _turns.sumOf { it.estimateTokens() }
    }

    /**
     * Wyrzuca wymiany, które przestały być rozmową.
     *
     * Wołane przy KAŻDYM odczycie, nie w tle: kontekst nie ma własnego wątku,
     * a jedyny moment, w którym jego wiek ma znaczenie, to chwila budowania
     * promptu. Zawsze pod [_lock].
     */
    private fun dropStale() {
        if (staleAfterMs <= 0L) return
        val cutoff = clock() - staleAfterMs
        _turns.removeAll { it.timestamp < cutoff }
    }

    /**
     * Trim - usuwa najstarsze wymiany gdy przekroczone limity.
     */
    private fun trim() {
        // Najpierw wiek, potem liczba: przeterminowana wymiana nie ma prawa
        // zajmować miejsca świeżej.
        dropStale()

        // Limit wymian
        while (_turns.size > maxTurns) {
            _turns.removeAt(0)
        }

        // Limit tokenów
        while (estimatedTokens() > maxTokens && _turns.isNotEmpty()) {
            _turns.removeAt(0)
        }
    }

    /**
     * Ostatnia wymiana (do follow-up).
     */
    fun lastTurn(): ConversationTurn? = synchronized(_lock) {
        dropStale()
        _turns.lastOrNull()
    }

    companion object {
        /**
         * Kwadrans. Tyle, żeby przerwa na odebranie telefonu albo dojście do
         * samochodu nie kasowała rozmowy - i nie tyle, żeby pytanie sprzed
         * południa wracało po obiedzie.
         */
        const val DEFAULT_STALE_AFTER_MS = 15 * 60 * 1000L
    }

    /**
     * Kontekst jako system message - dla promptu AI.
     */
    fun asSystemContext(): String {
        val text = asContextString()
        if (text.isEmpty()) return ""
        return "Poprzednia rozmowa (kontekst):\n$text\n\n---\n"
    }
}
