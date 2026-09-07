package pl.victor.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pełna rozdzielczość kosztuje kilkanaście sekund (Wi-Fi Direct), więc ta
 * decyzja musi być trafna w obie strony: bez niej "przeczytaj, co tu pisze"
 * nie ma prawa zadziałać, a z nią wszędzie - każde "co przede mną jest"
 * czekałoby pół minuty.
 */
class VisionDetailTest {

    @Test
    fun `prosba o przeczytanie wymaga szczegolu`() {
        assertTrue(VisionDetail.needsDetail("Przeczytaj na głos cały tekst widoczny na zdjęciu"))
        assertTrue(VisionDetail.needsDetail("co tu pisze?"))
        assertTrue(VisionDetail.needsDetail("Jaki jest skład tego produktu?"))
        assertTrue(VisionDetail.needsDetail("Sprawdź datę ważności"))
        assertTrue(VisionDetail.needsDetail("Ile kosztuje? Zobacz cenę"))
    }

    @Test
    fun `zwykly opis otoczenia nie wymaga szczegolu`() {
        assertFalse(VisionDetail.needsDetail("Co widzisz przede mną?"))
        assertFalse(VisionDetail.needsDetail("Opisz, co jest w tym pokoju"))
        assertFalse(VisionDetail.needsDetail("Czy to jest bezpieczne?"))
        assertFalse(VisionDetail.needsDetail(""))
    }

    @Test
    fun `odmiana nie gubi trafienia`() {
        // Rdzenie, nie całe słowa - inaczej polska odmiana zjada połowę.
        assertTrue(VisionDetail.needsDetail("Co jest na etykiecie?"))
        assertTrue(VisionDetail.needsDetail("Przeczytaj mi instrukcję"))
        assertTrue(VisionDetail.needsDetail("Jakie są litery na tabliczce?"))
    }
}
