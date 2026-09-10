package pl.victor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailureTest {

    @Test
    fun `brak srodkow w DeepSeeku mowi o srodkach`() {
        val text = ProviderFailure.describe(
            "HTTP 402: {\"error\":{\"message\":\"Insufficient Balance\"}}"
        )
        assertTrue(text, "środków" in text)
    }

    @Test
    fun `limit zapytan mowi o odczekaniu`() {
        val text = ProviderFailure.describe("HTTP 429 Too Many Requests")
        assertTrue(text, "odczekać" in text)
    }

    @Test
    fun `zly klucz Gemini mowi o kluczu`() {
        val text = ProviderFailure.describe(
            "HTTP 400: API key not valid. Please pass a valid API key."
        )
        assertTrue(text, "Klucz API" in text)
    }

    @Test
    fun `brak sieci mowi o sieci`() {
        val text = ProviderFailure.describe(
            "java.net.UnknownHostException: Unable to resolve host \"api.deepseek.com\""
        )
        assertTrue(text, "sieci" in text)
    }

    @Test
    fun `blad przejsciowy prosi o powtorzenie`() {
        val text = ProviderFailure.describe("HTTP 503 service unavailable", retryable = true)
        assertTrue(text, "jeszcze raz" in text)
    }

    @Test
    fun `nieznany blad nie jest pusty i nie cytuje HTTP`() {
        val text = ProviderFailure.describe("HTTP 500 internal server error")
        assertTrue(text.isNotBlank())
        assertTrue(text, "HTTP" !in text)
    }

    @Test
    fun `brak komunikatu tez daje zdanie`() {
        assertTrue(ProviderFailure.describe(null).isNotBlank())
    }

    @Test
    fun `wielkosc liter nie ma znaczenia`() {
        assertEquals(
            ProviderFailure.describe("INSUFFICIENT BALANCE"),
            ProviderFailure.describe("insufficient balance")
        )
    }

    @Test
    fun `srodki wygrywaja z limitem gdy sa oba`() {
        val text = ProviderFailure.describe("429: insufficient quota for this request")
        assertTrue(text, "środków" in text)
    }
}
