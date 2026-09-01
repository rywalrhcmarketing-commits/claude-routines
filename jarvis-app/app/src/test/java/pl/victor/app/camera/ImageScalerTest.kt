package pl.victor.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.victor.app.ai.ImageResolution

/**
 * Testy czystej części skalera - doboru `inSampleSize` i odwzorowania
 * rozdzielczości na parametr jakości miniatury.
 *
 * Samo skalowanie wymaga Androida (Bitmap), więc siedzi w teście
 * instrumentacyjnym; tu sprawdzamy arytmetykę, w której najłatwiej o błąd
 * o jeden krok w którąś stronę.
 */
class ImageScalerTest {

    @Test
    fun `obraz mieszczacy sie w limicie nie jest probkowany`() {
        // 640x480 przy limicie MEDIUM (1280x720) - nie ma czego zmniejszać.
        assertEquals(1, ImageScaler.sampleSizeFor(640, 480, ImageResolution.MEDIUM))
    }

    @Test
    fun `obraz nieco wiekszy od limitu nie jest probkowany`() {
        // Zmniejszenie o połowę zeszłoby poniżej limitu - lepiej przeskalować
        // dokładnie, niż zgubić szczegóły potęgą dwójki.
        assertEquals(1, ImageScaler.sampleSizeFor(1600, 900, ImageResolution.MEDIUM))
    }

    @Test
    fun `obraz dwukrotnie wiekszy jest probkowany dwukrotnie`() {
        assertEquals(2, ImageScaler.sampleSizeFor(2560, 1440, ImageResolution.MEDIUM))
    }

    @Test
    fun `obraz czterokrotnie wiekszy jest probkowany czterokrotnie`() {
        assertEquals(4, ImageScaler.sampleSizeFor(5120, 2880, ImageResolution.MEDIUM))
    }

    @Test
    fun `probkowanie nigdy nie schodzi ponizej limitu`() {
        // Najważniejsza własność: po podpróbkowaniu obraz ma nadal być
        // co najmniej tak duży jak limit, żeby dokładne skalowanie miało
        // z czego zejść.
        for (resolution in ImageResolution.entries) {
            for (width in listOf(800, 1024, 1920, 3000, 4096, 6000)) {
                val height = width * 9 / 16
                val sample = ImageScaler.sampleSizeFor(width, height, resolution)
                val afterWidth = width / sample
                val afterHeight = height / sample
                assertTrue(
                    "$resolution: ${width}x$height / $sample = ${afterWidth}x$afterHeight " +
                        "zeszło poniżej ${resolution.maxWidth}x${resolution.maxHeight}",
                    sample == 1 ||
                        (afterWidth >= resolution.maxWidth && afterHeight >= resolution.maxHeight)
                )
            }
        }
    }

    @Test
    fun `probkowanie zawsze jest potega dwojki`() {
        for (width in listOf(700, 1300, 2600, 5300, 9000)) {
            val sample = ImageScaler.sampleSizeFor(width, width, ImageResolution.LOW)
            assertTrue(
                "inSampleSize musi być potęgą dwójki, było $sample",
                sample > 0 && (sample and (sample - 1)) == 0
            )
        }
    }

    @Test
    fun `wyzsza rozdzielczosc to wyzsza jakosc miniatury`() {
        val low = ImageScaler.thumbnailQualityFor(ImageResolution.LOW)
        val medium = ImageScaler.thumbnailQualityFor(ImageResolution.MEDIUM)
        val high = ImageScaler.thumbnailQualityFor(ImageResolution.HIGH)
        val ultra = ImageScaler.thumbnailQualityFor(ImageResolution.ULTRA)

        assertTrue("kolejność jakości: $low < $medium < $high < $ultra",
            low < medium && medium < high && high < ultra)
    }

    @Test
    fun `jakosc miniatury miesci sie w zakresie akceptowanym przez okulary`() {
        for (resolution in ImageResolution.entries) {
            val quality = ImageScaler.thumbnailQualityFor(resolution)
            assertTrue(
                "$resolution dało jakość $quality, poza zakresem 0..6",
                quality in pl.victor.app.ble.GlassesProtocol.THUMBNAIL_QUALITY_RANGE
            )
        }
    }

    @Test
    fun `puste bajty przechodza bez zmian`() {
        val empty = ByteArray(0)
        assertTrue(ImageScaler.fit(empty, ImageResolution.MEDIUM).isEmpty())
    }
}
