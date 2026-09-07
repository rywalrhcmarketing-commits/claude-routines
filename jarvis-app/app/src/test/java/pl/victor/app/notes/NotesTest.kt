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
    fun `kalendarz ma pierwszenstwo przed notatnikiem`() {
        // "Zapisz mi spotkanie na piątek" to prośba o wydarzenie. Notatka
        // zamiast wpisu w kalendarzu znaczy brak przypomnienia - po cichu.
        assertNull(Notes.extract("zapisz mi spotkanie na piątek o 15"))
        assertNull(Notes.extract("dodaj do kalendarza wizytę u lekarza"))
        // Zwykła notatka nadal działa.
        assertEquals("Kupić mleko", Notes.extract("zapisz że kupić mleko"))
    }

    @Test
    fun `pytanie o to co zapisalem dotyczy notatek`() {
        assertTrue(Notes.mentionsNotes("co zapisałem wczoraj"))
        assertTrue(Notes.mentionsNotes("czy mam coś do kupienia"))
        assertFalse(Notes.mentionsNotes("jaka jest pogoda w Warszawie"))
    }

    @Test
    fun `notatki niosa date w kontekscie dla modelu`() {
        // Bez daty "co zapisałem wczoraj?" jest pytaniem bez odpowiedzi - model
        // widzi listę zdań bez osi czasu.
        val now = 1_757_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        val context = Notes.buildPromptContext(
            listOf(
                Notes.Note("Kupić mleko", now),
                Notes.Note("Oddać książkę", now - day),
                Notes.Note("Zapłacić rachunek", now - 30 * day)
            ),
            nowMs = now
        )!!
        assertTrue(context.contains("dziś"))
        assertTrue(context.contains("wczoraj"))
        // Stara notatka dostaje samą datę - "30 dni temu" nic nie ułatwia.
        assertFalse(context.contains("30 dni temu"))
        assertTrue(context.contains("Zapłacić rachunek"))
    }

    @Test
    fun `notatka bez daty nie dostaje pustego nawiasu`() {
        // Notatki zapisane przed tą zmianą mają zero zamiast znacznika czasu.
        val context = Notes.buildPromptContext(listOf(Notes.Note("Stara notatka", 0)))!!
        assertTrue(context.contains("- Stara notatka"))
        assertFalse(context.contains("[]"))
    }

    @Test
    fun `brak notatek to brak sekcji`() {
        assertNull(Notes.buildPromptContext(emptyList()))
    }

    @Test
    fun `notatka z dwukropkiem jest rozpoznawana`() {
        // Zgłoszone z użycia: "Notatka: kupić XYZ" szło do modelu, a ten
        // odpowiadał "zapisuję w Twoich notatkach" i nie zapisywał niczego.
        assertEquals("Kupić mleko", Notes.extract("Notatka: kupić mleko"))
        assertEquals("Kupić mleko", Notes.extract("notatka kupić mleko"))
        assertEquals("Kupić mleko", Notes.extract("zapisz: kupić mleko"))
        assertEquals("Kupić mleko", Notes.extract("zanotuj - kupić mleko"))
    }

    @Test
    fun `slowo notatki nadal jest prosba o odczytanie`() {
        // "notatka" jako przedrostek nie może połknąć "notatki" - inaczej
        // prośba o listę zamieniałaby się w nową, pustą notatkę.
        assertNull(Notes.extract("notatki"))
        assertTrue(Notes.isListRequest("notatki"))
    }

    @Test
    fun `przedrostek musi konczyc sie granica slowa`() {
        // "dodajmy" nie zaczyna notatki, choć zaczyna się od "dodaj".
        assertNull(Notes.extract("dodajmy do tego jeszcze jeden argument"))
    }

    @Test
    fun `porzadkowanie odrzuca odpowiedzi ktore nie sa notatka`() {
        val original = "Kupić mleko"
        // Pusto, wielolinijkowo albo znacznie dłużej niż oryginał = model
        // zaczął komentować. Zostaje surowa notatka.
        assertEquals(original, Notes.acceptTidied(original, null))
        assertEquals(original, Notes.acceptTidied(original, "   "))
        assertEquals(original, Notes.acceptTidied(original, "Oto notatka:\nKupić mleko"))
        assertEquals(
            original,
            Notes.acceptTidied(original, "Oczywiście! " + "Bardzo chętnie pomogę. ".repeat(5))
        )
        // Poprawiona jedna linijka przechodzi, razem z obcięciem cudzysłowów.
        assertEquals("Kupić mleko i chleb", Notes.acceptTidied(original, "\"Kupić mleko i chleb\""))
    }

    @Test
    fun `dlugie listy odsylaja do aplikacji`() {
        val many = (1..15).map { Notes.Note("Notatka $it", 0) }
        assertTrue(Notes.speak(many).contains("Resztę zobaczysz w aplikacji"))
    }
}
