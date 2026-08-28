package pl.jarvis.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Walidator modeli - sprawdza przez API providera czy dany model nadal istnieje.
 *
 * Nie modyfikuje ModelRegistry - tylko raportuje co widzi.
 * Na podstawie tych danych SmartModelResolver decyduje co zrobić.
 */
class RemoteModelValidator(
    private val apiKey: String,
    private val providerId: String
) {
    private val tag = "RemoteModelValidator"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pobiera listę modeli dostępnych dla tego providera i klucza API.
     * Zwraca listę ID modeli (np. ["gemini-2.5-flash", "gemini-2.5-pro"]).
     * Pusta lista jeśli provider nie ma endpointu lub błąd.
     */
    suspend fun fetchAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            when (providerId) {
                "gemini" -> fetchGemini()
                "openai" -> fetchOpenAI()
                "claude" -> fetchClaude()
                "minimax" -> fetchMiniMax()
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to fetch models for $providerId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Sprawdza czy konkretny model istnieje u providera.
     */
    suspend fun isModelAvailable(modelId: String): Boolean {
        val available = fetchAvailableModels()
        return available.isEmpty() || available.contains(modelId)
    }

    private suspend fun fetchGemini(): List<String> {
        // GET https://generativelanguage.googleapis.com/v1beta/models?key=API_KEY
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "Gemini models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val models = obj["models"]?.jsonArray ?: return emptyList()
            return models.mapNotNull { element ->
                val model = element.jsonObject
                val name = model["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                // Format: "models/gemini-2.5-flash" -> "gemini-2.5-flash"
                name.removePrefix("models/")
            }
        }
    }

    private suspend fun fetchOpenAI(): List<String> {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "OpenAI models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val models = obj["data"]?.jsonArray ?: return emptyList()
            return models.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        }
    }

    private suspend fun fetchClaude(): List<String> {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=100")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "Anthropic models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val models = obj["data"]?.jsonArray ?: return emptyList()
            return models.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        }
    }

    private suspend fun fetchMiniMax(): List<String> {
        val request = Request.Builder()
            .url("https://api.minimax.io/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "MiniMax models list: HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val data = obj["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { element ->
                element.jsonObject["id"]?.jsonPrimitive?.content
            }
        }
    }
}

/**
 * Wynik walidacji modelu.
 */
sealed class ModelValidationResult {
    /** Model istnieje u providera i jest aktywny */
    object Available : ModelValidationResult()

    /** Model nie istnieje u providera (404 lub nie ma na liście) */
    object NotFound : ModelValidationResult()

    /** Model istnieje ale jest deprecated */
    data class Deprecated(val replacementId: String?) : ModelValidationResult()

    /** Nie udało się sprawdzić (błąd sieci, brak endpointu) */
    data class Unknown(val reason: String) : ModelValidationResult()
}
