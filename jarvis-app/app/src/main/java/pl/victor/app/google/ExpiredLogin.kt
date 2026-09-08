package pl.victor.app.google

/**
 * Rozpoznaje, czy błąd wywołania Google API oznacza WYGASŁE LOGOWANIE.
 *
 * ## Po co osobna, czysta funkcja
 * Bo pomyłka jest kosztowna w obie strony, a sprawdzić da się to wyłącznie testem -
 * prawdziwego wygaśnięcia tokenu nie wywoła się na żądanie.
 *
 * ## Skąd ten problem
 * Aplikacja w trybie testowym Google unieważnia token odświeżania po SIEDMIU DNIACH.
 * Konto zostaje zalogowane LOKALNIE - `getLastSignedInAccount` czyta pamięć telefonu,
 * a nie pyta Google - więc aplikacja dalej uważa, że jest połączona, a każde wywołanie
 * API wraca błędem uwierzytelnienia.
 *
 * Do tej pory te błędy wpadały w te same `catch (e: Exception)`, co awarie sieci, i
 * kończyły się pustą listą. Skutek był gorszy niż błąd: karta konta pisała
 * "połączono", a asystent z pełnym przekonaniem odpowiadał, że nie masz nic w
 * kalendarzu. Fałszywa odpowiedź, nie komunikat o usterce.
 *
 * ## Dlaczego po nazwie klasy i treści, a nie po typie
 * Bo te wyjątki pochodzą z trzech różnych bibliotek (`UserRecoverableAuthException`
 * z usług Google Play, `TokenResponseException` z klienta OAuth, zwykły
 * `GoogleJsonResponseException` z warstwy HTTP), a prawdziwy powód bywa dopiero w
 * przyczynie przyczyny. Dopasowanie po nazwie łapie wszystkie trzy i nie zmusza tego
 * pliku do zależności od żadnej z nich.
 */
object ExpiredLogin {

    /**
     * Czy ten błąd to wygasłe albo cofnięte logowanie.
     *
     * @param error wyjątek z wywołania Google API
     * @return prawda, gdy trzeba zalogować się ponownie
     */
    fun looksExpired(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (matches(current)) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun matches(error: Throwable): Boolean {
        val name = error.javaClass.name
        if (CLASS_MARKERS.any { name.contains(it, ignoreCase = true) }) return true
        val message = error.message.orEmpty()
        if (message.isEmpty()) return false
        return MESSAGE_MARKERS.any { message.contains(it, ignoreCase = true) }
    }

    /**
     * Nazwy klas wyjątków, które znaczą "zaloguj się ponownie".
     *
     * `NeedPermission` i `UserRecoverableAuth` to sygnał z usług Google Play, że
     * zgoda przestała obowiązywać; `TokenResponseException` to odmowa serwera
     * tokenów przy próbie odświeżenia.
     */
    private val CLASS_MARKERS = listOf(
        "UserRecoverableAuth",
        "TokenResponseException",
        "GoogleAuthException"
    )

    /**
     * Fragmenty treści błędu.
     *
     * `invalid_grant` to dokładna odpowiedź serwera Google na token unieważniony po
     * siedmiu dniach. Świadomie NIE ma tu samego "403": zwykła odmowa uprawnień do
     * zasobu też jest 403, a to jest zupełnie inna sytuacja i inna rada dla
     * użytkownika. "401" zostaje, bo oznacza wyłącznie brak uwierzytelnienia.
     */
    private val MESSAGE_MARKERS = listOf(
        "invalid_grant",
        "invalid_token",
        "invalid_credentials",
        "Unauthorized",
        "401"
    )

    /** Ile poziomów przyczyn przeglądamy - prawdziwy powód bywa dwa, trzy w głąb. */
    private const val MAX_CAUSE_DEPTH = 6
}
