package pl.victor.app.ble

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 */
class ButtonActionDetector {

    private var lastClickTime: Long = 0
    private var clickCount: Int = 0
    private val CLICK_WINDOW_MS = 500L

    private val _action = MutableSharedFlow<ButtonAction>(replay = 0, extraBufferCapacity = 1)
    val action: SharedFlow<ButtonAction> = _action.asSharedFlow()

    /**
     * Przetwarza event z przycisku. Wywołaj z obserwatora buttonEvent w VictorManager.
     */
    fun processEvent(event: ButtonEvent) {
        when (event) {
            ButtonEvent.ShortClick -> handleClick()
            ButtonEvent.DoubleClick -> {
                clickCount = 2
                tryEmitAction(ButtonAction.FOLLOW_UP)
                reset()
            }
            ButtonEvent.TripleClick -> {
                clickCount = 3
                tryEmitAction(ButtonAction.SCAN_QR)
                reset()
            }
            ButtonEvent.LongPress -> {
                tryEmitAction(ButtonAction.NEW_CONVERSATION)
                reset()
            }
            ButtonEvent.Release -> {
                // Nic nie rób - obsłużone w LongPress
            }
        }
    }

    /**
     * Obsługa pojedynczego kliku - czeka na kolejne kliki w oknie czasowym.
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

        // Czekaj na kolejne kliki przed emitowaniem akcji
        // (jeśli po tym kliku nie przyjdzie kolejny w oknie, emit QUICK_QUESTION)
        // Implementujemy to przez timeout w processEvent z opóźnieniem
    }

    /**
     * Wywołaj po upływie okna czasowego, żeby potwierdzić akcję dla
     * pojedynczego kliku (jeśli nie przyszedł kolejny).
     */
    fun flushPendingClick() {
        if (clickCount == 1) {
            tryEmitAction(ButtonAction.QUICK_QUESTION)
        }
        reset()
    }

    private fun tryEmitAction(action: ButtonAction) {
        _action.tryEmit(action)
    }

    private fun reset() {
        clickCount = 0
        lastClickTime = 0
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
