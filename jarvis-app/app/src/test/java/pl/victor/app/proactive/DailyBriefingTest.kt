package pl.victor.app.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Briefing to jedyna funkcja, która odzywa się bez powodu w danych. Dlatego
 * najważniejsze jest tu NIE to, że mówi, tylko że da się go uciszyć i że nie
 * budzi, gdy nie ma nic do powiedzenia.
 */
class DailyBriefingTest {

    private val full = DailyBriefing.Material(
        calendar = "=== KALENDARZ === spotkanie 10:00",
        weather = "=== POGODA === deszcz",
        mail = "=== POCZTA === 3 nowe"
    )

    @Test
    fun `brak materialu to brak briefingu`() {
        val prefs = DailyBriefing.Preferences()
        assertNull(DailyBriefing.buildPrompt(DailyBriefing.Material(), prefs))
        assertFalse(DailyBriefing.hasAnything(DailyBriefing.Material(), prefs))
    }

    @Test
    fun `wylaczenie wszystkich sekcji ucisza briefing`() {
        // Nawet gdy dane są - użytkownik ma prawo nie chcieć ich słyszeć.
        val prefs = DailyBriefing.Preferences(
            includeCalendar = false,
            includeWeather = false,
            includeMail = false
        )
        assertNull(DailyBriefing.buildPrompt(full, prefs))
        assertFalse(DailyBriefing.hasAnything(full, prefs))
    }

    @Test
    fun `wylaczona sekcja nie trafia do polecenia`() {
        val prefs = DailyBriefing.Preferences(includeWeather = false, includeMail = false)
        val prompt = DailyBriefing.buildPrompt(full, prefs)!!
        assertTrue(prompt.contains("KALENDARZ"))
        assertFalse(prompt.contains("POGODA"))
        assertFalse(prompt.contains("POCZTA"))
    }

    @Test
    fun `poczta wchodzi dopiero po wlaczeniu`() {
        val off = DailyBriefing.buildPrompt(full, DailyBriefing.Preferences())!!
        assertFalse(off.contains("POCZTA"))
        val on = DailyBriefing.buildPrompt(full, DailyBriefing.Preferences(includeMail = true))!!
        assertTrue(on.contains("POCZTA"))
    }

    @Test
    fun `pusta sekcja liczy sie jak brak sekcji`() {
        val material = DailyBriefing.Material(calendar = "   ", weather = null)
        assertFalse(DailyBriefing.hasAnything(material, DailyBriefing.Preferences()))
    }

    @Test
    fun `wlasne wskazowki trafiaja do polecenia`() {
        val prefs = DailyBriefing.Preferences(focus = "mów o korkach na trasie do pracy")
        val prompt = DailyBriefing.buildPrompt(full, prefs)!!
        assertTrue(prompt.contains("korkach"))
    }

    @Test
    fun `puste wskazowki nie dokladaja pustej sekcji`() {
        val prompt = DailyBriefing.buildPrompt(full, DailyBriefing.Preferences(focus = "  "))!!
        assertFalse(prompt.contains("zwracać uwagę na"))
    }

    @Test
    fun `dlugosc zmienia polecenie`() {
        val short = DailyBriefing.buildPrompt(
            full, DailyBriefing.Preferences(length = DailyBriefing.Length.SHORT)
        )!!
        val detailed = DailyBriefing.buildPrompt(
            full, DailyBriefing.Preferences(length = DailyBriefing.Length.DETAILED)
        )!!
        assertTrue(short.contains("trzech zdaniach"))
        assertTrue(detailed.contains("dziesięciu zdań"))
    }

    @Test
    fun `polecenie zakazuje list i zmyslania`() {
        // Briefing idzie prosto do syntezatora mowy - lista punktowana brzmi
        // wtedy jak bełkot, a zmyślone spotkanie jest gorsze niż jego brak.
        val prompt = DailyBriefing.buildPrompt(full, DailyBriefing.Preferences())!!
        assertTrue(prompt.contains("bez list"))
        assertTrue(prompt.contains("Nie wymyślaj"))
    }

    @Test
    fun `nieznana dlugosc wraca do krotkiej`() {
        assertTrue(DailyBriefing.Length.fromId("cokolwiek") == DailyBriefing.Length.SHORT)
        assertTrue(DailyBriefing.Length.fromId(null) == DailyBriefing.Length.SHORT)
        assertTrue(DailyBriefing.Length.fromId("detailed") == DailyBriefing.Length.DETAILED)
    }

    @Test
    fun `prosba o briefing jest rozpoznawana`() {
        assertTrue(DailyBriefing.isBriefingRequest("Briefing"))
        assertTrue(DailyBriefing.isBriefingRequest("co mnie dziś czeka?"))
        assertTrue(DailyBriefing.isBriefingRequest("Podsumuj dzień."))
    }

    @Test
    fun `zwykle pytanie nie jest prosba o briefing`() {
        // Dopasowanie po fragmencie łapałoby te zdania - i zamiast odpowiedzi
        // użytkownik dostawałby cały poranny briefing.
        assertFalse(DailyBriefing.isBriefingRequest("co dziś jadłeś"))
        assertFalse(DailyBriefing.isBriefingRequest("podsumuj dzień wczorajszy w trzech zdaniach"))
        assertFalse(DailyBriefing.isBriefingRequest(""))
    }
}
