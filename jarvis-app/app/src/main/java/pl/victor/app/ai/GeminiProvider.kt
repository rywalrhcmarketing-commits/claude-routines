package pl.victor.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.victor.app.vision.ScannedCode
import java.util.concurrent.TimeUnit

/**
 * Provider dla Google Gemini 2.5 Flash.
 * Obsługuje: text + image + audio IN, text OUT.
 * Wbudowany web search (Google Search grounding).
 *
 * Obsługuje oba formaty klucza:
 * - Stary: AIza... (Standard Key) - działa do września 2026
 * - Nowy: AQ.Ab... (Auth Key) - zalecany od 2026
 *
 * Używa NATYWNEGO endpointu (nie OpenAI-compatible):
 *   https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key=...
 *
 * UWAGA: Nie zawiera klucza API. Klucz przekazywany z SettingsRepository.
 */
class GeminiProvider(
    private val apiKey: String,
    val model: String = "gemini-2.5-flash"
) : AIProvider {

    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val supportsNativeAudio = false  // audio out przez Android TTS
    override val supportsWebSearch = true      // Google Search grounding

    /**
     * Gemini 1.5+ obsługuje: images, video, audio, streaming.
     * Max: 3600s wideo, 20MB inline / 2GB przez Files API.
     */
    override val capabilities = ProviderCapabilities(
        supportsImages = true,
        supportsVideo = true,         // Gemini 1.5+ ma video understanding
        supportsAudio = true,         // multimodal in
        maxImagesPerRequest = 16,
        maxVideoBytes = 20L * 1024 * 1024,   // 20MB inline
        maxImageBytes = 4L * 1024 * 1024,
        recommendedImageResolution = ImageResolution.MEDIUM,
        supportsStreaming = true,
        supportsFunctionCalling = true
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Analiza wideo (Gemini 1.5+ obsługuje natywnie).
     * Wysyła MP4 inline (max 20MB) do endpoint.
     */
    override suspend fun analyzeVideo(
        textQuestion: String,
        videoBytes: ByteArray,
        videoDurationMs: Long,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): AIResponse {
        if (videoBytes.size > capabilities.maxVideoBytes) {
            Log.w("GeminiProvider", "Wideo ${videoBytes.size} > max ${capabilities.maxVideoBytes}, " +
                    "próbuję zmniejszyć lub wrócić do analizy klatek")
            // Fallback - tu w produkcji byłaby kompresja lub extract frames
            // Na teraz rzucamy exception - lepiej wiedzieć
            throw AIProviderException(
                "Wideo za duże (${videoBytes.size} > ${capabilities.maxVideoBytes}). " +
                "Spróbuj krótszego lub niższej jakości.",
                providerId = id,
                isRetryable = false
            )
        }

        val url = buildString {
            append("https://generativelanguage.googleapis.com/v1beta/models/")
            append(model)
            append(":generateContent?key=")
            append(apiKey)
        }

        // Inline data z MIME type video/mp4
        val base64Video = android.util.Base64.encodeToString(videoBytes, android.util.Base64.NO_WRAP)
        val videoPart = buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", "video/mp4")
                put("data", base64Video)
            })
        }

        val textPart = buildJsonObject {
            put("text", textQuestion.ifBlank { "Co widzisz na tym wideo? Opisz szczegółowo." })
        }

        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(videoPart)
                    add(textPart)
                }
            })
        }

        val requestBody = buildJsonObject {
            put("contents", contents)
            if (systemPrompt != null) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemPrompt) })
                    }
                }
            }
            if (enableWebSearch) {
                putJsonArray("tools") {
                    add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
                }
            }
        }

        return try {
            val body = requestBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw AIProviderException(
                        "Gemini video API error: ${response.code} - $responseBody",
                        providerId = id,
                        isRetryable = response.code in 500..599
                    )
                }
                parseResponse(responseBody)
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            throw AIProviderException(
                "Video analysis failed: ${e.message}",
                providerId = id,
                isRetryable = true,
                cause = e
            )
        }
    }

    override suspend fun analyze(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): AIResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key is empty" }

        val url = "$API_BASE/$model:generateContent?key=$apiKey"

        // Zbuduj request do Gemini API
        val parts = mutableListOf<GeminiPart>()

        // 1. Zdjęcia (inline base64)
        images.forEach { imageBytes ->
            parts.add(
                GeminiPart(
                    inlineData = GeminiInlineData(
                        mimeType = "image/jpeg",
                        data = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                    )
                )
            )
        }

        // 2. Audio (opcjonalne)
        audioBytes?.let { audio ->
            parts.add(
                GeminiPart(
                    inlineData = GeminiInlineData(
                        mimeType = "audio/wav",
                        data = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                    )
                )
            )
        }

        // 3. Prompt (użyj persony jeśli podana)
        val prompt = buildPrompt(
            question = textQuestion,
            hasImages = images.isNotEmpty(),
            scannedCodes = scannedCodes,
            systemPrompt = systemPrompt
        )
        parts.add(GeminiPart(text = prompt))

        val tools = if (enableWebSearch) {
            listOf(GeminiTool(googleSearch = GoogleSearchTool()))
        } else null

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            tools = tools
        )

        val requestBody = json.encodeToString(GeminiRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw AIProviderException(
                        "Gemini API error ${response.code}: $errorBody",
                        providerId = id,
                        isRetryable = response.code in 500..599 || response.code == 429
                    )
                }

                val body = response.body?.string() ?: throw AIProviderException(
                    "Empty response body",
                    providerId = id
                )

                parseResponse(body)
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API call failed", e)
            throw AIProviderException(
                "Network error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }

    private fun buildPrompt(
        question: String,
        hasImages: Boolean,
        scannedCodes: List<ScannedCode> = emptyList(),
        systemPrompt: String? = null
    ): String {
        val baseContext = if (hasImages) {
            "User wysłał $IMAGES_IN_REQUEST zdjęć ze swojego otoczenia (POV) i zadaje pytanie."
        } else {
            "User zadaje pytanie tekstowe."
        }

        // Jeśli persona podana - użyj jej jako system prompt
        // W przeciwnym razie użyj domyślnego
        val systemContext = systemPrompt ?: """
            Jesteś asystentem AI wbudowanym w inteligentne okulary. Odpowiadaj zwięźle, po polsku.
            Jeśli widzisz coś na zdjęciach, opisz to krótko i odnieś do pytania.
            Nie używaj formatowania markdown. Mów naturalnie, jakbyś rozmawiał.
        """.trimIndent()

        // QR kody - specjalna obsługa
        val qrSection = if (scannedCodes.isNotEmpty()) {
            val codes = scannedCodes.joinToString("\n") { "- [${it.type}] ${it.describe()}" }
            "\n\nWykryte kody (QR/barcode) na zdjęciach:\n$codes\n\nJeśli to URL - odwiedź i streść. Jeśli wizytówka - przedstaw kontakt. Jeśli WiFi - powiedz hasło."
        } else {
            ""
        }

        val userPart = if (question.isNotBlank()) {
            "\n\nPytanie: $question"
        } else {
            "\n\n(Pytanie może być w audio - jeśli tak, odpowiedz na nie)"
        }

        return "$systemContext\n\n$baseContext$qrSection$userPart"
    }

    private fun parseResponse(body: String): AIResponse {
        val geminiResponse = json.decodeFromString(GeminiResponse.serializer(), body)

        val text = geminiResponse.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.filter { it.text != null }
            ?.joinToString(" ") { it.text!! }
            ?: throw AIProviderException("No text in Gemini response", providerId = id)

        val sources = geminiResponse.candidates
            ?.firstOrNull()
            ?.groundingMetadata
            ?.groundingChunks
            ?.mapNotNull { chunk ->
                chunk.web?.let { web ->
                    Source(
                        title = web.title ?: "",
                        url = web.uri ?: "",
                        snippet = ""
                    )
                }
            }
            ?: emptyList()

        val tokens = geminiResponse.usageMetadata?.totalTokenCount ?: 0

        return AIResponse(
            text = text.trim(),
            sources = sources,
            tokensUsed = tokens,
            providerId = id
        )
    }

    companion object {
        private const val TAG = "GeminiProvider"
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val STREAM_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val IMAGES_IN_REQUEST = 5
    }

    /**
     * Streaming przez Gemini API: streamGenerateContent
     * Zwraca SSE - każda linia "data: {...}" to fragment odpowiedzi.
     */
    override fun analyzeStream(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray?,
        scannedCodes: List<ScannedCode>,
        enableWebSearch: Boolean,
        systemPrompt: String?
    ): kotlinx.coroutines.flow.Flow<AIResponseChunk> = kotlinx.coroutines.flow.flow {
        require(apiKey.isNotBlank()) { "Gemini API key is empty" }

        val url = "$STREAM_API_BASE/$model:streamGenerateContent?alt=sse&key=$apiKey"

        val parts = mutableListOf<GeminiPart>()
        images.forEach { imageBytes ->
            parts.add(GeminiPart(inlineData = GeminiInlineData(
                mimeType = "image/jpeg",
                data = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            )))
        }
        val prompt = buildPrompt(textQuestion, images.isNotEmpty(), scannedCodes, systemPrompt)
        parts.add(GeminiPart(text = prompt))

        val tools = if (enableWebSearch) listOf(GeminiTool(googleSearch = GoogleSearchTool())) else null

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            tools = tools
        )

        val requestBody = json.encodeToString(GeminiRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw AIProviderException(
                        "Gemini API error ${response.code}: $errorBody",
                        providerId = id,
                        isRetryable = response.code in 500..599 || response.code == 429
                    )
                }

                val source = response.body?.source() ?: throw AIProviderException(
                    "No response body", providerId = id
                )

                // Parsuj SSE: każda linia "data: {...}\n\n"
                val fullText = StringBuilder()
                var totalTokens = 0

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.removePrefix("data: ").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                        try {
                            val chunk = json.decodeFromString(
                                GeminiResponse.serializer(), jsonStr
                            )
                            chunk.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                                part.text?.let { textFragment ->
                                    fullText.append(textFragment)
                                    emit(AIResponseChunk(
                                        text = textFragment,
                                        isFinal = false
                                    ))
                                }
                            }
                            chunk.usageMetadata?.totalTokenCount?.let { totalTokens = it }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse chunk: ${e.message}")
                        }
                    }
                }

                // Ostatni chunk - z summary
                emit(AIResponseChunk(
                    text = "",
                    isFinal = true,
                    tokensUsed = totalTokens
                ))
            }
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            throw AIProviderException(
                "Streaming error: ${e.message}",
                providerId = id,
                isRetryable = true
            )
        }
    }.flowOn(Dispatchers.IO)
}

// === Gemini API request/response DTOs (snake_case jak w ich API) ===

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String  // base64
)

@Serializable
data class GeminiTool(
    val googleSearch: GoogleSearchTool? = null
)

@Serializable
data class GoogleSearchTool(
    val dynamicRetrievalConfig: DynamicRetrievalConfig? = null
)

@Serializable
data class DynamicRetrievalConfig(
    val mode: String = "MODE_DYNAMIC",
    val dynamicThreshold: Double = 0.3
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val usageMetadata: GeminiUsageMetadata? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val groundingMetadata: GroundingMetadata? = null
)

@Serializable
data class GroundingMetadata(
    val groundingChunks: List<GroundingChunk>? = null
)

@Serializable
data class GroundingChunk(
    val web: WebSource? = null
)

@Serializable
data class WebSource(
    val uri: String? = null,
    val title: String? = null
)

@Serializable
data class GeminiUsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)
