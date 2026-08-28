package pl.jarvis.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy silnika alertów proaktywnych.
 *
 * Najważniejsze zabezpieczenie: alerty pogodowe muszą działać bez wpisu
 * w kalendarzu. Wcześniej silnik zwracał pustą listę, gdy nie było
 * nadchodzącego spotkania - przez co cała funkcja "co 15 minut sprawdza
 * pogodę" nie mogła zadziałać u nikogo bez kalendarza.
 */
class ProactiveAlertsEngineTest {

    private val engine = ProactiveAlertsEngine()
    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    private fun entry(
        offsetMin: Long,
        temp: Double = 15.0,
        wind: Double = 2.0,
        rain: Double = 0.0,
        clouds: Int = 50
    ) = WeatherEntry(
        timestampMs = now + offsetMin * 60_000L,
        tempCelsius = temp,
        feelsLike = temp,
        humidity = 60,
        windSpeed = wind,
        condition = "Clouds",
        description = "zachmurzenie",
        rainMm3h = rain,
        cloudsPercent = clouds
    )

    private fun forecast(vararg e: WeatherEntry, sunset: Long = 0L) =
        WeatherForecast("Warszawa", e.toList(), sunsetMs = sunset)

    private fun event(inMinutes: Long = 60) = CalendarEvent(
        id = 1,
        title = "Spotkanie z Anną",
        beginMs = now + inMinutes * 60_000L,
        endMs = now + (inMinutes + 60) * 60_000L,
        location = "biuro",
        calendarName = "praca",
        description = null,
        leaveByMs = now + (inMinutes - 15) * 60_000L
    )

    // === Działanie bez kalendarza ===

    @Test
    fun `alert o deszczu powstaje bez wydarzenia w kalendarzu`() {
        val alerts = engine.analyze(
            event = null,
            forecast = forecast(entry(30, rain = 3.0)),
            now = now
        )
        assertTrue(alerts.any { it.type == AlertType.RAIN || it.type == AlertType.HEAVY_RAIN })
    }

    @Test
    fun `alert bez wydarzenia nie ma dopisku o spotkaniu`() {
        val alert = engine.analyze(null, forecast(entry(30, rain = 3.0)), now = now)
            .first { it.type == AlertType.RAIN || it.type == AlertType.HEAVY_RAIN }
        assertFalse(alert.message.contains("Spotkanie"))
        assertEquals(null, alert.event)
    }

    @Test
    fun `alert z wydarzeniem zawiera jego tytul`() {
        val alert = engine.analyze(event(), forecast(entry(30, rain = 3.0)), now = now)
            .first { it.type == AlertType.RAIN || it.type == AlertType.HEAVY_RAIN }
        assertTrue(alert.message.contains("Spotkanie z Anną"))
    }

    @Test
    fun `brak prognozy nie wywala silnika`() {
        val alerts = engine.analyze(event(), forecast = null, now = now)
        assertNotNull(alerts)
    }

    // === Jakość powietrza ===

    @Test
    fun `zle powietrze generuje alert bez kalendarza`() {
        val alerts = engine.analyze(
            event = null,
            forecast = forecast(entry(30)),
            airQuality = AirQuality(aqi = 4, pm25 = 60.0, pm10 = 90.0),
            now = now
        )
        assertTrue(alerts.any { it.type == AlertType.AIR_QUALITY })
    }

    @Test
    fun `czyste powietrze nie generuje alertu`() {
        val alerts = engine.analyze(
            event = null,
            forecast = forecast(entry(30)),
            airQuality = AirQuality(aqi = 1, pm25 = 3.0, pm10 = 8.0),
            now = now
        )
        assertFalse(alerts.any { it.type == AlertType.AIR_QUALITY })
    }

    @Test
    fun `bardzo zle powietrze ma wysoki priorytet`() {
        val alert = engine.analyze(
            null, forecast(entry(30)),
            AirQuality(aqi = 5, pm25 = 120.0, pm10 = 180.0), now
        ).first { it.type == AlertType.AIR_QUALITY }
        assertEquals(AlertSeverity.HIGH, alert.severity)
    }

    // === Zmierzch i widoczność ===

    @Test
    fun `ostrzega przed zblizajacym sie zmierzchem`() {
        val alerts = engine.analyze(
            null, forecast(entry(30), sunset = now + 20 * 60_000L), now = now
        )
        assertTrue(alerts.any { it.type == AlertType.SUNSET })
    }

    @Test
    fun `nie ostrzega o zmierzchu odleglym o godziny`() {
        val alerts = engine.analyze(
            null, forecast(entry(30), sunset = now + 5 * hour), now = now
        )
        assertFalse(alerts.any { it.type == AlertType.SUNSET })
    }

    @Test
    fun `bezchmurne niebo jest zglaszane jako dobra widocznosc`() {
        val alerts = engine.analyze(null, forecast(entry(30, clouds = 5)), now = now)
        assertTrue(alerts.any { it.type == AlertType.GOOD_VISIBILITY })
    }

    // === Temperatura i wiatr ===

    @Test
    fun `mroz generuje alert`() {
        val alerts = engine.analyze(null, forecast(entry(30, temp = -8.0)), now = now)
        assertTrue(alerts.any { it.type == AlertType.COLD })
    }

    @Test
    fun `upal generuje alert`() {
        val alerts = engine.analyze(null, forecast(entry(30, temp = 34.0)), now = now)
        assertTrue(alerts.any { it.type == AlertType.HOT })
    }

    @Test
    fun `silny wiatr generuje alert`() {
        // 16 m/s to ~58 km/h.
        val alerts = engine.analyze(null, forecast(entry(30, wind = 16.0)), now = now)
        assertTrue(alerts.any { it.type == AlertType.STRONG_WIND })
    }

    @Test
    fun `lagodna pogoda nie generuje alertow pogodowych`() {
        val alerts = engine.analyze(null, forecast(entry(30, temp = 18.0, clouds = 50)), now = now)
        val pogodowe = alerts.filter {
            it.type in setOf(AlertType.RAIN, AlertType.HEAVY_RAIN, AlertType.COLD,
                AlertType.HOT, AlertType.STRONG_WIND)
        }
        assertTrue(pogodowe.isEmpty())
    }

    // === Spóźnienie ===

    @Test
    fun `alert o spoznieniu wymaga wydarzenia`() {
        // Bez wydarzenia nie ma dokąd się spóźnić.
        val alerts = engine.analyze(null, forecast(entry(5)), now = now)
        assertFalse(alerts.any { it.type == AlertType.LATE })
    }
}
