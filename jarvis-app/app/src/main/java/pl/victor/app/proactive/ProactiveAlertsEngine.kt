package pl.victor.app.proactive

import android.util.Log

/**
 * Silnik proaktywnych alertów.
 *
 * Logika:
 * - Sprawdź kalendarz - następne spotkanie
 * - Sprawdź pogodę - czy będzie padać w oknie (teraz → 30 min przed wyjściem)
 * - Jeśli tak → wygeneruj alert
 *
 * Konkretne alerty:
 * - "Będzie padać, weź parasol" (15-30 min przed wyjściem)
 * - "Śnieg, ubierz się ciepło" (1-2h przed wyjściem)
 * - "Ulewa - może weź taksówkę" (15 min przed)
 * - "Wiatr 50 km/h, uważaj" (silny wiatr)
 * - "Spóźnisz się X min, mam powiedzieć szefowi?" (za mało czasu)
 */
class ProactiveAlertsEngine {

    private val tag = "ProactiveAlerts"

    /** Okno analizy gdy nie ma wydarzenia w kalendarzu - najbliższa godzina. */
    private val DEFAULT_WINDOW_MS = 60 * 60 * 1000L

    /** Próg alertu o wietrze w km/h. */
    private val WIND_THRESHOLD_KMH = 50.0

    /** Ile minut przed zachodem słońca ostrzegać. */
    private val SUNSET_WARNING_MINUTES = 30L

    /** Do ilu procent zachmurzenia uznajemy niebo za bezchmurne. */
    private val CLEAR_SKY_PERCENT = 20

    /**
     * Główna funkcja - analizuje i zwraca listę alertów do wyświetlenia.
     */
    fun analyze(
        event: CalendarEvent?,
        forecast: WeatherForecast?,
        airQuality: AirQuality? = null,
        now: Long = System.currentTimeMillis()
    ): List<ProactiveAlert> {
        if (forecast == null) {
            Log.d(tag, "Brak prognozy - brak alertów pogodowych")
            return environmentAlerts(airQuality, null, now)
        }

        val alerts = mutableListOf<ProactiveAlert>()

        // Bez wydarzenia w kalendarzu i tak ostrzegamy o pogodzie -
        // wtedy patrzymy na najbliższą godzinę zamiast na czas do wyjścia.
        val leaveWindowStart = now
        val leaveWindowEnd = event?.leaveByMs ?: (now + DEFAULT_WINDOW_MS)
        val minutesToLeave = (leaveWindowEnd - now) / (60 * 1000)

        // Dopisek o spotkaniu tylko wtedy, gdy w ogóle jest wydarzenie.
        val eventSuffix = event?.let { "Spotkanie: ${it.title}" } ?: ""

        Log.d(tag, "Analiza: event='${event?.title ?: "brak"}', " +
                "okno ${minutesToLeave}min [$leaveWindowStart, $leaveWindowEnd]")

        // Sprawdź czy będzie padać między TERAZ a WYJŚCIEM
        val rain = forecast.willRainBetween(leaveWindowStart, leaveWindowEnd)

        if (rain != null) {
            val minutesUntilRain = ((rain.startsAt - now) / (60 * 1000)).coerceAtLeast(0)
            val alert = when {
                rain.snowMm > 0 -> ProactiveAlert(
                    type = AlertType.SNOW,
                    severity = AlertSeverity.MEDIUM,
                    title = "❄️ Będzie padać śnieg",
                    message = "${rain.summary()} między teraz a Twoim wyjściem (${minutesUntilRain} min). " +
                            "Ubierz się ciepło, weź czapkę i rękawiczki.",
                    event = event
                )
                rain.rainMm > 5 -> ProactiveAlert(
                    type = AlertType.HEAVY_RAIN,
                    severity = AlertSeverity.HIGH,
                    title = "⛈️ Będzie ulewa",
                    message = "${rain.summary()} za ${minutesUntilRain} min. " +
                            "Weź parasol i kalosze, albo weź taksówkę. " +
                            eventSuffix,
                    event = event
                )
                rain.rainMm > 1 -> ProactiveAlert(
                    type = AlertType.RAIN,
                    severity = AlertSeverity.MEDIUM,
                    title = "☂️ Będzie padać",
                    message = "${rain.summary()} za ${minutesUntilRain} min. " +
                            "Weź parasol przed wyjściem. " +
                            eventSuffix,
                    event = event
                )
                else -> ProactiveAlert(
                    type = AlertType.LIGHT_RAIN,
                    severity = AlertSeverity.LOW,
                    title = "🌦️ Lekki deszcz",
                    message = "Będzie lekko padać (${rain.summary()}) za ${minutesUntilRain} min. " +
                            "Może warto zabrać mały parasol? " +
                            eventSuffix,
                    event = event
                )
            }
            alerts.add(alert)
        }

        // Silny wiatr - uwzględnia też porywy (wind.gust)
        forecast.strongWindBetween(leaveWindowStart, leaveWindowEnd, WIND_THRESHOLD_KMH)?.let { wind ->
            alerts.add(ProactiveAlert(
                type = AlertType.STRONG_WIND,
                severity = if (wind.gustKmh >= 70) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                title = "💨 Silny wiatr",
                message = "${wind.summary()}. Uważaj na parasol i na rowerze.",
                event = event
            ))
        }

        // Sprawdź temperaturę - czy będzie bardzo zimno/ciepło
        val avgTemp = forecast.entries
            .filter { it.timestampMs in leaveWindowStart..leaveWindowEnd }
            .map { it.tempCelsius }
            .average()

        when {
            avgTemp < 0 -> alerts.add(ProactiveAlert(
                type = AlertType.COLD,
                severity = AlertSeverity.MEDIUM,
                title = "🥶 Mróz",
                message = "Temperatura spadnie do ${avgTemp.toInt()}°C. " +
                        "Ubierz się bardzo ciepło - czapka, szalik, rękawiczki obowiązkowe.",
                event = event
            ))
            avgTemp > 30 -> alerts.add(ProactiveAlert(
                type = AlertType.HOT,
                severity = AlertSeverity.LOW,
                title = "🥵 Upał",
                message = "Temperatura ${avgTemp.toInt()}°C. Weź wodę, " +
                        "unikaj długiego słońca.",
                event = event
            ))
        }

        // Sprawdź czy user się spóźni (ma <10 min do wyjścia) - tylko gdy jest wydarzenie
        if (event != null && minutesToLeave in 1..10) {
            val distance = event.location?.let { estimateCommuteTime(it) } ?: 10
            if (minutesToLeave < distance) {
                alerts.add(ProactiveAlert(
                    type = AlertType.LATE,
                    severity = AlertSeverity.HIGH,
                    title = "⏰ Spóźnisz się!",
                    message = "Zostało Ci tylko $minutesToLeave min do wyjścia, " +
                            "a dojazd do ${event.location ?: "miejsca spotkania"} zajmie ~$distance min. " +
                            "Wychodź natychmiast!",
                    event = event
                ))
            }
        }

        alerts += environmentAlerts(airQuality, forecast, now)

        Log.d(tag, "Wygenerowano ${alerts.size} alert(ów): ${alerts.map { it.type }}")
        return alerts
    }

