package pl.jarvis.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `pytanie o otoczenie nie otwiera map`() {
        // To jest ważne dla trybu dostępności: niewidomy użytkownik pytający
        // "gdzie jest wyjście?" pyta o to, co przed nim. Przekierowanie go
        // wtedy do Google Maps byłoby wprost szkodliwe.
        for (question in listOf(
            "gdzie jest wyjście",
            "gdzie jest klamka",
            "gdzie są schody",
            "gdzie jest przycisk"
        )) {
            val actions = detector.detect(question)
            assertTrue(
                "\"$question\" nie może otwierać map, wykryto: $actions",
                actions.none { it is Action.ShowOnMap }
            )
        }
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


    // === Polaczenia i SMS: numer telefonu vs nazwa kontaktu ===
    //
    // Wykryta wartosc "to" idzie pozniej do AIOrchestrator.resolveContactIfNeeded,
    // ktore rozstrzyga miedzy numerem a nazwa kontaktu z ksiazki adresowej. Zanim
    // to sie stanie, sam detektor musi wyciagnac CALY numer, a nie jego fragment.

    @Test
    fun `zadzwon do nazwy wyciaga cale slowo jako cel`() {
        val action = detector.detect("zadzwoń do mamy")
            .filterIsInstance<Action.MakeCall>().first()
        assertEquals("mamy", action.to)
    }

    @Test
    fun `zadzwon pod numer z odstepami wyciaga caly numer`() {
        // Wczesniej \S+ lapal tylko "123" z "123 456 789" - reszta numeru
        // ginela, a polaczenie szlo pod bledny, obciety numer.
        val action = detector.detect("zadzwoń pod 123 456 789")
            .filterIsInstance<Action.MakeCall>().first()
        assertTrue(
            "numer ma zawierac wszystkie cyfry, bylo: \"${action.to}\"",
            action.to.filter { it.isDigit() } == "123456789"
        )
    }

    @Test
    fun `zadzwon na numer z myslnikami dziala`() {
        val action = detector.detect("zadzwoń na 500-100-200")
            .filterIsInstance<Action.MakeCall>().first()
        assertTrue(action.to.filter { it.isDigit() } == "500100200")
    }

    @Test
    fun `wyslij sms do nazwy wyciaga nazwe i tresc osobno`() {
        val action = detector.detect("wyślij sms do Ani: cześć jak się masz")
            .filterIsInstance<Action.SendSms>().first()
        assertEquals("ani", action.to)
        assertTrue(action.body.contains("cześć"))
    }


    // === Alarm: pory dnia ===
    //
    // "poludnie" i "polnoc" maja wspolny prefiks "pol" - dopasowanie przez
    // startsWith() myli jedno z drugim (bylo tak wczesniej). Te testy pilnuja
    // dokladnego rozroznienia.

    @Test
    fun `alarm rano nie przesuwa godziny`() {
        val action = detector.detect("ustaw alarm na 7 rano")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(7, action.hour)
    }

    @Test
    fun `alarm wieczorem przesuwa godzine o 12`() {
        val action = detector.detect("ustaw alarm na 9 wieczór")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(21, action.hour)
    }

    @Test
    fun `alarm w poludnie przesuwa godzine o 12 gdy ponizej 12`() {
        val action = detector.detect("ustaw alarm na 1 południe")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(13, action.hour)
    }

    @Test
    fun `alarm o polnocy to godzina zero`() {
        // To byl martwy przypadek: zla precedencja || i && sprawiala, ze
        // KAZDA pora zaczynajaca sie na "po" (w tym polnoc) dostawala +12
        // bez wzgledu na strategnika, a wzorzec bez "l" nie lapal "polnoc" wcale.
        val action = detector.detect("ustaw alarm na 12 północ")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(0, action.hour)
    }

    @Test
    fun `alarm o polnocy dziala tez bez polskich znakow`() {
        val action = detector.detect("ustaw alarm na 12 polnoc")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(0, action.hour)
    }

    @Test
    fun `alarm z minutami zachowuje minuty`() {
        val action = detector.detect("ustaw alarm na 6:45 rano")
            .filterIsInstance<Action.SetAlarm>().first()
        assertEquals(6, action.hour)
        assertEquals(45, action.minute)
    }


    // === Latarka i Bluetooth: przelaczniki systemowe ===

    @Test
    fun `wlacz latarke z poprawnym e ogonkowym jest wykrywane`() {
        // Bylo martwe: regex mial "|" poza grupa, wiec ta pisownia (najbardziej
        // naturalna dla polskiego uzytkownika) nigdy sie nie dopasowywala.
        val action = detector.detect("włącz latarkę")
            .filterIsInstance<Action.ToggleFlashlight>().first()
        assertTrue(action.enabled)
    }

    @Test
    fun `wlacz latarke bez polskich znakow tez dziala`() {
        val action = detector.detect("włącz latarke")
            .filterIsInstance<Action.ToggleFlashlight>().first()
        assertTrue(action.enabled)
    }

    @Test
    fun `wylacz latarke z poprawnym e ogonkowym jest wykrywane`() {
        val action = detector.detect("wyłącz latarkę")
            .filterIsInstance<Action.ToggleFlashlight>().first()
        assertFalse(action.enabled)
    }

    @Test
    fun `wylacz wifi z poprawnym a ogonkowym jest wykrywane`() {
        // Trzeci wariant tego samego bledu: wzorzec "wy[lł]acz" mial literalne
        // "a" zamiast "ą" po [lł] - "wyłącz" (jedyna poprawna polska pisownia
        // z nosowym "ą") nigdy sie nie dopasowywal, tylko "wyłacz"/"wylacz"
        // bez diakrytykow. Ten blad byl w kodzie od poczatku, nie moj.
        val action = detector.detect("wyłącz wifi")
            .filterIsInstance<Action.ToggleWifi>().first()
        assertFalse(action.enabled)
    }

    @Test
    fun `wylacz bluetooth jest wykrywane`() {
        // Wczesniej byla tylko galaz "wlacz bluetooth" - wylaczenie
        // nigdy nie mialo jak zadzialac.
        val action = detector.detect("wyłącz bluetooth")
            .filterIsInstance<Action.ToggleBluetooth>().first()
        assertFalse(action.enabled)
    }

    @Test
    fun `wlacz bluetooth nadal dziala`() {
        val action = detector.detect("włącz bluetooth")
            .filterIsInstance<Action.ToggleBluetooth>().first()
        assertTrue(action.enabled)
    }

}
