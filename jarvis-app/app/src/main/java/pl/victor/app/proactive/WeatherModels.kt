package pl.victor.app.proactive

/**
 * Model danych pogodowych - oddzielony od [WeatherService], który je pobiera.
 *
 * Rozdział jest po to, żeby dało się testować to, co z prognozy WYNIKA
 * (czy będzie padać, ile do zachodu, jak zbudować kontekst dla modelu), bez
 * wciągania klienta HTTP i parsera JSON-a. Same struktury nie mają żadnej
 * zależności poza biblioteką standardową.
 */

data class WeatherForecast(
    val city: String,
    val entries: List<WeatherEntry>,
    /** Wschód słońca (ms epoch), 0 gdy nieznany. */
    val sunriseMs: Long = 0L,
    /** Zachód słońca (ms epoch), 0 gdy nieznany. */
    val sunsetMs: Long = 0L
) {
    /**
     * Sprawdza czy będzie padać w danym oknie czasowym.
     */
    /**
     * Silny wiatr w oknie czasowym.
     *
     * @param thresholdKmh próg alertu w km/h
     * @return najsilniejszy podmuch przekraczający próg albo `null`
     */
    fun strongWindBetween(startMs: Long, endMs: Long, thresholdKmh: Double = 50.0): WindInfo? {
        val relevant = entries.filter { it.timestampMs in startMs..endMs }
        if (relevant.isEmpty()) return null
        val worst = relevant.maxByOrNull { maxOf(it.windSpeed, it.windGust) } ?: return null
        // OWM z units=metric podaje prędkość w m/s.
        val speedKmh = worst.windSpeed * MPS_TO_KMH
        val gustKmh = worst.windGust * MPS_TO_KMH
        val peak = maxOf(speedKmh, gustKmh)
        if (peak < thresholdKmh) return null
        return WindInfo(atMs = worst.timestampMs, speedKmh = speedKmh, gustKmh = gustKmh)
    }

    /** Średnie zachmurzenie w oknie czasowym (procent), `null` gdy brak danych. */
    fun cloudinessBetween(startMs: Long, endMs: Long): Int? {
        val relevant = entries.filter { it.timestampMs in startMs..endMs }
        if (relevant.isEmpty()) return null
        return relevant.map { it.cloudsPercent }.average().toInt()
    }

    /** Ile minut zostało do zachodu słońca; `null` gdy zachód już minął lub brak danych. */
    fun minutesToSunset(nowMs: Long = System.currentTimeMillis()): Long? {
        if (sunsetMs <= 0L || sunsetMs <= nowMs) return null
        return (sunsetMs - nowMs) / 60_000L
    }

    fun willRainBetween(startMs: Long, endMs: Long): RainInfo? {
        val relevant = entries.filter { it.timestampMs in startMs..endMs }
        val totalRain = relevant.sumOf { it.rainMm3h }
        val totalSnow = relevant.sumOf { it.snowMm3h }
        if (totalRain == 0.0 && totalSnow == 0.0) return null

        val firstRain = relevant.firstOrNull { it.rainMm3h > 0 || it.snowMm3h > 0 }
        return RainInfo(
            startsAt = firstRain?.timestampMs ?: 0L,
            rainMm = totalRain,
            snowMm = totalSnow,
            description = firstRain?.description ?: ""
        )
    }
}

/** Przelicznik m/s (jednostka OWM przy units=metric) na km/h. */
private const val MPS_TO_KMH = 3.6

data class WeatherEntry(
    val timestampMs: Long,
    val tempCelsius: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val condition: String,       // "Rain", "Snow", "Clear", "Clouds"
    val description: String,     // "lekki deszcz"
    val rainMm3h: Double = 0.0,
    val snowMm3h: Double = 0.0,
    /** Zachmurzenie w procentach (0 = bezchmurnie). */
    val cloudsPercent: Int = 0,
    /** Porywy wiatru w m/s (0 gdy API ich nie podało). */
    val windGust: Double = 0.0
)

/** Alert o silnym wietrze. */
data class WindInfo(
    val atMs: Long,
    val speedKmh: Double,
    val gustKmh: Double
) {
    fun summary(): String {
        val peak = maxOf(speedKmh, gustKmh).toInt()
        return when {
            peak >= 90 -> "Wichura, $peak km/h"
            peak >= 70 -> "Bardzo silny wiatr, $peak km/h"
            else -> "Silny wiatr, $peak km/h"
        }
    }
}

/**
 * Jakość powietrza wg OpenWeatherMap.
 * @param aqi indeks 1-5 (1 = bardzo dobra, 5 = bardzo zła)
 */
data class AirQuality(
    val aqi: Int,
    val pm25: Double,
    val pm10: Double
) {
    /** Czy warto ostrzec użytkownika. */
    val isUnhealthy: Boolean get() = aqi >= 4 || pm25 > 25.0 || pm10 > 50.0

    fun summary(): String {
        val label = when (aqi) {
            1 -> "Powietrze czyste"
            2 -> "Powietrze dobre"
            3 -> "Powietrze umiarkowane"
            4 -> "Powietrze złe"
            5 -> "Powietrze bardzo złe"
            else -> "Jakość powietrza nieznana"
        }
        return "$label (PM2.5 ${pm25.toInt()}, PM10 ${pm10.toInt()})"
    }
}

data class RainInfo(
    val startsAt: Long,
    val rainMm: Double,
    val snowMm: Double,
    val description: String
) {
    fun summary(): String = when {
        snowMm > 0 -> "Śnieg ($snowMm mm)"
        rainMm > 5 -> "Ulewny deszcz ($rainMm mm)"
        rainMm > 1 -> "Deszcz ($rainMm mm)"
        else -> "Lekki deszcz"
    }
}

data class GeoLocation(
    val name: String,
    val lat: Double,
    val lon: Double,
    val country: String
)
