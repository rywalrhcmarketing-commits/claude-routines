package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Przeliczenie 48 kHz -> 16 kHz stoi na drodze CAŁEJ transkrypcji mowy z
 * okularów: rozpoznawanie na urządzeniu pracuje na 16 kHz. Błąd tutaj nie daje
 * błędu ani wyjątku - daje ciszę albo bełkot, czyli objaw nie do odróżnienia od
 * "mikrofon nie działa". Stąd te testy.
 */
class PcmResamplerTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samplesOf(pcm: ByteArray): List<Int> =
        (0 until pcm.size / 2).map { i ->
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            ((high shl 8) or low).toShort().toInt()
        }

    @Test
    fun `48 kHz skraca sie trzykrotnie`() {
        val input = pcm(*IntArray(300) { 1000 })
        val output = PcmResampler.resample(input, 48_000, 16_000)
        assertEquals(100, output.size / 2)
    }

    @Test
    fun `stala wartosc przechodzi bez zmiany`() {
        val input = pcm(*IntArray(30) { 1234 })
        val output = PcmResampler.resample(input, 48_000, 16_000)
        assertTrue(samplesOf(output).all { it == 1234 })
    }

    @Test
    fun `usrednianie tlumi szum o najwyzszej czestotliwosci`() {
        // Naprzemienne +8000/-8000 to sygnał o częstotliwości Nyquista -
        // dokładnie to, co przy zwykłym wyrzucaniu próbek wróciłoby w pasmo
        // mowy jako szum. Po uśrednieniu ma zostać prawie nic.
        val input = pcm(*IntArray(300) { if (it % 2 == 0) 8000 else -8000 })
        val output = PcmResampler.resample(input, 48_000, 16_000)
        assertTrue(samplesOf(output).all { abs(it) < 3000 })
    }

    @Test
    fun `ton mowy przezywa przeliczenie`() {
        // 500 Hz mieści się w paśmie mowy i po przeliczeniu musi zostać
        // sygnałem o porównywalnej amplitudzie, a nie ciszą.
        val input = pcm(*IntArray(4_800) { i ->
            (10_000 * sin(2 * PI * 500 * i / 48_000.0)).toInt()
        })
        val output = PcmResampler.resample(input, 48_000, 16_000)
        val peak = samplesOf(output).maxOf { abs(it) }
        assertTrue("szczyt=$peak", peak > 8_000)
    }

    @Test
    fun `ta sama czestotliwosc nie rusza danych`() {
        val input = pcm(1, 2, 3)
        assertSame(input, PcmResampler.resample(input, 16_000, 16_000))
    }

    @Test
    fun `puste wejscie nie wywraca sie`() {
        assertEquals(0, PcmResampler.resample(ByteArray(0), 48_000, 16_000).size)
    }

    @Test
    fun `niecalkowita krotnosc tez dziala`() {
        val input = pcm(*IntArray(441) { 500 })
        val output = PcmResampler.resample(input, 44_100, 16_000)
        assertEquals(160, output.size / 2)
        assertTrue(samplesOf(output).all { it == 500 })
    }

    @Test
    fun `bezsensowna czestotliwosc oddaje wejscie bez zmian`() {
        val input = pcm(7, 8)
        assertSame(input, PcmResampler.resample(input, 0, 16_000))
    }
}
