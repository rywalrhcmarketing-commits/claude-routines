package pl.jarvis.app.proactive

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

    /**
     * Główna funkcja - analizuje i zwraca listę alertów do wyświetlenia.
     */
    fun analyze(
        event: CalendarEvent?,
        forecast: WeatherForecast?,
        now: Long = System.currentTimeMillis()
    ): List<ProactiveAlert> {
        if (event == null || forecast == null) {
            Log.d(tag, "Brak eventu lub prognozy - brak alertów")
            return emptyList()
        }

        val alerts = mutableListOf<ProactiveAlert>()

        // Okno czasowe: teraz → kiedy user musi wyjść
        val leaveWindowStart = now
        val leaveWindowEnd = event.leaveByMs
        val minutesToLeave = (leaveWindowEnd - now) / (60 * 1000)

        Log.d(tag, "Analyzing: event='${event.title}' at ${event.beginMs}, " +
                "leave in ${minutesToLeave}min, window=[$leaveWindowStart, $leaveWindowEnd]")

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
                            "Spotkanie: ${event.title}",
                    event = event
                )
                rain.rainMm > 1 -> ProactiveAlert(
                    type = AlertType.RAIN,
                    severity = AlertSeverity.MEDIUM,
                    title = "☂️ Będzie padać",
                    message = "${rain.summary()} za ${minutesUntilRain} min. " +
                            "Weź parasol przed wyjściem. " +
                            "Spotkanie: ${event.title}",
                    event = event
                )
                else -> ProactiveAlert(
                    type = AlertType.LIGHT_RAIN,
                    severity = AlertSeverity.LOW,
                    title = "🌦️ Lekki deszcz",
                    message = "Będzie lekko padać (${rain.summary()}) za ${minutesUntilRain} min. " +
                            "Może warto zabrać mały parasol? " +
                            "Spotkanie: ${event.title}",
                    event = event
                )
            }
            alerts.add(alert)
        }

        // Sprawdź silny wiatr (>40 km/h) w oknie wyjścia
        val maxWind = forecast.entries
            .filter { it.timestampMs in leaveWindowStart..leaveWindowEnd }
            .maxOfOrNull { it.windSpeed } ?: 0.0

        if (maxWind > 11) {  // 11 m/s = ~40 km/h
            alerts.add(ProactiveAlert(
                type = AlertType.STRONG_WIND,
                severity = AlertSeverity.MEDIUM,
                title = "💨 Silny wiatr",
                message = "Wiatr do ${(maxWind * 3.6).toInt()} km/h między teraz a Twoim wyjściem. " +
                        "Uważaj na parasol - może się złamać.",
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

        // Sprawdź czy user się spóźni (ma <10 min do wyjścia)
        if (minutesToLeave in 1..10) {
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

        Log.d(tag, "Generated ${alerts.size} alert(s): ${alerts.map { it.type }}")
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
    val event: CalendarEvent
)

enum class AlertType {
    LIGHT_RAIN, RAIN, HEAVY_RAIN, SNOW,
    STRONG_WIND, COLD, HOT, LATE
}

enum class AlertSeverity { LOW, MEDIUM, HIGH }
