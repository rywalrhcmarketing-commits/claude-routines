package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Okulary oddają płaską listę nazw - bez typów i dat. Pomyłka w rozpoznaniu
 * typu pokazuje wideo w zakładce ze zdjęciami, a tego nie widać w kodzie ekranu.
 */
class MediaLibraryTest {

    @Test
    fun `rozpoznaje typy po rozszerzeniu`() {
        assertEquals(MediaLibrary.Kind.PHOTO, MediaLibrary.kindOf("IMG_0001.JPG"))
        assertEquals(MediaLibrary.Kind.VIDEO, MediaLibrary.kindOf("VID_0002.mp4"))
        assertEquals(MediaLibrary.Kind.AUDIO, MediaLibrary.kindOf("REC_0003.wav"))
        assertEquals(MediaLibrary.Kind.OTHER, MediaLibrary.kindOf("media.config"))
    }

    @Test
    fun `najnowsze na gorze`() {
        // Nazwy są sekwencyjne, więc malejąco po nazwie = najnowsze pierwsze.
        val grouped = MediaLibrary.group(listOf("IMG_0001.jpg", "IMG_0003.jpg", "IMG_0002.jpg"))
        val photos = grouped.single { it.first == MediaLibrary.Kind.PHOTO }.second
        assertEquals(listOf("IMG_0003.jpg", "IMG_0002.jpg", "IMG_0001.jpg"), photos.map { it.name })
    }

    @Test
    fun `puste grupy nie trafiaja do wyniku`() {
        // Pusta zakładka "Wideo" wygląda jak awaria, a znaczy tylko tyle, że
        // nikt nie nagrywał.
        val grouped = MediaLibrary.group(listOf("IMG_0001.jpg"))
        assertEquals(1, grouped.size)
        assertEquals(MediaLibrary.Kind.PHOTO, grouped.first().first)
    }

    @Test
    fun `puste nazwy sa pomijane`() {
        assertTrue(MediaLibrary.group(listOf("", "   ")).isEmpty())
    }

    @Test
    fun `tylko zdjecia da sie pokazac na ekranie`() {
        assertTrue(MediaLibrary.isViewable(MediaLibrary.Item("a.jpg", MediaLibrary.Kind.PHOTO)))
        assertFalse(MediaLibrary.isViewable(MediaLibrary.Item("a.mp4", MediaLibrary.Kind.VIDEO)))
    }
}
