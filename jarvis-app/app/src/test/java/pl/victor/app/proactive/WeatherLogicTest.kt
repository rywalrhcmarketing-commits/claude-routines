package pl.victor.app.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy logiki pogodowej liczonej lokalnie na danych z OpenWeatherMap.
 * Nie dotykają sieci - sprawdzają wnioskowanie, które decyduje o alertach.
 */
class WeatherLogicTest {

    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    private fun entry(
        offsetHours: Long,
        temp: Double = 15.0,
        wind: Double = 2.0,
        gust: Double = 0.0,
        rain: Double = 0.0,
        snow: Double = 0.0,
        clouds: Int = 50
    ) = WeatherEntry(
        timestampMs = now + offsetHours * hour,
        tempCelsius = temp,
        feelsLike = temp,
        humidity = 60,
        windSpeed = wind,
        condition = "Clouds",
        description = "zachmurzenie",
        rainMm3h = rain,
        snowMm3h = snow,
        cloudsPercent = clouds,
        windGust = gust
    )

    private fun forecast(vararg entries: WeatherEntry, sunset: Long = 0L) =
        WeatherForecast(city = "Warszawa", entries = entries.toList(), sunsetMs = sunset)

    // === Deszcz ===

    @Test
    fun `wykrywa deszcz w oknie czasowym`() {
        val f = forecast(entry(0), entry(1, rain = 2.5), entry(2))
        val rain = f.willRainBetween(now, now + 3 * hour)
        assertNotNull(rain)
        assertEquals(2.5, rain!!.rainMm, 0.01)
    }

    @Test
    fun `ignoruje deszcz poza oknem`() {
        val f = forecast(entry(0), entry(10, rain = 5.0))
        assertNull(f.willRainBetween(now, now + 2 * hour))
    }

    @Test
    fun `snieg jest raportowany osobno`() {
        val f = forecast(entry(1, snow = 3.0))
        val rain = f.willRainBetween(now, now + 2 * hour)!!
        assertEquals(3.0, rain.snowMm, 0.01)
        assertTrue(rain.summary().contains("Śnieg"))
    }

    // === Wiatr ===

    @Test
    fun `wiatr ponizej progu nie generuje alertu`() {
        // 10 m/s to 36 km/h - poniżej progu 50.
        val f = forecast(entry(1, wind = 10.0))
        assertNull(f.strongWindBetween(now, now + 2 * hour))
    }

    @Test
    fun `wiatr powyzej progu jest wykrywany`() {
        // 15 m/s to 54 km/h.
        val f = forecast(entry(1, wind = 15.0))
        val wind = f.strongWindBetween(now, now + 2 * hour)
        assertNotNull(wind)
        assertEquals(54.0, wind!!.speedKmh, 0.5)
    }

    @Test
    fun `poryw przekraczajacy prog liczy sie mimo slabego wiatru sredniego`() {
        // Średnia 5 m/s, ale porywy 20 m/s (72 km/h) - to jest groźne.
        val f = forecast(entry(1, wind = 5.0, gust = 20.0))
        val wind = f.strongWindBetween(now, now + 2 * hour)
        assertNotNull(wind)
        assertTrue(wind!!.gustKmh > 70)
    }

    @Test
    fun `opis wiatru rosnie z sila`() {
        assertTrue(WindInfo(now, 55.0, 0.0).summary().contains("Silny"))
        assertTrue(WindInfo(now, 75.0, 0.0).summary().contains("Bardzo silny"))
        assertTrue(WindInfo(now, 95.0, 0.0).summary().contains("Wichura"))
    }

    // === Zachmurzenie i zachód słońca ===

    @Test
    fun `usrednia zachmurzenie w oknie`() {
        val f = forecast(entry(0, clouds = 10), entry(1, clouds = 30))
        assertEquals(20, f.cloudinessBetween(now, now + 2 * hour))
    }

    @Test
    fun `brak danych o zachmurzeniu zwraca null`() {
        assertNull(forecast().cloudinessBetween(now, now + hour))
    }

    @Test
    fun `liczy minuty do zachodu slonca`() {
        val f = forecast(sunset = now + 45 * 60_000L)
        assertEquals(45L, f.minutesToSunset(now))
    }

    @Test
    fun `zachod ktory juz minal zwraca null`() {
        val f = forecast(sunset = now - hour)
        assertNull(f.minutesToSunset(now))
    }

    @Test
    fun `brak danych o zachodzie zwraca null`() {
        assertNull(forecast(sunset = 0L).minutesToSunset(now))
    }

    // === Jakość powietrza ===

    @Test
    fun `czyste powietrze nie jest niezdrowe`() {
        assertFalse(AirQuality(aqi = 1, pm25 = 5.0, pm10 = 10.0).isUnhealthy)
    }

    @Test
    fun `wysoki indeks AQI oznacza niezdrowe`() {
        assertTrue(AirQuality(aqi = 4, pm25 = 5.0, pm10 = 10.0).isUnhealthy)
    }

    @Test
    fun `wysokie PM2_5 oznacza niezdrowe mimo niskiego AQI`() {
        // Sam indeks bywa zaniżony - progi WHO dla pyłów są ostrzejsze.
        assertTrue(AirQuality(aqi = 2, pm25 = 40.0, pm10 = 10.0).isUnhealthy)
    }

    @Test
    fun `opis powietrza zawiera wartosci pylow`() {
        val summary = AirQuality(aqi = 3, pm25 = 22.7, pm10 = 41.2).summary()
        assertTrue(summary.contains("umiarkowane"))
        assertTrue(summary.contains("22"))
        assertTrue(summary.contains("41"))
    }
}
