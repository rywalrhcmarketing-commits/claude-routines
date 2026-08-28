package pl.jarvis.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy parsera wizytówek z kodów QR.
 *
 * Wizytówki w praktyce bywają niezgodne ze specyfikacją, więc parser ma być
 * pobłażliwy: pomijać nieznane pola zamiast się wywracać.
 */
class VCardParserTest {

    private val fullVCard = """
        BEGIN:VCARD
        VERSION:3.0
        N:Kowalski;Jan;;;
        FN:Jan Kowalski
        ORG:Przykład sp. z o.o.
        TITLE:Dyrektor
        TEL;TYPE=CELL:+48 600 100 200
        EMAIL:jan@przyklad.pl
        URL:https://przyklad.pl
        ADR;TYPE=WORK:;;Długa 14;Warszawa;;00-001;Polska
        END:VCARD
    """.trimIndent()

    @Test
    fun `rozpoznaje vCard i MeCard`() {
        assertTrue(VCardParser.looksLikeContact(fullVCard))
        assertTrue(VCardParser.looksLikeContact("MECARD:N:Kowalski,Jan;TEL:600100200;;"))
    }

    @Test
    fun `zwykly tekst i URL to nie wizytowka`() {
        assertFalse(VCardParser.looksLikeContact("https://przyklad.pl"))
        assertFalse(VCardParser.looksLikeContact("Zwykły tekst"))
    }

    @Test
    fun `czyta wszystkie pola vCard`() {
        val card = VCardParser.parse(fullVCard)!!
        assertEquals("Jan Kowalski", card.name)
        assertEquals("Przykład sp. z o.o.", card.organization)
        assertEquals("Dyrektor", card.title)
        assertEquals(listOf("+48 600 100 200"), card.phones)
        assertEquals(listOf("jan@przyklad.pl"), card.emails)
        assertEquals("https://przyklad.pl", card.url)
    }

    @Test
    fun `FN ma pierwszenstwo przed N`() {
        val card = VCardParser.parse(fullVCard)!!
        // Gdyby brane było N, wyszłoby odwrócone "Jan Kowalski" z separatorów.
        assertEquals("Jan Kowalski", card.name)
    }

    @Test
    fun `sklada imie z pola N gdy brak FN`() {
        val card = VCardParser.parse(
            "BEGIN:VCARD\nN:Nowak;Anna;;;\nTEL:600100200\nEND:VCARD"
        )!!
        assertEquals("Anna Nowak", card.name)
    }

    @Test
    fun `zbiera wiele telefonow i maili`() {
        val card = VCardParser.parse(
            """
            BEGIN:VCARD
            FN:Anna Nowak
            TEL;TYPE=CELL:600100200
            TEL;TYPE=WORK:221234567
            EMAIL;TYPE=WORK:a@firma.pl
            EMAIL;TYPE=HOME:anna@dom.pl
            END:VCARD
            """.trimIndent()
        )!!
        assertEquals(2, card.phones.size)
        assertEquals(2, card.emails.size)
    }

    @Test
    fun `skleja linie zawiniete zgodnie z RFC`() {
        // Kontynuacja linii zaczyna się białym znakiem - inaczej nazwa byłaby ucięta.
        val card = VCardParser.parse(
            "BEGIN:VCARD\nFN:Jan\n  Kowalski-Nowak\nEND:VCARD"
        )!!
        assertEquals("Jan Kowalski-Nowak", card.name)
    }

    @Test
    fun `odwraca sekwencje ucieczki`() {
        val card = VCardParser.parse(
            "BEGIN:VCARD\nFN:Firma\\, Oddział\nEND:VCARD"
        )!!
        assertEquals("Firma, Oddział", card.name)
    }

    @Test
    fun `czyta MeCard`() {
        val card = VCardParser.parse("MECARD:N:Kowalski,Jan;TEL:600100200;EMAIL:j@p.pl;;")!!
        assertEquals("Jan Kowalski", card.name)
        assertEquals(listOf("600100200"), card.phones)
        assertEquals(listOf("j@p.pl"), card.emails)
    }

    @Test
    fun `wizytowka bez nazwy jest odrzucana`() {
        // Sam telefon bez nazwiska nie jest użytecznym kontaktem.
        assertNull(VCardParser.parse("BEGIN:VCARD\nTEL:600100200\nEND:VCARD"))
    }

    @Test
    fun `nieznane pola nie wywracaja parsera`() {
        val card = VCardParser.parse(
            """
            BEGIN:VCARD
            FN:Jan Kowalski
            X-JAKIES-DZIWNE-POLE:cokolwiek
            PHOTO;ENCODING=b:AAAA
            NOTE:notatka
            END:VCARD
            """.trimIndent()
        )
        assertEquals("Jan Kowalski", card!!.name)
    }

    @Test
    fun `opis mowiony zawiera najwazniejsze dane`() {
        val spoken = VCardParser.parse(fullVCard)!!.spoken()
        assertTrue(spoken.contains("Jan Kowalski"))
        assertTrue(spoken.contains("+48 600 100 200"))
    }

    @Test
    fun `kontekst dla AI zawiera etykiety pol`() {
        val ctx = VCardParser.parse(fullVCard)!!.toPromptContext()
        assertTrue(ctx.contains("Imię i nazwisko: Jan Kowalski"))
        assertTrue(ctx.contains("Firma: Przykład sp. z o.o."))
    }
}
