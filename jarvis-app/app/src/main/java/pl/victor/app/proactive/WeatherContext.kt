package pl.victor.app.proactive

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pogoda jako kontekst dla modelu - dokładnie tą samą drogą, co kalendarz
 * ([CalendarContext]) i poczta ([GmailContext]).
 *
 * ## Dlaczego kontekst, a nie osobna "komenda pogodowa"
 * Aplikacja miała pogodę wyłącznie w alertach: sprawdzała ją w tle i wysyłała
 * powiadomienie, gdy coś było nie tak. Zapytana wprost - "jaka jest pogoda?" -
 * odpowiadała z pamięci modelu, czyli zmyślała. Dorobienie osobnej komendy
 * dałoby jedną sztywną formułkę; doklejenie prognozy do promptu pozwala
 * odpowiedzieć na wszystko naraz: "czy brać kurtkę", "czy zdążę przed
 * deszczem", "czy da się dziś biegać" - bo model ma dane i sam wyciąga wnioski.
 *
 * Prognoza jest doklejana TYLKO wtedy, gdy pytanie faktycznie jej dotyczy -
 * inaczej każde pytanie ciągnęłoby zapytanie do API pogodowego.
 */
object WeatherContext {

    /**
     * Czy pytanie dotyczy pogody.
     *
     * Świadomie szeroko - lepiej dokleić kilka linijek prognozy niepotrzebnie
     * niż odpowiedzieć zmyśloną temperaturą. Wzorce łapią też pytania zadane
     * nie wprost ("brać kurtkę?", "czy zmoknę").
     */
    fun isAboutWeather(question: String): Boolean {
        val q = question.lowercase()
        return WEATHER_KEYWORDS.any { q.contains(it) }
    }

    /**
     * Buduje fragment promptu z prognozą.
     *
     * @return `null`, gdy nie ma prognozy - wtedy prompt zostaje bez zmian,
     *         a model odpowie, że nie zna aktualnej pogody
     */
    fun buildPromptContext(
        forecast: WeatherForecast?,
        airQuality: AirQuality? = null,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        if (forecast == null || forecast.entries.isEmpty()) return null

        val timeFormat = SimpleDateFormat("EEEE HH:mm", Locale("pl", "PL"))
        val upcoming = forecast.entries
            .filter { it.timestampMs >= nowMs - HOUR_MS }
            .take(ENTRIES_IN_PROMPT)

        if (upcoming.isEmpty()) return null

        return buildString {
            append("=== PROGNOZA POGODY (").append(forecast.city).append(") ===\n")
            append("Dane z serwisu pogodowego, pobrane przed chwilą. ")
            append("Opieraj się na nich, nie na własnej pamięci.\n")

            upcoming.forEach { entry ->
                append("- ").append(timeFormat.format(Date(entry.timestampMs)))
                append(": ").append("%.0f".format(entry.tempCelsius)).append("°C")
                if (kotlin.math.abs(entry.feelsLike - entry.tempCelsius) >= 2.0) {
                    append(" (odczuwalna ").append("%.0f".format(entry.feelsLike)).append("°C)")
                }
                append(", ").append(entry.description)
                append(", wiatr ").append("%.0f".format(entry.windSpeed * MPS_TO_KMH)).append(" km/h")
                if (entry.rainMm3h > 0.0) {
                    append(", deszcz ").append("%.1f".format(entry.rainMm3h)).append(" mm")
                }
                if (entry.snowMm3h > 0.0) {
                    append(", śnieg ").append("%.1f".format(entry.snowMm3h)).append(" mm")
                }
                append('\n')
            }

            forecast.minutesToSunset(nowMs)?.let { minutes ->
                append("Do zachodu słońca: ").append(minutes).append(" min.\n")
            }

            airQuality?.let { append(it.summary()).append('\n') }

            append("Odpowiadaj krótko i konkretnie, tak jak się mówi na głos - ")
            append("bez tabelek i wyliczanek godzina po godzinie.\n")
        }
    }

    private const val HOUR_MS = 3_600_000L

    /** Ile wpisów prognozy (co 3 h) dokleić - 8 to doba do przodu. */
    private const val ENTRIES_IN_PROMPT = 8

    private const val MPS_TO_KMH = 3.6

    private val WEATHER_KEYWORDS = listOf(
        "pogod", "prognoz", "temperatur", "stopni", "ciepło", "cieplo",
        "zimno", "mróz", "mroz", "upał", "upal",
        "deszcz", "pada", "padać", "padac", "zmokn", "parasol",
        "śnieg", "snieg", "gołoledź", "gololedz", "ślisko", "slisko",
        "wiatr", "wietrzn", "burz", "mgła", "mgla",
        "słonecznie", "slonecznie", "zachmurzen", "chmur",
        "kurtk", "czapk", "ubrać się", "ubrac sie", "jak się ubrać", "jak sie ubrac",
        "smog", "powietrz", "pylenie",
        "zachód słońca", "zachod slonca", "wschód słońca", "wschod slonca",
        "weather", "forecast", "rain", "temperature"
    )
}