    /**
     * Alerty niezależne od kalendarza: jakość powietrza, zbliżający się zachód
     * słońca i dobra widoczność.
     */
    private fun environmentAlerts(
        airQuality: AirQuality?,
        forecast: WeatherForecast?,
        now: Long
    ): List<ProactiveAlert> {
        val alerts = mutableListOf<ProactiveAlert>()

        if (airQuality != null && airQuality.isUnhealthy) {
            alerts.add(
                ProactiveAlert(
                    type = AlertType.AIR_QUALITY,
                    severity = if (airQuality.aqi >= 5) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                    title = "😷 Jakość powietrza",
                    message = "${airQuality.summary()}. Rozważ ograniczenie wysiłku na zewnątrz."
                )
            )
        }

        if (forecast != null) {
            forecast.minutesToSunset(now)?.let { minutes ->
                if (minutes in 1..SUNSET_WARNING_MINUTES) {
                    alerts.add(
                        ProactiveAlert(
                            type = AlertType.SUNSET,
                            severity = AlertSeverity.LOW,
                            title = "🌇 Zmierzch",
                            message = "Zachód słońca za $minutes min. Zrobi się ciemno."
                        )
                    )
                }
            }

            val clouds = forecast.cloudinessBetween(now, now + DEFAULT_WINDOW_MS)
            if (clouds != null && clouds <= CLEAR_SKY_PERCENT) {
                alerts.add(
                    ProactiveAlert(
                        type = AlertType.GOOD_VISIBILITY,
                        severity = AlertSeverity.LOW,
                        title = "☀️ Bezchmurnie",
                        message = "Zachmurzenie $clouds% - dobra widoczność."
                    )
                )
            }
        }

        return alerts
    }

    /**
     * Heurystyczne oszacowanie czasu dojazdu (w minutach).
     * W przyszłości: Google Maps Distance Matrix API.
     */
    private fun estimateCommuteTime(location: String): Int {
        return when {
            location.contains("home", ignoreCase = true) ||
            location.contains("dom", ignoreCase = true) -> 5
            location.contains("office", ignoreCase = true) ||
            location.contains("biuro", ignoreCase = true) -> 15
            else -> 10
        }
    }
}

data class ProactiveAlert(
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    /** Powiązane wydarzenie; `null` dla alertów czysto pogodowych. */
    val event: CalendarEvent? = null
)

enum class AlertType {
    LIGHT_RAIN, RAIN, HEAVY_RAIN, SNOW,
    STRONG_WIND, COLD, HOT, LATE,
    AIR_QUALITY, SUNSET, GOOD_VISIBILITY
}

enum class AlertSeverity { LOW, MEDIUM, HIGH }
