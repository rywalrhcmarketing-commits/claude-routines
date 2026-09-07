package pl.victor.app.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rozpoznawanie notatki musi być PEWNE w obie strony: przegapiona notatka
 * ginie w rozmowie z modelem, a fałszywe trafienie zamienia zwykłe pytanie w
 * wpis w notatniku - bez odpowiedzi.
 */
class NotesTest {

    @Test
    fun `zapisz ze zaczyna notatke`() {
        assertEquals(
            "Mam oddać książkę w piątek",
            Notes.extract("zapisz że mam oddać książkę w piątek")
        )
    }

    @Test
    fun `dziala bez ogonkow`() {
        // Rozpoznawanie mowy potrafi oddać "ze" zamiast "że".
        assertEquals("Mam kupić mleko", Notes.extract("zapisz ze mam kupić mleko"))
    }

    @Test
    fun `dodaj tez zaczyna notatke`() {
        assertEquals("Kupić mleko", Notes.extract("dodaj kupić mleko"))
    }

    @Test
    fun `pytanie o notatki nie jest notatka`() {
        // Dopasowanie po fragmencie łapałoby to zdanie i zamiast odpowiedzi
        // użytkownik dostawałby nową notatkę o treści "wczoraj".
        assertNull(Notes.extract("co zapisałeś wczoraj"))
        assertNull(Notes.extract("jaka jest pogoda"))
    }

    @Test
    fun `sam czasownik to nie notatka`() {
        assertNull(Notes.extract("zapisz"))
        assertNull(Notes.extract("zanotuj "))
    }

    @Test
    fun `prosba o odczytanie jest rozpoznawana`() {
        assertTrue(Notes.isListRequest("Notatki"))
        assertTrue(Notes.isListRequest("co mam zapisane?"))
        assertFalse(Notes.isListRequest("zapisz że mam kupić mleko"))
    }

    @Test
    fun `puste notatki maja swoje zdanie`() {
        assertEquals("Nie masz jeszcze żadnych notatek.", Notes.speak(emptyList()))
    }

    @Test
    fun `notatki sa numerowane przy czytaniu`() {
        val spoken = Notes.speak(
            listOf(Notes.Note("Kupić mleko", 0), Notes.Note("Oddać książkę", 0))
        )
        assertTrue(spoken.contains("1. Kupić mleko"))
        assertTrue(spoken.contains("2. Oddać książkę"))
    }

    @Test
    fun `dlugie listy odsylaja do aplikacji`() {
        val many = (1..15).map { Notes.Note("Notatka $it", 0) }
        assertTrue(Notes.speak(many).contains("Resztę zobaczysz w aplikacji"))
    }
}
