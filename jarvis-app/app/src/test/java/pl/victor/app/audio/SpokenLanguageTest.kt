package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pomyłka jest słyszalna w obie strony, ale NIE jest symetryczna: polskie
 * zdanie przeczytane angielskim głosem brzmi gorzej niż angielskie
 * przeczytane polskim. Dlatego próg jest wysoki i to jest tu sprawdzane.
 */
class SpokenLanguageTest {

    @Test
    fun `polskie zdanie zostaje jednym polskim fragmentem`() {
        val segments = SpokenLanguage.split("Jutro o piętnastej masz spotkanie w biurze")
        assertEquals(1, segments.size)
        assertFalse(segments.first().english)
    }

    @Test
    fun `polskie slowa wygladajace neutralnie nie ida po angielsku`() {
        // "start", "sport", "internet", "komputer" nie mają polskich znaków,
        // a są polskimi słowami - same w sobie nie mogą przełączać głosu.
        listOf("Sport i internet", "Komputer stoi na biurku", "Start systemu").forEach {
            assertTrue(SpokenLanguage.split(it).none { seg -> seg.english })
        }
    }

    @Test
    fun `angielska fraza dostaje wlasny fragment`() {
        val segments = SpokenLanguage.split("Film nazywa się The Dark Knight i jest dobry")
        assertTrue(segments.any { it.english && it.text.contains("The") })
        assertTrue(segments.any { !it.english })
    }

    @Test
    fun `neutralne slowa przyklejaja sie do angielskiego ciagu`() {
        val segments = SpokenLanguage.split("the best of all")
        assertEquals(1, segments.size)
        assertTrue(segments.first().english)
    }

    @Test
    fun `mocne zbitki sa rozpoznawane`() {
        assertTrue(SpokenLanguage.looksEnglish("something"))
        assertTrue(SpokenLanguage.looksEnglish("management"))
        assertTrue(SpokenLanguage.looksEnglish("meeting"))
        assertFalse(SpokenLanguage.looksEnglish("szmata"))
        assertFalse(SpokenLanguage.looksEnglish("żółty"))
        assertFalse(SpokenLanguage.looksEnglish("na"))
    }

    @Test
    fun `pusty tekst nie daje fragmentow`() {
        assertTrue(SpokenLanguage.split("").isEmpty())
    }
}
