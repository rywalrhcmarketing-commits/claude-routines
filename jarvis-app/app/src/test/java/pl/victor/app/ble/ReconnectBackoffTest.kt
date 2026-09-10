package pl.victor.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun `pierwsze proby ida gesto`() {
        for (attempt in 0 until ReconnectBackoff.FAST_ATTEMPTS) {
            assertEquals(ReconnectBackoff.FAST_DELAY_MS, ReconnectBackoff.delayForAttempt(attempt))
        }
    }

    @Test
    fun `po gestych probach odstep rosnie`() {
        val first = ReconnectBackoff.delayForAttempt(ReconnectBackoff.FAST_ATTEMPTS)
        val second = ReconnectBackoff.delayForAttempt(ReconnectBackoff.FAST_ATTEMPTS + 1)
        assertTrue("$first < $second", first < second)
        assertTrue(first > ReconnectBackoff.FAST_DELAY_MS)
    }

    @Test
    fun `odstep nigdy nie przekracza sufitu`() {
        for (attempt in 0..10_000) {
            val delay = ReconnectBackoff.delayForAttempt(attempt)
            assertTrue("próba $attempt dała $delay", delay <= ReconnectBackoff.MAX_DELAY_MS)
            assertTrue("próba $attempt dała $delay", delay > 0)
        }
    }

    @Test
    fun `odstep nigdy nie maleje`() {
        var previous = 0L
        for (attempt in 0..200) {
            val delay = ReconnectBackoff.delayForAttempt(attempt)
            assertTrue("próba $attempt: $delay < $previous", delay >= previous)
            previous = delay
        }
    }

    @Test
    fun `sufit osiagany jest szybko`() {
        // Kilkanaście prób ma wystarczyć, żeby zejść do jednej na minutę -
        // inaczej pierwsza godzina rozłączenia to i tak dziesiątki skanów.
        assertEquals(
            ReconnectBackoff.MAX_DELAY_MS,
            ReconnectBackoff.delayForAttempt(ReconnectBackoff.FAST_ATTEMPTS + 5)
        )
    }

    @Test
    fun `pierwsza godzina to mniej niz sto prob`() {
        var elapsed = 0L
        var attempts = 0
        while (elapsed < 3_600_000L) {
            elapsed += ReconnectBackoff.delayForAttempt(attempts)
            attempts++
        }
        assertTrue("prób w godzinę: $attempts", attempts < 100)
    }

    @Test
    fun `log nie zalewa przy dlugim rozlaczeniu`() {
        val logged = (0..200).count { ReconnectBackoff.shouldLog(it) }
        assertTrue("linii logu: $logged", logged < 40)
        assertTrue(ReconnectBackoff.shouldLog(0))
    }
}
