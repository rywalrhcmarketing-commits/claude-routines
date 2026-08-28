package pl.jarvis.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy rozpoznawania komend głosowych.
 *
 * Wzorce w detektorze były zapisane jako zwykłe napisy przekazywane do
 * `String.matches()`, co w Kotlinie w ogóle się nie kompiluje - musiały
 * zostać opakowane w `Regex`. Te testy pilnują, żeby po tej zmianie
 * dopasowania nadal działały, łącznie z polskimi znakami.
 */
class SmartActionDetectorTest {

    private val detector = SmartActionDetector()

    private inline fun <reified T : Action> assertDetects(text: String) {
        val actions = detector.detect(text)
        assertTrue(
            "Nie rozpoznano ${T::class.simpleName} w \"$text\", wykryto: " +
                actions.map { it::class.simpleName },
            actions.any { it is T }
        )
    }

    // === Sterowanie muzyką ===

    @Test
    fun `rozpoznaje pauze`() {
        assertDetects<Action.TogglePlayPause>("pauza")
        assertDetects<Action.TogglePlayPause>("zatrzymaj muzykę")
        assertDetects<Action.TogglePlayPause>("wstrzymaj")
    }

    @Test
    fun `rozpoznaje wznowienie`() {
        assertDetects<Action.TogglePlayPause>("wznów")
        assertDetects<Action.TogglePlayPause>("kontynuuj")
    }

    @Test
    fun `wznow dziala tez bez polskiego ogonka`() {
        // Transkrypcja mowy gubi diakrytyki - wzorzec musi łapać oba warianty.
        assertDetects<Action.TogglePlayPause>("wznow")
    }

    @Test
    fun `rozpoznaje nastepny utwor`() {
        val actions = detector.detect("następna")
        val skip = actions.filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertTrue("Nie wykryto SkipTrack", skip != null)
        assertEquals(SkipDirection.NEXT, skip!!.direction)
    }

    @Test
    fun `nastepna dziala bez ogonkow`() {
        val skip = detector.detect("nastepna").filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertEquals(SkipDirection.NEXT, skip?.direction)
    }

    @Test
    fun `rozpoznaje poprzedni utwor`() {
        val skip = detector.detect("poprzednia").filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertEquals(SkipDirection.PREVIOUS, skip?.direction)
    }

    @Test
    fun `rozpoznaje angielskie warianty`() {
        assertDetects<Action.TogglePlayPause>("stop")
        val skip = detector.detect("skip").filterIsInstance<Action.SkipTrack>().firstOrNull()
        assertEquals(SkipDirection.NEXT, skip?.direction)
    }

    // === Brak fałszywych trafień ===

    @Test
    fun `zwykle pytanie nie jest komenda`() {
        // "co widzisz przede mną" celowo NIE jest tu użyte - to prawidłowa
        // komenda opisu sceny w trybie dostępności.
        val actions = detector.detect("jaka jest stolica Francji")
        assertTrue(
            "Zwykłe pytanie zostało wzięte za komendę: ${actions.map { it::class.simpleName }}",
            actions.isEmpty()
        )
    }

    @Test
    fun `pytanie o otoczenie jest komenda opisu sceny`() {
        assertDetects<Action.DescribeScene>("co widzisz przede mną")
    }

    @Test
    fun `pusty tekst nie generuje akcji`() {
        assertTrue(detector.detect("").isEmpty())
        assertTrue(detector.detect("   ").isEmpty())
    }

    @Test
    fun `wielkosc liter nie ma znaczenia`() {
        assertDetects<Action.TogglePlayPause>("PAUZA")
        assertDetects<Action.TogglePlayPause>("Pauza")
    }

    @Test
    fun `komenda w srodku zdania jest rozpoznawana`() {
        // Wzorce mają .* po obu stronach, więc muszą łapać także w środku.
        assertDetects<Action.TogglePlayPause>("słuchaj, zatrzymaj muzykę na chwilę")
    }
}
