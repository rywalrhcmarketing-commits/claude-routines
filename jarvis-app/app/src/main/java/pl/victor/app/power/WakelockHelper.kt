package pl.victor.app.power

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Blokada uśpienia procesora na czas pracy, która musi się skończyć.
 *
 * Usługa pierwszoplanowa trzyma proces przy życiu, ale NIE trzyma procesora: przy
 * zgaszonym ekranie rdzeń potrafi przysnąć między pakietem BLE a odpowiedzią z sieci.
 * Stąd ta klasa.
 *
 * ## Dlaczego jest tu zliczanie, a nie jedno gniazdo
 *
 * Poprzednia wersja trzymała JEDNĄ blokadę i miała `release()` bez argumentu. Przy
 * dwóch nakładających się właścicielach dawało to cichy błąd, i to dokładnie na
 * najważniejszej ścieżce:
 *
 * 1. nasłuch bierze blokadę,
 * 2. rozpoznana wypowiedź startuje turę, a tura bierze blokadę - czyli zwalnia cudzą
 *    i zakłada własną,
 * 3. nasłuch kończy swój blok `finally` i woła `release()` - zwalniając blokadę TURY.
 *
 * Od tego momentu model, zdjęcie i odczytanie odpowiedzi lecą bez żadnej blokady.
 * Przy odblokowanym telefonie nie widać tego wcale, bo ekran i tak trzyma procesor.
 * Przy zablokowanym - tura potrafi utknąć w połowie.
 *
 * Teraz każdy właściciel podaje swoją nazwę i oddaje dokładnie swoją blokadę.
 * Fizyczna blokada systemowa schodzi dopiero, gdy zwolni ją OSTATNI właściciel.
 */
class WakelockHelper(context: Context) {

    private val tag = "WakelockHelper"
    private val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** Kto w tej chwili potrzebuje procesora. Pusty zbiór = blokady nie ma. */
    private val holders = LinkedHashSet<String>()
    private var lock: PowerManager.WakeLock? = null
    private var acquiredAt: Long = 0L

    /** Kiedy najpóźniej system zwolni blokadę sam. Zero, gdy blokady nie ma. */
    private var deadline: Long = 0L

    /**
     * Bierze blokadę w imieniu [reason].
     *
     * Wołanie dwa razy pod tą samą nazwą nie tworzy drugiej blokady - odnawia tylko
     * bezpiecznik czasowy.
     *
     * @param maxDurationMs bezpiecznik: po tym czasie system zwolni blokadę sam,
     *   nawet gdyby [release] nie doszło. Ma być DŁUŻSZY niż najdłuższa uczciwa
     *   praca, bo inaczej ucina ją w połowie - patrz [DEFAULT_MAX_MS].
     * @return czy blokada jest trzymana
     */
    @Synchronized
    fun acquire(reason: String, maxDurationMs: Long = DEFAULT_MAX_MS): Boolean {
        holders += reason
        return try {
            val existing = lock ?: pm.newWakeLock(PARTIAL, "V.I.C.T.O.R.:praca").also {
                it.setReferenceCounted(false)
                lock = it
            }
            // Ponowne acquire na trzymanej blokadzie odnawia bezpiecznik. Bierzemy
            // z tego wyłącznie PRZEDŁUŻENIE: właściciel z krótszym bezpiecznikiem
            // nie może skrócić czasu temu, który potrzebuje dłużej. Inaczej krótki
            // nasłuch dołączający do trwającej tury ucinałby jej blokadę do swojego
            // limitu - czyli dokładnie ten błąd, który ta klasa ma wykluczać.
            val now = System.currentTimeMillis()
            val wanted = now + maxDurationMs
            if (wanted > deadline) {
                existing.acquire(maxDurationMs)
                deadline = wanted
            }
            if (acquiredAt == 0L) acquiredAt = now
            Log.d(tag, "Blokada wzięta przez \"$reason\" (trzymają: ${holders.joinToString()})")
            true
        } catch (e: Exception) {
            Log.e(tag, "Nie udało się wziąć blokady uśpienia", e)
            holders -= reason
            false
        }
    }

    /**
     * Oddaje blokadę wziętą przez [reason].
     *
     * Nazwa nieznana (bo ten właściciel już oddał albo nigdy nie brał) nie robi nic -
     * w szczególności NIE rusza blokad innych właścicieli.
     */
    @Synchronized
    fun release(reason: String) {
        if (!holders.remove(reason)) return
        if (holders.isNotEmpty()) {
            Log.d(tag, "\"$reason\" oddał, ale trzymają jeszcze: ${holders.joinToString()}")
            return
        }
        val held = lock ?: return
        if (held.isHeld) {
            Log.d(tag, "Blokada zwolniona po ${System.currentTimeMillis() - acquiredAt} ms")
            runCatching { held.release() }
        }
        lock = null
        acquiredAt = 0L
        deadline = 0L
    }

    /** Ile czasu blokada jest trzymana; zero, gdy nikt jej nie trzyma. */
    @Synchronized
    fun heldDurationMs(): Long =
        if (acquiredAt == 0L) 0L else System.currentTimeMillis() - acquiredAt

    /** Bierze blokadę na czas bloku i oddaje ją niezależnie od tego, jak blok się skończy. */
    inline fun <T> withWakeLock(
        reason: String,
        maxDurationMs: Long = DEFAULT_MAX_MS,
        block: () -> T
    ): T {
        acquire(reason, maxDurationMs)
        try {
            return block()
        } finally {
            release(reason)
        }
    }

    companion object {
        private const val PARTIAL = PowerManager.PARTIAL_WAKE_LOCK

        /**
         * Bezpiecznik czasowy, gdy właściciel go nie poda.
         *
         * Cztery minuty, nie dziesięć sekund jak w pierwszej wersji. Bezpiecznik ma
         * ratować przed zapomnianym `release`, a nie ucinać pracę: pełna tura to
         * zdjęcie przez Wi-Fi, kilka kontekstów, odpowiedź modelu i odczytanie jej na
         * głos. Wartość jest dobrana POWYŻEJ progu, przy którym watchdog uznaje turę
         * za zablokowaną i tak ją kończy - więc blokada nigdy nie schodzi pierwsza.
         */
        const val DEFAULT_MAX_MS = 240_000L
    }
}
