package pl.victor.app.localmodel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes <= 0) 0 else ((downloadedBytes * 100) / totalBytes).toInt()
}

/**
 * Pobiera plik modelu bezpośrednio z Hugging Face (zwykłe HTTPS GET na
 * `resolve/main/...` - model jest publiczny, bez tokenu). Pisze do `.part`,
 * sprawdza nagłówek GGUF i dopiero potem podmienia na docelową nazwę - żeby
 * przerwane pobieranie nigdy nie wyglądało jak gotowy model.
 */
class LocalModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        entry: LocalModelCatalogEntry,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val assessment = DeviceCapability.assess(context, entry)
            if (!assessment.supported) {
                throw IllegalStateException(assessment.blockers.joinToString("; "))
            }

            val tempFile = LocalModelStorage.tempFile(context, entry)
            val request = Request.Builder().url(entry.sourceUrl).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Pobieranie nie powiodło się: HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Pusta odpowiedź serwera")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: entry.sizeBytes

                body.byteStream().use { input ->
                    RandomAccessFile(tempFile, "rw").use { output ->
                        output.setLength(0)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReportedPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val progress = DownloadProgress(downloaded, totalBytes)
                            if (progress.percent != lastReportedPercent) {
                                lastReportedPercent = progress.percent
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            if (!looksLikeGguf(tempFile)) {
                tempFile.delete()
                throw IllegalStateException("Pobrany plik nie wygląda na poprawny model GGUF")
            }

            val targetFile = LocalModelStorage.targetFile(context, entry)
            targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            Log.i(TAG, "Model ${entry.id} pobrany: ${targetFile.length()} bajtów")
            targetFile
        }.onFailure { e ->
            Log.w(TAG, "Pobieranie modelu ${entry.id} nie powiodło się", e)
            LocalModelStorage.tempFile(context, entry).delete()
        }
    }

    private fun looksLikeGguf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        return header.contentEquals(GGUF_MAGIC)
    }

    private companion object {
        const val TAG = "LocalModelDownloader"
        const val BUFFER_SIZE = 8 * 1024
        val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    }
}
