package pl.victor.app.localmodel

/**
 * Abstrakcja silnika lokalnego modelu - pozwala podmienić llama.cpp na coś
 * innego (np. LiteRT dla Gemmy) bez zmian w [pl.victor.app.ai.LocalAIProvider].
 */
interface LocalInferenceEngine {
    suspend fun loadModel(modelPath: String, contextSize: Int): Result<Unit>
    suspend fun unloadModel()
    fun isModelLoaded(): Boolean

    /**
     * Generuje odpowiedź dla już sformatowanego promptu (szablon czatu
     * stosowany wcześniej, patrz [PromptTemplates]). [onToken] jest wołane
     * per fragment tekstu w miarę generowania - z dowolnego wątku, wołający
     * odpowiada za ewentualne przejście na główny.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): Result<GenerationResult>

    suspend fun cancelGeneration()
}

data class GenerationResult(
    val fullText: String,
    val tokenCount: Int
)
