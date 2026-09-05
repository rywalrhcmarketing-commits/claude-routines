package pl.victor.app.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Komunikat błędu jest tu produktem, nie logiem: zgłoszono, że "łączenie z
 * Gemini powinno działać dobrze", a najczęstsza wersja "nie działa" to zły albo
 * niewłaściwie skonfigurowany klucz. Surowe "Gemini API error 400" nie mówi
 * użytkownikowi, co ma z tym zrobić - te testy pilnują, żeby mówiło.
 */
class GeminiErrorMessageTest {

    @Test
    fun `zly klucz mowi wprost o kluczu`() {
        val message = GeminiProvider.explainHttpError(
            400,
            """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}"""
        )
        assertTrue(message, message.contains("klucz", ignoreCase = true))
        assertTrue(message, message.contains("aistudio"))
    }

    @Test
    fun `brak dostepu odsyla do projektu i wlaczonego API`() {
        val message = GeminiProvider.explainHttpError(403, """{"error":{"code":403}}""")
        assertTrue(message, message.contains("Generative Language API"))
    }

    @Test
    fun `limit zapytan jest nazwany limitem`() {
        val message = GeminiProvider.explainHttpError(429, "")
        assertTrue(message, message.contains("limit", ignoreCase = true))
    }

    @Test
    fun `awaria serwera nie obwinia telefonu`() {
        val message = GeminiProvider.explainHttpError(503, "")
        assertTrue(message, message.contains("nie jest problem po stronie"))
    }

    @Test
    fun `brak modelu proponuje zmiane modelu`() {
        val message = GeminiProvider.explainHttpError(404, "")
        assertTrue(message, message.contains("model", ignoreCase = true))
    }

    @Test
    fun `surowa odpowiedz zostaje w komunikacie dla diagnostyki`() {
        val message = GeminiProvider.explainHttpError(400, "SZCZEGOL_Z_SERWERA")
        assertTrue(message, message.contains("HTTP 400"))
        assertTrue(message, message.contains("SZCZEGOL_Z_SERWERA"))
    }

    @Test
    fun `bardzo dluga odpowiedz serwera nie zalewa komunikatu`() {
        val message = GeminiProvider.explainHttpError(400, "x".repeat(5_000))
        assertTrue(message, message.length < 600)
    }

    @Test
    fun `nieznany kod nie gubi sie po cichu`() {
        val message = GeminiProvider.explainHttpError(418, "")
        assertTrue(message, message.contains("418"))
    }
}
