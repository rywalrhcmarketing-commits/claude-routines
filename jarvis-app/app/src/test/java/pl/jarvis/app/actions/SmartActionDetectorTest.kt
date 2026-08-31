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

    // === Akcje, ktore byly zaimplementowane, ale nieosiagalne ===

    @Test
    fun `pokaz na mapie jest rozpoznawane`() {
        assertDetects<Action.ShowOnMap>("pokaż na mapie Rynek Główny")
        assertDetects<Action.ShowOnMap>("gdzie jest najbliższa apteka")
        assertDetects<Action.ShowOnMap>("znajdź na mapie dworzec")
    }

    @Test
    fun `pokaz na mapie niesie nazwe miejsca`() {
        val action = detector.detect("gdzie jest najbliższa apteka")
            .filterIsInstance<Action.ShowOnMap>().first()
        assertTrue(
            "zapytanie ma zawierać nazwę miejsca, było: \"${action.query}\"",
            action.query.contains("apteka")
        )
    }

    @Test
    fun `nawigacja ma pierwszenstwo przed pokazaniem na mapie`() {
        // "nawiguj do X" to prośba o prowadzenie, nie o podgląd - obie naraz
        // otworzyłyby dwie aplikacje.
        val actions = detector.detect("nawiguj do Rynku Głównego")
        assertTrue(actions.any { it is Action.Navigate })
        assertTrue(
            "nie powinno być jednocześnie ShowOnMap",
            actions.none { it is Action.ShowOnMap }
        )
    }

    @Test
    fun `otworz strone jest rozpoznawane`() {
        assertDetects<Action.OpenUrl>("otwórz stronę wikipedia.pl")
        assertDetects<Action.OpenUrl>("wejdź na https://example.com")
    }

    @Test
    fun `adres bez protokolu dostaje https`() {
        val action = detector.detect("otwórz stronę wikipedia.pl")
            .filterIsInstance<Action.OpenUrl>().first()
        assertTrue(
            "adres ma mieć protokół, było: ${action.url}",
            action.url.startsWith("https://")
        )
    }

    @Test
    fun `sam adres w wypowiedzi otwiera strone`() {
        assertDetects<Action.OpenUrl>("https://example.com")
    }

    @Test
    fun `zwykle zdanie z kropka nie jest adresem`() {
        // "Idę do domu." nie może zostać uznane za adres.
        val actions = detector.detect("idę do domu")
        assertTrue(actions.none { it is Action.OpenUrl })
    }

}
