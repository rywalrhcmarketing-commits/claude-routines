package pl.victor.app.google

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pomyłka boli w obie strony: nierozpoznane wygaśnięcie to fałszywa odpowiedź
 * ("nie masz nic w kalendarzu"), a nadgorliwość to wylogowywanie użytkownika
 * przy zwykłej awarii sieci.
 */
class ExpiredLoginTest {

    private class TokenResponseException(message: String) : Exception(message)
    private class UserRecoverableAuthException(message: String) : Exception(message)

    @Test
    fun `odpowiedz serwera o uniewaznionym tokenie`() {
        assertTrue(ExpiredLogin.looksExpired(Exception("400 Bad Request: invalid_grant")))
    }

    @Test
    fun `wyjatek uslug Google Play po nazwie klasy`() {
        assertTrue(ExpiredLogin.looksExpired(UserRecoverableAuthException("NeedPermission")))
        assertTrue(ExpiredLogin.looksExpired(TokenResponseException("cokolwiek")))
    }

    @Test
    fun `powod schowany w przyczynie`() {
        val deep = Exception(
            "Nie udało się pobrać wydarzeń",
            Exception("warstwa HTTP", Exception("401 Unauthorized"))
        )
        assertTrue(ExpiredLogin.looksExpired(deep))
    }

    @Test
    fun `awaria sieci to NIE jest wygasle logowanie`() {
        listOf(
            "Unable to resolve host \"www.googleapis.com\"",
            "timeout",
            "Failed to connect to /142.250.0.1:443",
            "500 Internal Server Error"
        ).forEach { message ->
            assertFalse(message, ExpiredLogin.looksExpired(Exception(message)))
        }
    }

    @Test
    fun `403 to za malo - odmowa dostepu do zasobu to inna sprawa`() {
        // 403 dostaje też konto bez zgody na dany zakres. Wylogowanie go w tej
        // sytuacji byłoby złą radą: zgody trzeba dodać, a nie logować się od nowa.
        assertFalse(ExpiredLogin.looksExpired(Exception("403 Forbidden: insufficient permissions")))
    }

    @Test
    fun `brak bledu to brak wygasniecia`() {
        assertFalse(ExpiredLogin.looksExpired(null))
    }

    @Test
    fun `zagniezdzone przyczyny nie zawieszaja`() {
        val a = Exception("a")
        val b = Exception("b", a)
        assertFalse(ExpiredLogin.looksExpired(b))
    }
}
