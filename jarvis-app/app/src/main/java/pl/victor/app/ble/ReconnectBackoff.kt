package pl.victor.app.ble

/**
 * Odstępy między próbami powrotu do rozłączonych okularów.
 *
 * ## Dlaczego prób nie wolno policzyć
 * Poprzednia wersja próbowała dziesięć razy co sześć sekund i po minucie
 * poddawała się na dobre. Minuta wystarcza na chwilowe wyjście z zasięgu, ale
 * nie na nic więcej: kto odłożył okulary na godzinę albo wyszedł z nimi do
 * innego pokoju, wracał do martwej aplikacji i musiał łączyć się ręcznie.
 * Zgłoszone dwa razy - "rozłączanie okularów nie naprawiło się".
 *
 * Dlatego próby nie kończą się nigdy (dopóki użytkownik sam nie rozłączy), za to
 * rzedną: gęsto przez pierwszą minutę, kiedy szansa na powrót jest największa,
 * potem coraz rzadziej, aż do jednej próby na minutę. Skan BLE co minutę jest
 * dla baterii zaniedbywalny, a różnica dla użytkownika jest taka, że okulary
 * same wracają, zamiast czekać, aż ktoś o nich pomyśli.
 */
object ReconnectBackoff {

    /** Pierwsze próby - gęsto, bo tu wracają okulary po chwilowej utracie zasięgu. */
    const val FAST_DELAY_MS = 6_000L

    /** Ile prób idzie gęsto, zanim zaczniemy rzednąć. */
    const val FAST_ATTEMPTS = 10

    /** Górna granica - rzadziej niż raz na minutę już nie schodzimy. */
    const val MAX_DELAY_MS = 60_000L

    /**
     * @param attempt numer próby liczony od zera
     * @return ile czekać PRZED tą próbą
     */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt < FAST_ATTEMPTS) return FAST_DELAY_MS
        // Podwajamy od pierwszej wolnej próby: 12 s, 24 s, 48 s, potem sufit.
        val doublings = attempt - FAST_ATTEMPTS
        if (doublings >= 32) return MAX_DELAY_MS
        val scaled = FAST_DELAY_MS shl (doublings + 1)
        return if (scaled <= 0L || scaled > MAX_DELAY_MS) MAX_DELAY_MS else scaled
    }

    /**
     * Czy o tej próbie warto powiedzieć w logu.
     *
     * Bez tego log zapełnia się w kółko tym samym zdaniem: przy nieskończonych
     * próbach to setki linii na godzinę, w których giną prawdziwe zdarzenia.
     */
    fun shouldLog(attempt: Int): Boolean =
        attempt < FAST_ATTEMPTS || delayForAttempt(attempt) >= MAX_DELAY_MS &&
            (attempt - FAST_ATTEMPTS) % 10 == 0
}
