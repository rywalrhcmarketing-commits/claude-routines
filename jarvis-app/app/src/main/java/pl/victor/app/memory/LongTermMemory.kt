package pl.victor.app.memory

import android.content.Context
import android.util.Log
import pl.victor.app.data.ConversationEntry
import pl.victor.app.data.HistoryRepository
import java.security.MessageDigest
import java.util.Locale

/**
 * Prosta pamięć długoterminowa - TF-IDF similarity (bez zewnętrznych bibliotek).
 *
 * Dla MVP - zamiast pełnych embeddings (wymagałoby ONNX/quantized model),
 * używamy TF-IDF + cosine similarity do wyszukiwania podobnych rozmów.
 *
 * W przyszłości: zamienić na sentence-transformers (ONNX) dla lepszej jakości.
 *
 * Use case:
 * - User pyta "Pamiętasz o czym rozmawialiśmy z Anią?"
 * - Apka szuka w historii podobnych rozmów
 * - Wkłada kontekst do promptu AI
 */
class LongTermMemory(
    private val context: Context,
    private val history: HistoryRepository
) {
    private val tag = "LongTermMemory"

    // Stopwords PL + EN (opcjonalne)
    private val stopwords = setOf(
        "i", "w", "na", "z", "do", "to", "że", "jest", "się", "nie",
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "of", "in", "on", "at", "by", "for", "with", "as", "from"
    )

    /**
     * Szuka podobnych rozmów w historii.
     * Wymaga listy wpisów (pobranej wcześniej przez obserwatora).
     *
     * @param query tekst zapytania
     * @param entries lista rozmów (z HistoryRepository)
     * @param limit ile wyników
     * @return lista posortowana po similarity
     */
    fun findSimilar(query: String, entries: List<ConversationEntry>, limit: Int = 5): List<MemoryMatch> {
        Log.d(tag, "Searching for: \"$query\" among ${entries.size} entries")
        return findSimilarInList(query, entries, limit)
    }

    /**
     * Wersja bezpośrednia - przyjmuje listę wpisów i znajduje pasujące.
     */
    fun findSimilarInList(query: String, entries: List<ConversationEntry>, limit: Int = 5): List<MemoryMatch> {
        if (entries.isEmpty()) return emptyList()

        val queryTokens = tokenize(query)
        val queryTf = computeTf(queryTokens)

        val docFreq = mutableMapOf<String, Int>()
        entries.forEach { entry ->
            val tokens = tokenize(entry.userQuestion + " " + entry.aiResponse)
            tokens.toSet().forEach { token ->
                docFreq[token] = (docFreq[token] ?: 0) + 1
            }
        }
        val idf = computeIdf(docFreq, entries.size)

        return entries.map { entry ->
            val docTokens = tokenize(entry.userQuestion + " " + entry.aiResponse)
            val docTf = computeTf(docTokens)
            val score = cosineSimilarity(queryTf, docTf, idf)
            MemoryMatch(entry, score)
        }.filter { it.score > 0.1f }
         .sortedByDescending { it.score }
         .take(limit)
    }

    /**
     * Tokenizacja - dzieli tekst na słowa, normalizuje, usuwa stopwords.
     */
    private fun tokenize(text: String): List<String> {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")  // tylko litery i cyfry
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopwords }
    }

    /**
     * TF (term frequency) - jak często słowo w dokumencie.
     */
    private fun computeTf(tokens: List<String>): Map<String, Float> {
        val tf = mutableMapOf<String, Float>()
        tokens.forEach { token ->
            tf[token] = (tf[token] ?: 0f) + 1f
        }
        // Normalizuj
        val max = tf.values.maxOrNull() ?: 1f
        return tf.mapValues { it.value / max }
    }

    /**
     * IDF (inverse document frequency) - jak rzadkie słowo w korpusie.
     */
    private fun computeIdf(docFreq: Map<String, Int>, totalDocs: Int): Map<String, Float> {
        return docFreq.mapValues { (_, df) ->
            Math.log((totalDocs.toFloat() / (df + 1)).toDouble()).toFloat() + 1f
        }
    }

    /**
     * Cosine similarity między dwoma wektorami TF-IDF.
     */
    private fun cosineSimilarity(
        queryTf: Map<String, Float>,
        docTf: Map<String, Float>,
        idf: Map<String, Float>
    ): Float {
        var dot = 0f
        var queryNorm = 0f
        var docNorm = 0f

        // Oblicz dot product i normy
        val allTerms = queryTf.keys + docTf.keys
        allTerms.forEach { term ->
            val qVal = (queryTf[term] ?: 0f) * (idf[term] ?: 0f)
            val dVal = (docTf[term] ?: 0f) * (idf[term] ?: 0f)
            dot += qVal * dVal
            queryNorm += qVal * qVal
            docNorm += dVal * dVal
        }

        val denominator = Math.sqrt((queryNorm * docNorm).toDouble())
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    /**
     * Generuje hash ID dla pytania (do cache'owania).
     */
    fun hashQuery(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}

data class MemoryMatch(
    val entry: ConversationEntry,
    val score: Float  // 0.0 - 1.0+
) {
    fun summary(): String = "Q: ${entry.userQuestion.take(60)}... A: ${entry.aiResponse.take(80)}..."
}
