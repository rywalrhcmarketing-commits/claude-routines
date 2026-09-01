package pl.victor.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pojedynczy wpis w historii rozmów.
 * Przechowywany lokalnie w Room database.
 */
@Entity(tableName = "conversations")
data class ConversationEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Kiedy odbyła się rozmowa (ms since epoch) */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** Pytanie użytkownika (text) - audio transkrypcja w v1.1 */
    @ColumnInfo(name = "user_question")
    val userQuestion: String,

    /** Odpowiedź AI (text) */
    @ColumnInfo(name = "ai_response")
    val aiResponse: String,

    /** Który provider AI odpowiedział */
    @ColumnInfo(name = "provider_id")
    val providerId: String,

    /** Path do pierwszego zdjęcia (cache) lub null */
    @ColumnInfo(name = "first_photo_path")
    val firstPhotoPath: String? = null,

    /** Ile zdjęć było w tym zapytaniu */
    @ColumnInfo(name = "photo_count")
    val photoCount: Int = 0,

    /** Ile tokenów zużyto */
    @ColumnInfo(name = "tokens_used")
    val tokensUsed: Int = 0,

    /** Źródła z web search (JSON array) */
    @ColumnInfo(name = "sources_json")
    val sourcesJson: String? = null
)
