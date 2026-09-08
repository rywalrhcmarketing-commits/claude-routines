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
        val segments = SpokenLanguage.split("Cytat brzmi what is the thing i tyle")
        assertTrue(segments.any { it.english && it.text.contains("what") })
        assertTrue(segments.any { !it.english })
    }

    @Test
    fun `krotka nazwa wlasna zostaje po polsku - swiadomy kompromis`() {
        // "The Dark Knight" ma tylko JEDEN mocny sygnał ("the"), więc zostaje
        // po polsku. To jest wybór, nie przeoczenie: tytuł przeczytany z
        // polskim akcentem jest znacznie mniejszym problemem niż całe polskie
        // zdanie przeczytane po angielsku.
        val segments = SpokenLanguage.split("Film nazywa się The Dark Knight i jest dobry")
        assertTrue(segments.none { it.english })
    }

    @Test
    fun `polskie zdania nie ida po angielsku`() {
        // Pierwsza wersja miała "to", "on", "we", "by", "most" i "as" na liście
        // wyrazów angielskich - a to bardzo częste POLSKIE słowa. Każde zdanie
        // z "to" było czytane angielskim głosem.
        listOf(
            "To jest bardzo dobre rozwiązanie",
            "On we Wrocławiu ma most",
            "Dokument i moment to nie angielski",
            "Mam meeting o piętnastej",
            "Marketing i monitoring w firmie"
        ).forEach { sentence ->
            assertTrue(
                "\"$sentence\" nie powinno iść po angielsku",
                SpokenLanguage.split(sentence).none { it.english }
            )
        }
    }

    @Test
    fun `polski tekst w cudzyslowie zostaje polski`() {
        // Cudzysłów obniża próg, ale w cudzysłowie bywa też polski cytat.
        listOf(
            "Powiedział \"nie ma sprawy\" i wyszedł",
            "Napis brzmi \"otwarte od rana\"",
            "Kliknij \"quiz\" na ekranie"
        ).forEach { sentence ->
            assertTrue(
                "\"$sentence\" nie powinno iść po angielsku",
                SpokenLanguage.split(sentence).none { it.english }
            )
        }
    }

    @Test
    fun `angielski cytat w cudzyslowie wystarczy jeden sygnal`() {
        // "quiz" to jedyny sygnal w ciagu - bez cudzyslowu za malo.
        val segments = SpokenLanguage.split("Powiedzial \"quiz night\" i poszedl")
        assertEquals(listOf(false, true, false), segments.map { it.english })
        assertEquals("\"quiz night\"", segments[1].text)
    }

    @Test
    fun `cudzyslow przelacza tylko to co w srodku`() {
        // Polskie wyrazy obok cudzyslowu zostaja polskie.
        val segments = SpokenLanguage.split("Na drzwiach napis \"quiz night\" oraz godziny")
        segments.filter { it.english }.forEach {
            assertEquals("\"quiz night\"", it.text)
        }
        assertTrue(segments.any { it.english })
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
        // "management" i "meeting" celowo NIE są już mocnym sygnałem samodzielnie:
        // to zapożyczenia, które w polskim zdaniu czyta się po polsku.
        assertTrue(SpokenLanguage.looksEnglish("thing"))
        assertFalse(SpokenLanguage.looksEnglish("szmata"))
        assertFalse(SpokenLanguage.looksEnglish("żółty"))
        assertFalse(SpokenLanguage.looksEnglish("na"))
    }

    @Test
    fun `pusty tekst nie daje fragmentow`() {
        assertTrue(SpokenLanguage.split("").isEmpty())
    }
}
