package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Głośność jest tu narzędziem diagnozy, nie ozdobą: rozróżnia trzy stany, które
 * w raporcie z pomiaru wyglądają identycznie - prawdziwą mowę, ciszę (dekoder
 * oddał same zera) i szum po złym ramkowaniu (wartości przypadkowe, więc
 * energia bliska maksimum).
 */
class PcmPlayerLoudnessTest {

    private fun pcmOf(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `cisza ma glosnosc zero`() {
        assertEquals(0.0, PcmPlayer.loudness(ByteArray(2_000)), 0.0001)
    }

    @Test
    fun `puste nagranie nie dzieli przez zero`() {
        assertEquals(0.0, PcmPlayer.loudness(ByteArray(0)), 0.0001)
    }

    @Test
    fun `mowa lezy miedzy progami`() {
        // Sinus o umiarkowanej amplitudzie - RMS wychodzi ~0,21.
        val samples = IntArray(4_800) { i -> (9_000 * sin(2 * PI * 300 * i / 48_000.0)).toInt() }
        val loudness = PcmPlayer.loudness(pcmOf(samples))
        assertTrue("glosnosc=$loudness", loudness > 0.01 && loudness < 0.45)
    }

    @Test
    fun `szum na pelnej skali przekracza gorny prog`() {
        // Naprzemienne skrajne wartości - tak brzmi źle poskładany strumień.
        val samples = IntArray(2_000) { if (it % 2 == 0) 32_000 else -32_000 }
        assertTrue(PcmPlayer.loudness(pcmOf(samples)) > 0.45)
    }

    @Test
    fun `bardzo cichy sygnal nie przekracza dolnego progu`() {
        val samples = IntArray(2_000) { if (it % 2 == 0) 100 else -100 }
        assertTrue(PcmPlayer.loudness(pcmOf(samples)) < 0.01)
    }

    @Test
    fun `wartosci ujemne licza sie tak samo jak dodatnie`() {
        val positive = PcmPlayer.loudness(pcmOf(IntArray(100) { 5_000 }))
        val negative = PcmPlayer.loudness(pcmOf(IntArray(100) { -5_000 }))
        assertEquals(positive, negative, 0.0001)
    }

    @Test
    fun `nieparzysta liczba bajtow nie wywraca sie`() {
        assertEquals(0.0, PcmPlayer.loudness(ByteArray(1)), 0.0001)
    }
}
