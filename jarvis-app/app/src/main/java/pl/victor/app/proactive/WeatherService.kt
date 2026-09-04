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
