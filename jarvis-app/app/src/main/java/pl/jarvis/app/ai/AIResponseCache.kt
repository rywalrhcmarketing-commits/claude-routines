package pl.jarvis.app.ai

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Cache odpowiedzi AI - oszczędza requesty (a więc baterię i pieniądze).
 *
 * Cache'ujemy odpowiedzi na podobne pytania.
 * - Klucz: hash(question + provider + model)
 * - TTL: 1h dla faktów, 24h dla ogólnych
 * - Limit: 50 wpisów (LRU)
 *
 * Kiedy NIE cache'ujemy:
 * - Gdy są zdjęcia (multimodal) - za różne wyniki
 * - Gdy pytanie o "teraz" / "dziś" - czasowe
 * - Gdy wykryto URL z QR - kontekst
 *
 * Kiedy cache'ujemy:
 * - Pytania ogólne ("co to jest fotosynteza")
 * - Definicje, wyjaśnienia
 * - Pytania o stałe fakty
 */
class AIResponseCache {

    private val tag = "AIResponseCache"
    private val cache = LinkedHashMap<String, CacheEntry>(50, 0.75f, true)
    private val maxSize = 50

    /**
     * Próbuje znaleźć odpowiedź w cache.
     * Zwraca null jeśli nie ma lub wygasła.
     */
    fun get(question: String, providerId: String, modelId: String): String? {
        val key = hashKey(question, providerId, modelId)
        val entry = cache[key] ?: return null
        if (entry.isExpired()) {
            cache.remove(key)
            return null
        }
        Log.d(tag, "Cache HIT: ${question.take(30)}...")
        return entry.answer
    }

    /**
     * Zapisuje odpowiedź w cache.
     */
    fun put(
        question: String,
        answer: String,
        providerId: String,
        modelId: String,
        ttlMinutes: Int = 60
    ) {
        if (cache.size >= maxSize) {
            // LRU eviction - usuwa pierwszy (najdawniej używany)
            val oldest = cache.keys.first()
            cache.remove(oldest)
        }
        val key = hashKey(question, providerId, modelId)
        cache[key] = CacheEntry(
            answer = answer,
            expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ttlMinutes.toLong())
        )
        Log.d(tag, "Cache PUT: ${question.take(30)}... (TTL ${ttlMinutes}min)")
    }

    /**
     * Czyści cache (np. przy zmianie ustawień).
     */
    fun clear() {
        cache.clear()
        Log.i(tag, "Cache cleared")
    }

    /**
     * Ile jest wpisów.
     */
    fun size(): Int = cache.size

    /**
     * Hash klucz (question + provider + model).
     */
    private fun hashKey(question: String, providerId: String, modelId: String): String {
        val input = "${question.lowercase().trim()}|$providerId|$modelId"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Sprawdza czy pytanie kwalifikuje się do cache'owania.
     */
    fun shouldCache(question: String): Boolean {
        val lower = question.lowercase()

        // Nie cacheuj pytań czasowych
        val timeTriggers = listOf("teraz", "dziś", "wczoraj", "jutro", "za ile",
            "kiedy", "godzina", "data", "pogoda", "kalendarz")
        if (timeTriggers.any { lower.contains(it) }) return false

        // Nie cacheuj personalnych
        val personalTriggers = listOf("moje", "mam", "byłem", "byłam", "pamiętasz",
            "zrobiłem", "zrobiłam", "wysłałem", "wysłałam")
        if (personalTriggers.any { lower.contains(it) }) return false

        return true
    }

    data class CacheEntry(
        val answer: String,
        val expiresAt: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
    }
}
