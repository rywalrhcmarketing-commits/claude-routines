package pl.jarvis.app.proactive

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
 * - Aktualną pogodę
 * - Prognozę 5-dniową (co 3h) - używamy do "czy będzie padać za X minut"
 * - Geo lookup - miasto → współrzędne
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
        val list = obj["list"]?.jsonArray ?: return WeatherForecast(
            city = obj["city"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
            entries = emptyList()
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
                snowMm3h = e["snow"]?.jsonObject?.get("3h")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            )
        }

        val city = obj["city"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""

        return WeatherForecast(city = city, entries = entries)
    }
}

data class WeatherForecast(
    val city: String,
    val entries: List<WeatherEntry>
) {
    /**
     * Sprawdza czy będzie padać w danym oknie czasowym.
     */
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

data class WeatherEntry(
    val timestampMs: Long,
    val tempCelsius: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val condition: String,       // "Rain", "Snow", "Clear", "Clouds"
    val description: String,     // "lekki deszcz"
    val rainMm3h: Double = 0.0,
    val snowMm3h: Double = 0.0
)

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
