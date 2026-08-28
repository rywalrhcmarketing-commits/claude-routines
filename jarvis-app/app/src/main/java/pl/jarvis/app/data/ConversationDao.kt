package pl.jarvis.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO do obsługi historii rozmów w Room database.
 */
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ConversationEntry): Long

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ConversationEntry>>

    /** Migawka ostatnich rozmów - do wyszukiwania w pamięci długoterminowej. */
    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ConversationEntry>

    /** Usuwa najstarsze wpisy ponad limit. */
    @Query(
        "DELETE FROM conversations WHERE id NOT IN " +
            "(SELECT id FROM conversations ORDER BY timestamp DESC LIMIT :limit)"
    )
    suspend fun trimTo(limit: Int)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntry?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}
