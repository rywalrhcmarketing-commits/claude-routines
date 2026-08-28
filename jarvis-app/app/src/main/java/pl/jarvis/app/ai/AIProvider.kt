package pl.jarvis.app.ai

import pl.jarvis.app.vision.ScannedCode

/**
 * Abstrakcja providera AI - pozwala łatwo podmieniać Gemini / OpenAI / Claude / MiniMax
 * bez zmian w reszcie kodu.
 */
interface AIProvider {
    /** Unikalny identyfikator providera, np. "gemini", "openai" */
    val id: String

    /** Nazwa wyświetlana w UI */
    val displayName: String

    /** Czy provider sam obsługuje audio (np. Gemini Live) czy potrzebny Android TTS */
    val supportsNativeAudio: Boolean

    /** Czy provider ma wbudowany web search (np. Gemini grounding) */
    val supportsWebSearch: Boolean

    /** Czy provider wspiera streaming (SSE/chunked) */
    val supportsStreaming: Boolean get() = true

    /**
     * Możliwości providera (obrazy, wideo, audio, limity).
     * Decyduje jaki tryb capture możemy zastosować.
     */
    val capabilities: ProviderCapabilities get() = ProviderCapabilities()

    /**
     * Wysyła zapytanie do AI z opcjonalnymi zdjęciami i audio.
     *
     * @param textQuestion pytanie użytkownika (puste jeśli tylko audio)
     * @param images lista zdjęć JPEG/PNG z okularów
     * @param audioBytes opcjonalne nagranie audio pytania
     * @param scannedCodes QR kody wykryte na zdjęciach (są dołączane do promptu)
     * @param enableWebSearch czy włączyć wyszukiwanie w sieci
     * @param systemPrompt opcjonalny system prompt (persona AI). Null = użyj domyślnego.
     */
    suspend fun analyze(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray? = null,
        scannedCodes: List<ScannedCode> = emptyList(),
        enableWebSearch: Boolean = false,
        systemPrompt: String? = null
    ): AIResponse

    /**
     * Wersja streaming - zwraca Flow z fragmentami tekstu.
     * Implementacja domyślna: wywołuje analyze() i emituje pełny tekst jako jeden chunk.
     * Override w Gemini/OpenAI/Claude dla prawdziwego streamingu.
     */
    fun analyzeStream(
        textQuestion: String,
        images: List<ByteArray>,
        audioBytes: ByteArray? = null,
        scannedCodes: List<ScannedCode> = emptyList(),
        enableWebSearch: Boolean = false,
        systemPrompt: String? = null
    ): kotlinx.coroutines.flow.Flow<AIResponseChunk> = kotlinx.coroutines.flow.flow {
        val full = analyze(textQuestion, images, audioBytes, scannedCodes, enableWebSearch, systemPrompt)
        emit(AIResponseChunk(text = full.text, isFinal = true, sources = full.sources, tokensUsed = full.tokensUsed))
    }

    /**
     * Wersja z wideo (dla providerów z capabilities.supportsVideo=true).
     * Domyślnie: jeśli provider nie wspiera wideo, wyciąga klatki i używa analyze().
     *
     * @param videoBytes MP4/WebM bytes
     * @param videoDurationMs długość wideo (ms)
     */
    suspend fun analyzeVideo(
        textQuestion: String,
        videoBytes: ByteArray,
        videoDurationMs: Long,
        audioBytes: ByteArray? = null,
        scannedCodes: List<ScannedCode> = emptyList(),
        enableWebSearch: Boolean = false,
        systemPrompt: String? = null
    ): AIResponse {
        if (!capabilities.supportsVideo) {
            throw AIProviderException(
                "Provider $id nie obsługuje wideo. Użyj analyze() ze zdjęciami.",
                providerId = id,
                isRetryable = false
            )
        }
        // Domyślna implementacja - podmienij w providerach wspierających
        throw AIProviderException(
            "Provider $id nie implementuje analyzeVideo()",
            providerId = id,
            isRetryable = false
        )
    }

    /**
     * Wersja streaming z wideo.
     * Domyślnie: zwraca pełną odpowiedź z analyzeVideo() jako jeden chunk.
     */
    fun analyzeVideoStream(
        textQuestion: String,
        videoBytes: ByteArray,
        videoDurationMs: Long,
        audioBytes: ByteArray? = null,
        scannedCodes: List<ScannedCode> = emptyList(),
        enableWebSearch: Boolean = false,
        systemPrompt: String? = null
    ): kotlinx.coroutines.flow.Flow<AIResponseChunk> = kotlinx.coroutines.flow.flow {
        val full = analyzeVideo(
            textQuestion, videoBytes, videoDurationMs, audioBytes,
            scannedCodes, enableWebSearch, systemPrompt
        )
        emit(AIResponseChunk(text = full.text, isFinal = true, sources = full.sources, tokensUsed = full.tokensUsed))
    }
}

/**
 * Fragment odpowiedzi w streamingu.
 */
data class AIResponseChunk(
    /** Fragment tekstu (może być pełną odpowiedzią lub jej częścią) */
    val text: String,
    /** Czy to ostatni fragment */
    val isFinal: Boolean = false,
    /** Źródła (dostępne tylko w ostatnim fragmencie) */
    val sources: List<Source> = emptyList(),
    /** Tokeny (dostępne tylko w ostatnim fragmencie) */
    val tokensUsed: Int = 0
)

/**
 * Odpowiedź od AI - może zawierać tekst, audio, źródła.
 */
data class AIResponse(
    /** Tekst odpowiedzi (zawsze obecny) */
    val text: String,

    /** Opcjonalne audio (Base64 encoded) - jeśli provider daje TTS */
    val audioBase64: String? = null,

    /** Źródła z web search (np. cytaty z Google Search) */
    val sources: List<Source> = emptyList(),

    /** Ile tokenów zużyto (do debugowania kosztów) */
    val tokensUsed: Int = 0,

    /** Który provider odpowiedział */
    val providerId: String = ""
)

/**
 * Źródło cytowane przez AI (z web search)
 */
data class Source(
    val title: String,
    val url: String,
    val snippet: String = ""
)

/**
 * Wyjątek gdy provider nie odpowiada lub zwraca błąd
 */
class AIProviderException(
    message: String,
    val providerId: String = "",
    val isRetryable: Boolean = true
) : Exception(message)
