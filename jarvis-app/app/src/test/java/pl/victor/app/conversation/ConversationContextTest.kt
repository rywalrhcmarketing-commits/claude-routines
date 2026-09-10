package pl.victor.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {

    /** Zegar sterowany ręcznie - inaczej testu wieku nie da się napisać. */
    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advanceMinutes(minutes: Long) {
            now += minutes * 60_000L
        }
    }

    private fun context(clock: FakeClock, staleAfterMs: Long = ConversationContext.DEFAULT_STALE_AFTER_MS) =
        ConversationContext(staleAfterMs = staleAfterMs, clock = clock)

    @Test
    fun `swieza wymiana zostaje w kontekscie`() {
        val clock = FakeClock()
        val ctx = context(clock)
        ctx.addTurn("jaka jest pogoda", "słonecznie")
        clock.advanceMinutes(2)
        assertTrue(ctx.asContextString().contains("jaka jest pogoda"))
        assertEquals(1, ctx.size())
    }

    @Test
    fun `wymiana sprzed godziny wypada`() {
        val clock = FakeClock()
        val ctx = context(clock)
        ctx.addTurn("ile to jest 2 plus 2", "cztery")
        clock.advanceMinutes(60)
        assertEquals("", ctx.asContextString())
        assertEquals(0, ctx.size())
    }

    @Test
    fun `stara wymiana nie wraca jako lastTurn`() {
        val clock = FakeClock()
        val ctx = context(clock)
        ctx.addTurn("co to jest fotosynteza", "proces w roślinach")
        clock.advanceMinutes(30)
        assertNull(ctx.lastTurn())
    }

    @Test
    fun `rozmowa bez przerw zostaje w calosci`() {
        val clock = FakeClock()
        val ctx = context(clock)
        repeat(5) { i ->
            ctx.addTurn("pytanie $i", "odpowiedź $i")
            clock.advanceMinutes(2)
        }
        assertEquals(5, ctx.size())
    }

    @Test
    fun `po dlugiej przerwie zostaje tylko nowa wymiana`() {
        val clock = FakeClock()
        val ctx = context(clock)
        ctx.addTurn("stare pytanie", "stara odpowiedź")
        clock.advanceMinutes(45)
        ctx.addTurn("nowe pytanie", "nowa odpowiedź")

        val text = ctx.asContextString()
        assertTrue(text, "nowe pytanie" in text)
        assertTrue(text, "stare pytanie" !in text)
        assertEquals(1, ctx.size())
    }

    @Test
    fun `limit dziesieciu wymian dalej dziala`() {
        val clock = FakeClock()
        val ctx = context(clock)
        repeat(15) { ctx.addTurn("p$it", "o$it") }
        assertEquals(10, ctx.size())
        assertTrue("p0" !in ctx.asContextString())
        assertTrue("p14" in ctx.asContextString())
    }

    @Test
    fun `zero wylacza starzenie`() {
        val clock = FakeClock()
        val ctx = context(clock, staleAfterMs = 0L)
        ctx.addTurn("pytanie", "odpowiedź")
        clock.advanceMinutes(10_000)
        assertEquals(1, ctx.size())
    }

    @Test
    fun `pusty kontekst nie dokleja naglowka`() {
        val clock = FakeClock()
        val ctx = context(clock)
        ctx.addTurn("pytanie", "odpowiedź")
        clock.advanceMinutes(60)
        assertEquals("", ctx.asSystemContext())
    }

    @Test
    fun `domyslny prog to kwadrans`() {
        assertEquals(15 * 60 * 1000L, ConversationContext.DEFAULT_STALE_AFTER_MS)
    }

    @Test
    fun `tuz przed progiem wymiana jeszcze zyje`() {
        val clock = FakeClock()
        val ctx = context(clock)
        ctx.addTurn("pytanie", "odpowiedź")
        clock.now += ConversationContext.DEFAULT_STALE_AFTER_MS - 1
        assertEquals(1, ctx.size())
    }
}
