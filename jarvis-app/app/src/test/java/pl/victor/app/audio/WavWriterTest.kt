package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Nagłówek WAV to 44 bajty, w których łatwo pomylić się o cztery - a skutek
 * jest taki, że model po drugiej stronie słyszy szum zamiast pytania i nikt
 * nie wie dlaczego. Stąd te testy: sprawdzają dokładnie te pola, które da się
 * pomylić, bo wyliczają się z innych.
 */
class WavWriterTest {

    private fun le32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun le16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    @Test
    fun `naglowek ma dokladnie 44 bajty przed probkami`() {
        val pcm = ByteArray(1000)
        val wav = WavWriter.wrap(pcm, 48_000)
        assertEquals(44 + pcm.size, wav.size)
    }

    @Test
    fun `pole RIFF liczy wszystko po sobie`() {
        val pcm = ByteArray(1000)
        val wav = WavWriter.wrap(pcm, 48_000)
        assertEquals("RIFF", ascii(wav, 0, 4))
        // 36 + dane - to jest dokładnie ta liczba, o którą najłatwiej się pomylić.
        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
    }

    @Test
    fun `sekcja fmt opisuje PCM 16 bit`() {
        val wav = WavWriter.wrap(ByteArray(10), 48_000, channels = 1)
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16, le32(wav, 16))       // rozmiar sekcji
        assertEquals(1, le16(wav, 20))        // format PCM
        assertEquals(1, le16(wav, 22))        // kanały
        assertEquals(48_000, le32(wav, 24))   // częstotliwość
        assertEquals(96_000, le32(wav, 28))   // byte rate = 48000 * 1 * 2
        assertEquals(2, le16(wav, 32))        // block align
        assertEquals(16, le16(wav, 34))       // bity na próbkę
    }

    @Test
    fun `byte rate i block align rosna z liczba kanalow`() {
        val wav = WavWriter.wrap(ByteArray(10), 48_000, channels = 2)
        assertEquals(2, le16(wav, 22))
        assertEquals(192_000, le32(wav, 28))
        assertEquals(4, le16(wav, 32))
    }

    @Test
    fun `sekcja data podaje rozmiar probek`() {
        val pcm = ByteArray(512) { it.toByte() }
        val wav = WavWriter.wrap(pcm, 16_000)
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(pcm.size, le32(wav, 40))
        // Próbki muszą trafić do pliku nietknięte.
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
    }

    @Test
    fun `czas trwania liczy sie z rozmiaru`() {
        // 48000 Hz, mono, 16 bit = 96000 bajtów na sekundę.
        assertEquals(1.0, WavWriter.durationSeconds(96_000, 48_000), 0.0001)
        assertEquals(0.5, WavWriter.durationSeconds(48_000, 48_000), 0.0001)
        assertEquals(1.0, WavWriter.durationSeconds(192_000, 48_000, channels = 2), 0.0001)
    }

    @Test
    fun `puste PCM daje sam naglowek`() {
        val wav = WavWriter.wrap(ByteArray(0), 48_000)
        assertEquals(44, wav.size)
        assertEquals(36, le32(wav, 4))
        assertEquals(0, le32(wav, 40))
    }
}
