package pl.victor.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.victor.app.ai.ImageResolution
import java.io.ByteArrayOutputStream

/**
 * Skalowanie zdjęć na prawdziwym Androidzie - tu dopiero istnieje `Bitmap`.
 *
 * Sprawdza to, co decyduje o koszcie zapytania do modelu: czy duże zdjęcie
 * naprawdę schodzi do limitu trybu i czy małe nie jest ruszane.
 */
@RunWith(AndroidJUnit4::class)
class ImageScalerInstrumentedTest {

    @Test
    fun duzeZdjecieSchodziDoLimituTrybu() {
        val original = jpeg(3000, 2000)

        val fitted = ImageScaler.fit(original, ImageResolution.MEDIUM)

        val (width, height) = dimensions(fitted)
        assertTrue(
            "szerokość $width przekracza limit ${ImageResolution.MEDIUM.maxWidth}",
            width <= ImageResolution.MEDIUM.maxWidth
        )
        assertTrue(
            "wysokość $height przekracza limit ${ImageResolution.MEDIUM.maxHeight}",
            height <= ImageResolution.MEDIUM.maxHeight
        )
        assertTrue(
            "przeskalowane zdjęcie ma być mniejsze: ${original.size} -> ${fitted.size} B",
            fitted.size < original.size
        )
    }

    @Test
    fun proporcjeSaZachowane() {
        val original = jpeg(3000, 2000)  // 3:2

        val (width, height) = dimensions(ImageScaler.fit(original, ImageResolution.MEDIUM))

        val ratio = width.toFloat() / height
        assertEquals("proporcje mają zostać zachowane", 1.5f, ratio, 0.02f)
    }

    @Test
    fun maleZdjecieNieJestRuszane() {
        // Miniatury z okularów są mniejsze niż limity - powiększanie tylko
        // zwiększyłoby rozmiar bez dodania informacji.
        val small = jpeg(320, 240)

        val fitted = ImageScaler.fit(small, ImageResolution.ULTRA)

        assertTrue("małe zdjęcie ma przejść bez zmian", small.contentEquals(fitted))
    }

    @Test
    fun nizszaRozdzielczoscDajeMniejszyPlik() {
        val original = jpeg(3000, 2000)

        val low = ImageScaler.fit(original, ImageResolution.LOW).size
        val ultra = ImageScaler.fit(original, ImageResolution.ULTRA).size

        assertTrue(
            "tryb LOW ma dawać wyraźnie mniejszy plik niż ULTRA: $low vs $ultra B",
            low < ultra
        )
    }

    @Test
    fun wynikJestNadalPoprawnymJpegiem() {
        val fitted = ImageScaler.fit(jpeg(3000, 2000), ImageResolution.LOW)

        assertEquals(0xFF.toByte(), fitted[0])
        assertEquals(0xD8.toByte(), fitted[1])
        assertNotNull(
            "wynik ma się dekodować - inaczej model dostanie śmieci",
            BitmapFactory.decodeByteArray(fitted, 0, fitted.size)
        )
    }

    @Test
    fun uszkodzoneBajtyPrzechodzaBezZmian() {
        // Lepiej oddać oryginał niż wywrócić przechwytywanie.
        val garbage = ByteArray(64) { it.toByte() }
        assertTrue(garbage.contentEquals(ImageScaler.fit(garbage, ImageResolution.MEDIUM)))
    }

    // === Pomocnicze ===

    /** Zdjęcie testowe z gradientem - jednolity kolor kompresowałby się nierealnie dobrze. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (x in 0 until width step 8) {
            paint.color = Color.rgb((x * 7) % 256, (x * 13) % 256, (x * 29) % 256)
            canvas.drawRect(x.toFloat(), 0f, (x + 8).toFloat(), height.toFloat(), paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun dimensions(jpeg: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }
}
