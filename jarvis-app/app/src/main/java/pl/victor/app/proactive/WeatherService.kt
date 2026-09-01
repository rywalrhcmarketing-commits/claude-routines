package pl.victor.app.proactive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Serwis pogodowy - OpenWeatherMap API (darmowy tier 1000 req/dzień).
 *
 * Klucz API: darmowy po rejestracji na https://openweathermap.org/api
 * Wpisz w Ustawieniach apki.
 *
 * Dostarcza:
 * - Aktualną pogodę i prognozę 5-dniową (co 3h)
 * - Jakość powietrza (PM2.5, PM10, indeks AQI)
 * - Wschód i zachód słońca
 * - Zachmurzenie i porywy wiatru
 * - Geo lookup - miasto → współrzędne
 *
 * Uwaga: indeks UV nie jest pobierany. Darmowy endpoint `/data/2.5/uvi` został
 * przez OpenWeatherMap wycofany, a One Call 3.0 wymaga osobnej subskrypcji.
 */
class WeatherService(private val apiKey: String) {

    private val tag = "WeatherService"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pobiera prognozę pogody dla podanych współrzędnych.
     * Zwraca listę prognoz (co 3h na 5 dni).
     */
    suspend fun getForecast(lat: Double, lon: Double): WeatherForecast? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(tag, "Brak API key")
            return@withContext null
        }

        try {
            val url = "https://api.openweathermap.org/data/2.5/forecast" +
                    "?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=pl"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "OWM forecast: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                parseForecast(body)
            }
        } catch (e: Exception) {
            Log.e(tag, "Forecast failed", e)
            null
        }
    }

    /**
     * Pobiera jakość powietrza (PM2.5, PM10, indeks AQI 1-5).
     * Endpoint dostępny w darmowym planie OpenWeatherMap.
     */
    suspend fun getAirQuality(lat: Double, lon: Double): AirQuality? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(tag, "Brak API key")
            return@withContext null
        }

        try {
            val url = "https://api.openweathermap.org/data/2.5/air_pollution" +
                    "?lat=$lat&lon=$lon&appid=$apiKey"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "OWM air_pollution: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val first = json.parseToJsonElement(body).jsonObject["list"]
                    ?.jsonArray?.firstOrNull()?.jsonObject ?: return@withContext null

                val components = first["components"]?.jsonObject
                AirQuality(
                    aqi = first["main"]?.jsonObject?.get("aqi")
                        ?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    pm25 = components?.get("pm2_5")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    pm10 = components?.get("pm10")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Air quality failed", e)
            null
        }
    }

    /**
     * Geo lookup: zamienia nazwę miasta na współrzędne.
     * "Warszawa,PL" → (52.23, 21.01)
     */
    suspend fun geocode(cityQuery: String): GeoLocation? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        try {
            val url = "https://api.openweathermap.org/geo/1.0/direct" +
                    "?q=${java.net.URLEncoder.encode(cityQuery, "UTF-8")}" +
                    "&limit=1&appid=$apiKey"
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val array = json.parseToJsonElement(body).jsonArray
                if (array.isEmpty()) return@withContext null
                val first = array[0].jsonObject
                GeoLocation(
                    name = first["name"]?.jsonPrimitive?.content ?: cityQuery,
                    lat = first["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    lon = first["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    country = first["country"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Geocode failed", e)
            null
        }
    }

    private fun parseForecast(body: String): WeatherForecast {
        val obj = json.parseToJsonElement(body).jsonObject
        val cityObj = obj["city"]?.jsonObject
        val sunriseMs = cityObj?.get("sunrise")?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: 0L
        val sunsetMs = cityObj?.get("sunset")?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: 0L

        val list = obj["list"]?.jsonArray ?: return WeatherForecast(
            city = cityObj?.get("name")?.jsonPrimitive?.content ?: "",
            entries = emptyList(),
            sunriseMs = sunriseMs,
            sunsetMs = sunsetMs
        )

        val entries = list.map { entry ->
            val e = entry.jsonObject
            WeatherEntry(
                timestampMs = e["dt"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: 0L,
                tempCelsius = e["main"]?.jsonObject?.get("temp")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                feelsLike = e["main"]?.jsonObject?.get("feels_like")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                humidity = e["main"]?.jsonObject?.get("humidity")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                windSpeed = e["wind"]?.jsonObject?.get("speed")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                condition = e["weather"]?.jsonArray?.firstOrNull()?.jsonObject?.get("main")?.jsonPrimitive?.content ?: "",
                description = e["weather"]?.jsonArray?.firstOrNull()?.jsonObject?.get("description")?.jsonPrimitive?.content ?: "",
                rainMm3h = e["rain"]?.jsonObject?.get("3h")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                snowMm3h = e["snow"]?.jsonObject?.get("3h")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                cloudsPercent = e["clouds"]?.jsonObject?.get("all")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                windGust = e["wind"]?.jsonObject?.get("gust")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            )
        }

        val city = cityObj?.get("name")?.jsonPrimitive?.content ?: ""

        return WeatherForecast(
            city = city,
            entries = entries,
            sunriseMs = sunriseMs,
            sunsetMs = sunsetMs
        )
    }
}

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
