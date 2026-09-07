package pl.victor.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiedy wolno urwać nasłuch.
 *
 * To jest jedyne miejsce, w którym aplikacja decyduje, że użytkownik skończył
 * mówić - a od czasu, gdy sami wysyłamy okularom komendę końca sesji, pomyłka
 * tutaj NAPRAWDĘ ucina pytanie w pół słowa. Testy pilnują więc głównie tego,
 * czego robić NIE wolno.
 */
class SpeechEndTest {

    private fun ends(
        voiced: Long,
        sinceLast: Long,
        sinceStart: Long
    ) = SpeechEnd.endsOnSilence(
        voicedMs = voiced,
        sinceLastPacketMs = sinceLast,
        sinceStartMs = sinceStart
    )

    @Test
    fun `urywek na starcie nie konczy nasluchu`() {
        // Dokładnie zgłoszona sytuacja: 0,3 s dźwięku i sekunda ciszy, zanim
        // użytkownik w ogóle zaczął mówić.
        assertFalse(ends(voiced = 300, sinceLast = 1_500, sinceStart = 2_000))
    }

    @Test
    fun `sama cisza bez mowy nie konczy nasluchu`() {
        assertFalse(ends(voiced = 0, sinceLast = 5_000, sinceStart = 6_000))
    }

    @Test
    fun `przed uplywem okna startowego cisza nic nie znaczy`() {
        // Nawet gdy mowy było dużo: w pierwszych sekundach leci dźwięk
        // wybudzenia i negocjacja łącza, a przerwa po nich to nie koniec zdania.
        assertFalse(ends(voiced = 3_000, sinceLast = 2_000, sinceStart = 2_400))
    }

    @Test
    fun `brak pakietow to nie cisza`() {
        // Gdy okulary nie nadają w ogóle, o końcu tury decyduje rozpoznawanie
        // mowy - ta funkcja ma wtedy milczeć, a nie zgadywać.
        assertFalse(ends(voiced = 0, sinceLast = -1, sinceStart = 30_000))
    }

    @Test
    fun `sekunda mowy i cisza konczy nasluch`() {
        assertTrue(ends(voiced = 1_200, sinceLast = 1_300, sinceStart = 5_000))
    }

    @Test
    fun `krotka przerwa w mowie nie konczy nasluchu`() {
        // Oddech w połowie zdania jest krótszy niż próg ciszy.
        assertFalse(ends(voiced = 2_000, sinceLast = 400, sinceStart = 5_000))
    }

    @Test
    fun `dlugi odstep nie liczy sie jako mowa`() {
        // Przerwa między dźwiękiem wybudzenia a pytaniem to cisza - wliczenie
        // jej do "mowy" przepuszczałoby próg bez jednego wypowiedzianego słowa.
        assertTrue(SpeechEnd.voicedGap(20) == 20L)
        assertTrue(SpeechEnd.voicedGap(2_000) == 0L)
    }

    @Test
    fun `prog mowy liczy sie w milisekundach a nie w pakietach`() {
        // 15 pakietów po 20 ms to 0,3 s - stary próg. Ma NIE wystarczać.
        assertFalse(ends(voiced = 15 * 20L, sinceLast = 2_000, sinceStart = 5_000))
        assertTrue(ends(voiced = 60 * 20L, sinceLast = 2_000, sinceStart = 5_000))
    }
}
