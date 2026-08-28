package pl.jarvis.app.conversation

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
 * - Auto-trim starszych
 */
class ConversationContext(
    private val maxTurns: Int = 10,
    private val maxTokens: Int = 4000
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
    fun size(): Int = synchronized(_lock) { _turns.size }

    /**
     * Zwraca wymiany jako tekst do wstrzyknięcia do promptu.
     * Format: "Użytkownik: ...\nJarvis: ...\n..."
     */
    fun asContextString(): String {
        val turns = synchronized(_lock) { _turns.toList() }
        if (turns.isEmpty()) return ""

        return turns.joinToString("\n\n") { turn ->
            val photosNote = if (turn.photos > 0) " [z ${turn.photos} zdjęciami]" else ""
            "Użytkownik$photosNote: ${turn.question}\nJarvis: ${turn.answer}"
        }
    }

    /**
     * Szacuje ile tokenów zajmuje kontekst.
     */
    fun estimatedTokens(): Int = synchronized(_lock) {
        _turns.sumOf { it.estimateTokens() }
    }

    /**
     * Trim - usuwa najstarsze wymiany gdy przekroczone limity.
     */
    private fun trim() {
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
        _turns.lastOrNull()
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
