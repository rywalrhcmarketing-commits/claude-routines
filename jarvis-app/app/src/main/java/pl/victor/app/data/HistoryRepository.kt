package pl.victor.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repozytorium historii - wysoki poziom abstrakcji nad ConversationDao.
 * Dodaje walidację, limity, formatowanie.
 */
class HistoryRepository(
    private val dao: ConversationDao
) {
    /** Obserwuje ostatnie 20 rozmów (limit per user story US-007) */
    fun observeRecent(limit: Int = 20): Flow<List<ConversationEntry>> {
        return dao.observeRecent(limit).map { list ->
            list.filter { it.aiResponse.isNotBlank() }  // sanity check
        }
    }

    suspend fun save(
        question: String,
        response: String,
        providerId: String,
        firstPhotoPath: String? = null,
        photoCount: Int = 0,
        tokensUsed: Int = 0,
        sourcesJson: String? = null
    ): Long {
        val entry = ConversationEntry(
            timestamp = System.currentTimeMillis(),
            userQuestion = question.take(500),  // limit długości
            aiResponse = response.take(2000),  // limit długości
            providerId = providerId,
            firstPhotoPath = firstPhotoPath,
            photoCount = photoCount,
            tokensUsed = tokensUsed,
            sourcesJson = sourcesJson
        )
        return dao.insert(entry)
    }

    /** Migawka ostatnich rozmów (bez obserwowania). */
    suspend fun getRecent(limit: Int = 20): List<ConversationEntry> = dao.getRecent(limit)

    /** Przycina historię do zadanego limitu, kasując najstarsze wpisy. */
    suspend fun trimTo(limit: Int) = dao.trimTo(limit)

    suspend fun getById(id: Long): ConversationEntry? = dao.getById(id)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun count(): Int = dao.count()
}
