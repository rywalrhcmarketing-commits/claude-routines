package pl.jarvis.app.proactive

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zamiana wydarzeń z kalendarza na fragment promptu dla modelu.
 *
 * Wydzielone z [pl.jarvis.app.AIOrchestrator] jako czyste funkcje, żeby dało się
 * to przetestować bez Androida - a przy okazji, żeby reguła „kiedy w ogóle
 * sięgamy po kalendarz" była w jednym miejscu i widoczna.
 *
 * ## Dlaczego nie zawsze
 * Kalendarz to dane wrażliwe i kosztują tokeny. Doklejamy go **tylko wtedy**,
 * gdy pytanie faktycznie dotyczy planów - inaczej każde „co to jest?" wysyłałoby
 * do modelu listę spotkań użytkownika.
 */
object CalendarContext {

    /**
     * Czy pytanie dotyczy planów, kalendarza albo spotkań.
     *
     * Dopasowanie jest celowo zachowawcze: fałszywe trafienie wysyła prywatne
     * dane do modelu, a fałszywe pominięcie kosztuje tylko gorszą odpowiedź.
     */
    fun isAboutSchedule(question: String): Boolean {
        val text = question.lowercase(Locale.ROOT)
        return KEYWORDS.any { it in text }
    }

    /**
     * Składa opis wydarzeń dla modelu.
     *
     * @return fragment promptu albo `null`, gdy nie ma czego dokleić
     */
    fun buildPromptContext(
        events: List<CalendarEvent>,
        now: Long = System.currentTimeMillis()
    ): String? {
        if (events.isEmpty()) return null

        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFormatter = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

        return buildString {
            append("=== KALENDARZ UŻYTKOWNIKA ===\n")
            append("Teraz jest ").append(formatter.format(Date(now)))
            append(", ").append(dayFormatter.format(Date(now))).append(".\n")
            append("Nadchodzące wydarzenia:\n")
            events.forEach { event ->
                append("- ").append(formatter.format(Date(event.beginMs)))
                append(" ").append(event.title.ifBlank { "(bez tytułu)" })
                event.location?.takeIf { it.isNotBlank() }?.let {
                    append(", miejsce: ").append(it)
                }
                append(" (").append(describeDelay(event.beginMs - now)).append(")")
                append("\n")
            }
            append(
                "Odpowiadaj na podstawie tej listy. Jeśli pytanie dotyczy czegoś, " +
                    "czego na niej nie ma, powiedz wprost, że tego nie widzisz.\n"
            )
        }
    }

    /** „za 25 minut", „za 3 godziny", „trwa" - zamiast surowych znaczników czasu. */
    internal fun describeDelay(deltaMs: Long): String {
        if (deltaMs <= 0) return "już trwa albo się zaczęło"
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1 -> "za chwilę"
            minutes < 60 -> "za $minutes ${minuteForm(minutes)}"
            else -> {
                val hours = minutes / 60
                val rest = minutes % 60
                val head = "za $hours ${hourForm(hours)}"
                if (rest == 0L) head else "$head $rest ${minuteForm(rest)}"
            }
        }
    }

    /** Polska odmiana - „1 minutę", „2 minuty", „5 minut". */
    private fun minuteForm(n: Long): String = when {
        n == 1L -> "minutę"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "minuty"
        else -> "minut"
    }

    private fun hourForm(n: Long): String = when {
        n == 1L -> "godzinę"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "godziny"
        else -> "godzin"
    }

    private val KEYWORDS = listOf(
        "kalendarz", "kalendarzu", "plan", "plany", "planach", "planie",
        "spotkanie", "spotkania", "spotkań", "spotkaniu",
        "harmonogram", "grafik", "terminarz",
        "co mam dzisiaj", "co mam dziś", "co mam jutro",
        "wydarzenie", "wydarzenia",
        "umówion", "zaplanowan",
        "schedule", "meeting", "calendar", "agenda"
    )
}
