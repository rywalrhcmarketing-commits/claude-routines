package pl.victor.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import pl.victor.app.ai.ImageResolution
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Dopasowuje zdjęcia do rozdzielczości wybranego trybu przechwytywania.
 *
 * Bez tego [ImageResolution] było przekazywane przez cały łańcuch wywołań
 * i nigdzie nie używane - tryby różniły się wyłącznie nazwą i liczbą klatek.
 * Teraz `FAST_BURST` naprawdę wysyła do modelu mniejsze obrazy niż
 * `HIGH_QUALITY_SINGLE`, co przekłada się na koszt zapytania i czas odpowiedzi.
 *
 * Zdjęcie mniejsze niż limit **nie jest** powiększane - to by tylko zwiększyło
 * rozmiar bez dodania informacji.
 */
object ImageScaler {

    private const val TAG = "ImageScaler"

    /**
     * Przeskalowuje i rekompresuje zdjęcie do limitów [resolution].
     *
     * @return przetworzone bajty JPEG albo oryginał, gdy przetworzenie się nie
     *         uda lub gdy zdjęcie i tak mieści się w limicie
     */
    fun fit(jpeg: ByteArray, resolution: ImageResolution): ByteArray {
        if (jpeg.isEmpty()) return jpeg

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Nie odczytałem wymiarów zdjęcia - zostawiam oryginał")
                return jpeg
            }

            if (width <= resolution.maxWidth && height <= resolution.maxHeight) {
                // Miniatury z okularów zwykle tu trafiają - nie ma czego zmniejszać.
                return jpeg
            }

            // inSampleSize potęgi dwójki: dekodujemy od razu mniejszy obraz,
            // zamiast wczytywać pełny i dopiero potem skalować.
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(width, height, resolution)
            }
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                ?: return jpeg

            val scaled = scaleToFit(decoded, resolution)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, resolution.jpegQuality, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()

            val result = out.toByteArray()
            Log.d(
                TAG,
                "Dopasowano ${width}x$height (${jpeg.size} B) do ${resolution.name} " +
                    "(${result.size} B)"
            )
            // Gdyby rekompresja wyszła większa niż oryginał, oryginał jest lepszy.
            if (result.isNotEmpty() && result.size < jpeg.size) result else jpeg
        } catch (e: Throwable) {
            // OutOfMemory przy dużym zdjęciu jest realne - lepiej oddać oryginał
            // niż wywrócić przechwytywanie.
            Log.w(TAG, "Skalowanie nie powiodło się - zostawiam oryginał", e)
            jpeg
        }
    }

    /** Największa potęga dwójki, przy której obraz nadal mieści się w limicie. */
    internal fun sampleSizeFor(width: Int, height: Int, resolution: ImageResolution): Int {
        var sample = 1
        while (width / (sample * 2) >= resolution.maxWidth &&
            height / (sample * 2) >= resolution.maxHeight
        ) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(bitmap: Bitmap, resolution: ImageResolution): Bitmap {
        val ratio = min(
            resolution.maxWidth.toFloat() / bitmap.width,
            resolution.maxHeight.toFloat() / bitmap.height
        )
        if (ratio >= 1f) return bitmap
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Przekłada rozdzielczość na parametr jakości miniatury okularów (0-6).
     *
     * To **nasze** odwzorowanie, nie stała producenta: dokumentacja podaje tylko
     * zakres, bez powiązania z wymiarami. Wyższa wartość to ostrzejsza miniatura
     * kosztem dłuższego transferu po BLE.
     */
    fun thumbnailQualityFor(resolution: ImageResolution): Int = when (resolution) {
        ImageResolution.LOW -> 1
        ImageResolution.MEDIUM -> 2
        ImageResolution.HIGH -> 4
        // 5, nie 6: tabela producenta kończy się na 5 ("Detailed").
        ImageResolution.ULTRA -> 5
    }
}
