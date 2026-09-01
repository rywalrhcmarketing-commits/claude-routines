package pl.victor.app.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager zdjęć - zapisuje zdjęcia z okularów do pamięci wewnętrznej aplikacji.
 *
 * Pliki: filesDir/photos/photo_{timestamp}.jpg
 * - filesDir jest prywatny dla apki
 * - Android usunie je gdy user odinstaluje apkę
 * - Dostęp tylko z naszej apki (nie z innych apek)
 *
 * Używane do:
 * - Miniaturki w historii rozmów
 * - Zapisywanie zdjęć z burst capture
 */
class PhotoStorage(private val context: Context) {

    private val tag = "PhotoStorage"

    private val photosDir: File by lazy {
        File(context.filesDir, "photos").apply {
            if (!exists()) mkdirs()
        }
    }

    private val videosDir: File by lazy {
        File(context.filesDir, "videos").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Zapisuje zdjęcie (JPEG/PNG) do pliku.
     * @return ścieżka do pliku lub null jeśli błąd
     */
    fun savePhoto(imageBytes: ByteArray, prefix: String = "photo"): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(photosDir, "${prefix}_${timestamp}.jpg")
            FileOutputStream(file).use { out ->
                out.write(imageBytes)
            }
            Log.d(tag, "Saved photo: ${file.absolutePath} (${imageBytes.size} bytes)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Failed to save photo", e)
            null
        }
    }

    /**
     * Ładuje zdjęcie z pliku.
     * @return ByteArray lub null jeśli brak pliku
     */
    fun loadPhoto(path: String): ByteArray? {
        return try {
            val file = File(path)
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            Log.w(tag, "Failed to load photo: $path", e)
            null
        }
    }

    /**
     * Usuwa zdjęcie z dysku.
     */
    fun deletePhoto(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(tag, "Deleted photo: $path (success=$deleted)")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to delete photo: $path", e)
            false
        }
    }

    /**
     * Usuwa WSZYSTKIE zdjęcia - używane przy czyszczeniu historii.
     * @return ile usunięto
     */
    fun deleteAllPhotos(): Int {
        return try {
            val files = photosDir.listFiles() ?: return 0
            var count = 0
            files.forEach { file ->
                if (file.isFile && file.delete()) count++
            }
            Log.i(tag, "Deleted $count photos")
            count
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete all photos", e)
            0
        }
    }

    /**
     * Ile zdjęć jest zapisanych.
     */
    fun getPhotoCount(): Int = photosDir.listFiles()?.size ?: 0

    /**
     * Ile zajmują miejsce (w bajtach).
     */
    fun getTotalSize(): Long {
        return photosDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Czyści stare zdjęcia (>30 dni) - automatyczne porządki.
     */
    fun cleanupOldPhotos(maxAgeDays: Int = 30): Int {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000L
        return try {
            val files = photosDir.listFiles() ?: return 0
            var count = 0
            files.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    if (file.delete()) count++
                }
            }
            if (count > 0) Log.i(tag, "Cleaned up $count old photos")
            count
        } catch (e: Exception) {
            Log.e(tag, "Cleanup failed", e)
            0
        }
    }

    /**
     * Zapisuje zdjęcie z konwersacji (miniatura do UI historii).
     */
    fun saveConversationPhoto(imageBytes: ByteArray, name: String): String? {
        return savePhoto(imageBytes, prefix = "conv_$name")
    }

    /**
     * Zapisuje wideo z okularów.
     */
    fun saveVideo(videoBytes: ByteArray, filename: String): String? {
        return try {
            val file = File(videosDir, filename)
            file.writeBytes(videoBytes)
            Log.d(tag, "Video saved: ${file.absolutePath} (${videoBytes.size} bytes)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "saveVideo failed", e)
            null
        }
    }
}
