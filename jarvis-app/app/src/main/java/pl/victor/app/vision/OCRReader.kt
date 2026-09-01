package pl.victor.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR - czyta tekst z otoczenia.
 *
 * Używa ML Kit Text Recognition v2 (Latin script).
 *
 * Use cases:
 * - Czytaj menu restauracji ("Przeczytaj menu")
 * - Czytaj etykiety produktów ("Co jest na tej butelce?")
 * - Czytaj książki/artykuły ("Czytaj to")
 * - Tłumaczenie tekstu z obcego języka ("Przetłumacz to")
 *
 * ML Kit Text Recognition v2:
 * - Obsługuje: łacina + cyrylica + chiński + koreański + japoński + devanagari
 * - Offline (model pobrany przy pierwszym użyciu, ~10MB)
 * - Szybki (~100-500ms na zdjęcie)
 */
class OCRReader {

    private val tag = "OCRReader"

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Czyta tekst z bitmapy.
     */
    suspend fun readBitmap(bitmap: Bitmap): OCRResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val result = recognizeImage(image)
            OCRResult(
                fullText = result.text,
                blocks = result.textBlocks.map { block ->
                    OCRBlock(
                        text = block.text,
                        // ML Kit nie udostępnia pewności rozpoznania dla bloku tekstu.
                        confidence = 0f,
                        boundingBox = block.boundingBox
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "OCR failed", e)
            OCRResult(fullText = "", blocks = emptyList(), error = e.message)
        }
    }

    /**
     * Czyta tekst z ByteArray (JPEG/PNG).
     */
    suspend fun readBytes(imageBytes: ByteArray): OCRResult {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return OCRResult(fullText = "", blocks = emptyList(), error = "decode failed")
        return readBitmap(bitmap)
    }

    /**
     * Czyta tekst z pliku.
     */
    suspend fun readFile(path: String): OCRResult {
        val bitmap = BitmapFactory.decodeFile(path)
            ?: return OCRResult(fullText = "", blocks = emptyList(), error = "decode failed")
        return readBitmap(bitmap)
    }

    /**
     * Async wrapper na ML Kit (który jest natywnie async).
     */
    private suspend fun recognizeImage(image: InputImage) =
        suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    fun close() {
        recognizer.close()
    }
}

/**
 * Wynik OCR.
 */
data class OCRResult(
    val fullText: String,
    val blocks: List<OCRBlock>,
    val error: String? = null
) {
    val isEmpty: Boolean get() = fullText.isBlank()
    val isSuccess: Boolean get() = error == null && fullText.isNotBlank()

    /**
     * Kompaktowy string do wysłania do AI.
     */
    fun toPromptContext(): String = buildString {
        append("Tekst odczytany ze zdjęcia (OCR):\n")
        append("```\n")
        append(fullText.take(3000))  // limit
        append("\n```")
    }
}

data class OCRBlock(
    val text: String,
    val confidence: Float,
    val boundingBox: android.graphics.Rect? = null
)
