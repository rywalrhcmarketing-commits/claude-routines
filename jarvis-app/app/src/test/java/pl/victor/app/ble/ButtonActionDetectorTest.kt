package pl.victor.app.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gesty na okularach to jedyny sposób sterowania V.I.C.T.O.R.-em bez telefonu,
 * a każdy z nich znaczy co innego. Te testy pilnują mapy gestów - przede
 * wszystkim tego, że podwójne kliknięcie ROBI ZDJĘCIE.
 *
 * To nie jest test dla samego testu: gdy pojedyncze kliknięcie przestało robić
 * zdjęcie, a zaczęło słuchać, aparatu nie wywoływał już żaden gest - i wyglądało
 * to dokładnie tak, jak zgłoszono: "zdjęć jakby w ogóle nie robi".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ButtonActionDetectorTest {

    /** Okno zliczania kliknięć w detektorze to 500 ms - czekamy z zapasem. */
    private val afterClickWindowMs = 900L

    private fun actionFor(clicks: Int): ButtonAction = runBlocking {
        val detector = ButtonActionDetector()
        val awaited = async { detector.action.first() }
        // Subskrypcja flow musi zdążyć wystartować, zanim polecą kliknięcia -
        // SharedFlow bez replay nie odda zdarzenia sprzed subskrypcji.
        delay(50)
        repeat(clicks) { detector.processEvent(ButtonEvent.ShortClick) }
        delay(afterClickWindowMs)
        awaited.await()
    }

    @Test
    fun `jedno klikniecie pyta glosem`() {
        assertEquals(ButtonAction.QUICK_QUESTION, actionFor(1))
    }

    @Test
    fun `dwa klikniecia robia zdjecie i opisuja widok`() {
        assertEquals(ButtonAction.LOOK_AND_DESCRIBE, actionFor(2))
    }

    @Test
    fun `trzy klikniecia skanuja kod`() {
        assertEquals(ButtonAction.SCAN_QR, actionFor(3))
    }

    @Test
    fun `gotowe zdarzenie podwojnego klikniecia tez robi zdjecie`() = runBlocking {
        val detector = ButtonActionDetector()
        val awaited = async { detector.action.first() }
        delay(50)
        detector.processEvent(ButtonEvent.DoubleClick)
        assertEquals(ButtonAction.LOOK_AND_DESCRIBE, awaited.await())
    }

    @Test
    fun `przytrzymanie zaczyna nowa rozmowe`() = runBlocking {
        val detector = ButtonActionDetector()
        val awaited = async { detector.action.first() }
        delay(50)
        detector.processEvent(ButtonEvent.LongPress)
        assertEquals(ButtonAction.NEW_CONVERSATION, awaited.await())
    }
}
