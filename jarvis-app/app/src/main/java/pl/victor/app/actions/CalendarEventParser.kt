package pl.victor.app.actions

import java.util.Calendar

/**
 * Parsuje polecenia głosowe tworzenia wydarzeń kalendarza na tytuł + dokładny czas.
 *
 * Wydzielone z [SmartActionDetector] jako czyste funkcje - łatwiej to przetestować,
 * a arytmetyka dat jest dokładnie tym rodzajem kodu, w którym w tej sesji już raz
 * znalazł się błąd (przesunięcie godziny dla "12 północ" w alarmie).
 */
object CalendarEventParser {

    data class ParsedEvent(val title: String, val startTimeMillis: Long)

    /**
     * @return `null`, gdy w tekście nie ma rozpoznawalnej godziny - bez niej nie da
     *         się bezpiecznie utworzyć wydarzenia, a zgadywanie mogłoby wstawić je
     *         o złej porze bez ostrzeżenia.
     */
    fun parse(text: String, now: Long = System.currentTimeMillis()): ParsedEvent? {
        val match = EVENT_REGEX.find(text.lowercase()) ?: return null

        val title = match.groupValues[1].trim().trimEnd('.', '!', '?', ',').ifBlank { "Spotkanie" }
        val dayWord = match.groupValues[2].trim()
        val hour = match.groupValues[3].toIntOrNull()?.coerceIn(0, 23) ?: return null
        val minute = match.groupValues[4].toIntOrNull()?.coerceIn(0, 59) ?: 0

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        applyDayOffset(cal, dayWord)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Bez słowa dnia, a godzina już dziś minęła - przesuń na jutro. Dokładnie
        // tak umówiłby się człowiek: "o 9" powiedziane o 15:00 nie znaczy "w przeszłości".
        if (dayWord.isBlank() && cal.timeInMillis < now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return ParsedEvent(title = title, startTimeMillis = cal.timeInMillis)
    }

    private fun applyDayOffset(cal: Calendar, dayWord: String) {
        when {
            dayWord.isBlank() || "dziś" in dayWord || "dzisiaj" in dayWord -> return
            "pojutrze" in dayWord -> cal.add(Calendar.DAY_OF_YEAR, 2)
            "jutro" in dayWord -> cal.add(Calendar.DAY_OF_YEAR, 1)
            else -> {
                val targetDow = DAY_NAMES.entries.firstOrNull { (name, _) -> name in dayWord }?.value
                    ?: return
                // Najbliższe wystąpienie tego dnia tygodnia w przód (1-7 dni).
                var offset = (targetDow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (offset == 0) offset = 7  // "w poniedziałek" powiedziane w poniedziałek = za tydzień
                cal.add(Calendar.DAY_OF_YEAR, offset)
            }
        }
    }

    private val DAY_NAMES = mapOf(
        "poniedzia" to Calendar.MONDAY,   // "poniedziałek"/"poniedzialek"
        "wtorek" to Calendar.TUESDAY,
        "rod" to Calendar.WEDNESDAY,      // "środę"/"srode"/"środe" - rdzeń bez pierwszej litery
        "czwartek" to Calendar.THURSDAY,
        "pi" to Calendar.FRIDAY,          // "piątek"/"piatek"
        "sobot" to Calendar.SATURDAY,     // "sobotę"/"sobote"
        "niedziel" to Calendar.SUNDAY     // "niedzielę"/"niedziele"
    )

    /**
     * Grupy: 1=tytuł (leniwie), 2=opcjonalne słowo dnia, 3=godzina, 4=opcjonalne minuty.
     */
    private val EVENT_REGEX = Regex(
        """(?:dodaj\s+do\s+kalendarza|um[oó]w\s+spotkanie|zaplanuj\s+spotkanie|""" +
            """zaplanuj\s+wydarzenie|dodaj\s+wydarzenie)\s+(?:z\s+)?(.+?)\s+""" +
            """(dzi[sś]|dzisiaj|jutro|pojutrze|w\s+poniedzia[lł]ek|we?\s+wtorek|""" +
            """w\s+[sś]rod[eę]|w\s+czwartek|w\s+pi[aą]tek|w\s+sobot[eę]|w\s+niedziel[eę])?""" +
            """\s*o\s+(?:godzinie\s+|godz\.?\s+)?(\d{1,2})(?::(\d{2}))?"""
    )
}
