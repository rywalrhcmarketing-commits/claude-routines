package pl.victor.app.ble

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Detektor akcji przycisku fizycznego na okularach.
 *
 * Mapuje eventy z ButtonEvent na konkretne akcje użytkownika:
 * - 1x kliknięcie  → QUICK_QUESTION (szybkie pytanie)
 * - 2x kliknięcie  → FOLLOW_UP (kontynuacja rozmowy)
 * - 3x kliknięcie  → SCAN_QR (skanuj QR z ostatniego zdjęcia)
 * - Przytrzymanie  → NEW_CONVERSATION (nowa rozmowa, reset historii)
 *
 * Detekcja: okno czasowe 500ms między kliknięciami.
 *
 * ## Dlaczego liczenie kliknięć idzie przez timer, nie przez osobne eventy
 * Prawdziwy sprzęt zgłasza przez BLE tylko JEDEN typ zdarzenia na wciśnięcie
 * przycisku (`ButtonEvent.ShortClick`) - `DoubleClick`/`TripleClick`/`LongPress`
 * nigdy nie przychodzą same z siebie z [pl.victor.app.ble.VictorManager]. Więc
 * to TA klasa musi zliczyć kolejne `ShortClick`i w oknie czasowym i po jego
 * upływie (bez kolejnego kliknięcia) zdecydować, co się wydarzyło - stąd
 * [flushJob]. Wcześniej ten timer nie istniał, więc żadne kliknięcie nigdy nie
 * kończyło się akcją.
 */
class ButtonActionDetector {

    private var lastClickTime: Long = 0
    private var clickCount: Int = 0
    private val CLICK_WINDOW_MS = 500L

    private val _action = MutableSharedFlow<ButtonAction>(replay = 0, extraBufferCapacity = 1)
    val action: SharedFlow<ButtonAction> = _action.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null

    /**
     * Przetwarza event z przycisku. Wywołaj z obserwatora buttonEvent w VictorManager.
     */
    fun processEvent(event: ButtonEvent) {
        when (event) {
            ButtonEvent.ShortClick -> handleClick()
            ButtonEvent.DoubleClick -> {
                flushJob?.cancel()
                clickCount = 2
                tryEmitAction(ButtonAction.FOLLOW_UP)
                reset()
            }
            ButtonEvent.TripleClick -> {
                flushJob?.cancel()
                clickCount = 3
                tryEmitAction(ButtonAction.SCAN_QR)
                reset()
            }
            ButtonEvent.LongPress -> {
                flushJob?.cancel()
                tryEmitAction(ButtonAction.NEW_CONVERSATION)
                reset()
            }
            ButtonEvent.Release -> {
                // Nic nie rób - obsłużone w LongPress
            }
        }
    }

    /**
     * Obsługa pojedynczego kliku - liczy go i (re)startuje okno oczekiwania
     * na kolejny. Jeśli kolejny klik nie przyjdzie w [CLICK_WINDOW_MS], zlicza
     * się to co już mamy - patrz [flushPendingClick].
     */
    private fun handleClick() {
        val now = SystemClock.elapsedRealtime()

        if (now - lastClickTime > CLICK_WINDOW_MS) {
            // Nowa sekwencja kliknięć
            clickCount = 1
        } else {
            clickCount++
        }

        lastClickTime = now

        flushJob?.cancel()
        flushJob = scope.launch {
            delay(CLICK_WINDOW_MS)
            flushPendingClick()
        }
    }

    /**
     * Zamyka okno liczenia kliknięć i emituje akcję odpowiadającą ich liczbie.
     * Woła się sama po [CLICK_WINDOW_MS] od ostatniego kliknięcia.
     */
    fun flushPendingClick() {
        when {
            clickCount == 1 -> tryEmitAction(ButtonAction.QUICK_QUESTION)
            clickCount == 2 -> tryEmitAction(ButtonAction.FOLLOW_UP)
            clickCount >= 3 -> tryEmitAction(ButtonAction.SCAN_QR)
        }
        reset()
    }

    private fun tryEmitAction(action: ButtonAction) {
        _action.tryEmit(action)
    }

    private fun reset() {
        clickCount = 0
        lastClickTime = 0
        flushJob = null
    }
}

/**
 * Akcje użytkownika wykryte przez analizę przycisku.
 */
sealed class ButtonAction {
    object QUICK_QUESTION : ButtonAction()
    object FOLLOW_UP : ButtonAction()
    object SCAN_QR : ButtonAction()
    object NEW_CONVERSATION : ButtonAction()
}
