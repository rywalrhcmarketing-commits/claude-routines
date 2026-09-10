package pl.victor.app.ai

/**
 * Zamienia błąd dostawcy AI na jedno zdanie, które da się powiedzieć na głos.
 *
 * ## Po co osobna klasa
 * Bo to jedyna informacja, jaką użytkownik w okularach dostanie o nieudanej
 * turze. Błąd lądował wcześniej wyłącznie na ekranie telefonu - a ktoś z
 * telefonem w kieszeni widział wtedy dokładnie to samo, co przy awarii:
 * ciszę. Zgłoszone jako "często jest brak odpowiedzi", przy opłaconych kontach
 * DeepSeeka i Gemini. Odpowiedź była, tylko nie po polsku i nie na głos.
 *
 * Rozpoznajemy tylko te powody, po których użytkownik może COŚ ZROBIĆ: brak
 * środków, limit zapytań, zły klucz, brak sieci. Reszta dostaje zdanie ogólne,
 * bo czytanie na głos treści odpowiedzi HTTP nikomu nie pomoże.
 *
 * Dopasowanie idzie po fragmentach, nie po całych komunikatach: każdy dostawca
 * pisze je inaczej, a kod stanu i angielskie słowo kluczowe są tym, co się
 * powtarza.
 */
object ProviderFailure {

    /**
     * @param message treść wyjątku dostawcy (bywa `null`)
     * @param retryable czy dostawca sam uznał błąd za przejściowy
     */
    fun describe(message: String?, retryable: Boolean = false): String {
        val text = (message ?: "").lowercase()
        return when {
            NO_FUNDS.any { it in text } ->
                "Dostawca AI odmówił: konto nie ma środków albo przekroczyło limit. " +
                    "Sprawdź to w ustawieniach."
            RATE_LIMITED.any { it in text } ->
                "Za dużo zapytań naraz - dostawca kazał chwilę odczekać. Spróbuj za moment."
            BAD_KEY.any { it in text } ->
                "Klucz API nie został przyjęty. Sprawdź go w ustawieniach."
            NO_NETWORK.any { it in text } ->
                "Nie mam połączenia z siecią, więc nie zapytam modelu."
            retryable -> "Model nie odpowiedział. Spróbuj jeszcze raz."
            else -> "Model odmówił odpowiedzi. Sprawdź ustawienia dostawcy AI."
        }
    }

    // Kolejność list ma znaczenie tylko tyle, ile kolejność gałęzi wyżej:
    // "insufficient balance" DeepSeeka niesie czasem i 402, i 429 w tej samej
    // odpowiedzi, a brak środków jest wtedy prawdziwszym powodem niż limit.
    private val NO_FUNDS = listOf(
        "402", "insufficient", "balance", "quota", "billing", "exceeded your current"
    )
    private val RATE_LIMITED = listOf("429", "rate limit", "rate_limit", "too many requests")
    private val BAD_KEY = listOf(
        "401", "403", "api key", "api_key", "unauthorized", "invalid_api_key",
        "permission_denied"
    )
    private val NO_NETWORK = listOf(
        "timeout", "timed out", "unable to resolve host", "failed to connect",
        "network is unreachable", "no address associated", "connection reset"
    )
}
