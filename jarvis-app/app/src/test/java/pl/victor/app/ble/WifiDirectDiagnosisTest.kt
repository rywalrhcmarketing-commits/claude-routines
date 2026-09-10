package pl.victor.app.ble

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectDiagnosisTest {

    @Test
    fun `wszystko wlaczone przepuszcza szukanie`() {
        assertNull(
            WifiDirectDiagnosis.preflight(
                p2pAvailable = true,
                wifiEnabled = true,
                locationEnabled = true,
                sdkInt = 31
            )
        )
    }

    @Test
    fun `wylaczone wifi mowi o wifi`() {
        val text = WifiDirectDiagnosis.preflight(true, false, true, 34)
        assertTrue(text!!, "Wi-Fi" in text)
    }

    @Test
    fun `wylaczona lokalizacja blokuje na androidzie 12`() {
        val text = WifiDirectDiagnosis.preflight(true, true, false, 32)
        assertTrue(text!!, "Lokalizacj" in text)
    }

    @Test
    fun `wylaczona lokalizacja nie przeszkadza na androidzie 13`() {
        assertNull(WifiDirectDiagnosis.preflight(true, true, false, 33))
    }

    @Test
    fun `brak wifi direct wygrywa z reszta`() {
        val text = WifiDirectDiagnosis.preflight(false, false, false, 30)
        assertTrue(text!!, "nie obsługuje" in text)
    }

    @Test
    fun `wylaczone wifi wygrywa z lokalizacja`() {
        // Bez radia lokalizacja i tak nic nie zmieni - użytkownik ma zrobić
        // JEDNĄ rzecz, nie dostać listy.
        val text = WifiDirectDiagnosis.preflight(true, false, false, 30)
        assertTrue(text!!, "Wi-Fi jest wyłączone" in text)
    }

    @Test
    fun `kod zajete radzi odczekac`() {
        val text = WifiDirectDiagnosis.discoveryRefused(WifiDirectDiagnosis.BUSY)
        assertTrue(text, "Odczekaj" in text)
    }

    @Test
    fun `kod nieobslugiwane mowi o telefonie`() {
        val text = WifiDirectDiagnosis.discoveryRefused(WifiDirectDiagnosis.P2P_UNSUPPORTED)
        assertTrue(text, "nie obsługuje" in text)
    }

    @Test
    fun `nieznany kod tez daje rade`() {
        assertTrue(WifiDirectDiagnosis.discoveryRefused(99).isNotBlank())
    }

    @Test
    fun `pusta lista nie mowi zeby podejsc blizej`() {
        // Zero widocznych urządzeń nie ma nic wspólnego z odległością - to
        // dawny komunikat wysyłał użytkownika w złą stronę.
        val text = WifiDirectDiagnosis.nothingFound(emptyList())
        assertTrue(text, "bliżej" !in text)
    }

    @Test
    fun `widziane urzadzenia trafiaja do komunikatu`() {
        val text = WifiDirectDiagnosis.nothingFound(listOf("Galaxy S21", "DIRECT-xy"))
        assertTrue(text, "Galaxy S21" in text)
        assertTrue(text, "DIRECT-xy" in text)
    }

    @Test
    fun `progiem lokalizacji jest android 13`() {
        assertTrue(WifiDirectDiagnosis.needsLocationOn(32))
        assertTrue(!WifiDirectDiagnosis.needsLocationOn(33))
    }
}
