package pl.victor.app.localmodel

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Silnik llama.cpp dla modeli GGUF (na razie: Qwen3.5 0.8B), przez wiązanie
 * `io.github.ljcamargo:llamacpp-kotlin` (JitPack, patrz build.gradle.kts).
 *
 * `startEngine(params, callback)` jest wołane przez refleksję, mimo że to
 * biblioteka trzecia i teoretycznie mogłaby być wołana wprost - tak samo
 * robi to jedyna znana nam działająca, wydana aplikacja używająca tego
 * wiązania (CyanBridge), więc to sprawdzone w praktyce zachowanie, a nie
 * ostrożność na wyrost. `launchCompletion`/`stopCompletion`/`releaseContext`
 * są w tamtej aplikacji wołane wprost - te zostają typowane.
 *
 * Callback tokenów jest rejestrowany RAZ przy ładowaniu modelu (`startEngine`)
 * i żyje tak długo jak kontekst - `generate()` nie przekazuje własnego
 * callbacku do `launchCompletion`, tylko podmienia [tokenCollector] na czas
 * jednego wywołania. To nie jest oczywiste z samego API, ale bez tego
 * generowanie kończy się bez ani jednego tokenu.
 */
class LlamaCppInferenceEngine : LocalInferenceEngine {

    private var engine: LlamaAndroid? = null
    private var contextId: Int? = null
    private var loadedPath: String? = null
    private val tokenCallbackLock = Any()
    private var tokenCollector: ((String) -> Unit)? = null
    private val tokenCounter = AtomicInteger(0)

    override suspend fun loadModel(modelPath: String, contextSize: Int): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            if (loadedPath == modelPath && contextId != null) return@withContext

            val file = File(modelPath)
            require(file.exists()) { "Plik modelu nie istnieje: $modelPath" }

            unloadModel()

            val llama = engine ?: createLlamaAndroid().also { engine = it }
            val modelFd = openModelFd(file)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val batch = (contextSize / 8).coerceIn(32, 128)
            val params = mapOf(
                "model" to Uri.fromFile(file).toString(),
                "model_fd" to modelFd,
                "n_ctx" to contextSize,
                "n_batch" to batch,
                "n_threads" to threads,
                "n_gpu_layers" to 0,
                "use_mmap" to true,
                "use_mlock" to false,
            )

            var startedOk = false
            val result: Map<*, *>? = try {
                val callback: (String) -> Unit = { token ->
                    synchronized(tokenCallbackLock) { tokenCollector?.invoke(token) }
                }
                val startEngine = llama.javaClass.methods.first {
                    it.name == "startEngine" && it.parameterTypes.size == 2
                }
                (startEngine.invoke(llama, params, callback) as? Map<*, *>).also {
                    startedOk = it != null
                }
            } finally {
                // Przy sukcesie natywna strona przejmuje fd (dup) - zamknięcie tutaj
                // powoduje podwójne zamknięcie (fdsan abort) na niektórych urządzeniach.
                // Zamykamy WYŁĄCZNIE gdy start się nie powiódł (wyciek deskryptora).
                if (!startedOk) {
                    runCatching { ParcelFileDescriptor.adoptFd(modelFd).close() }
                }
            }

            val newContextId = (result?.get("contextId") as? Number)?.toInt()
                ?: throw IllegalStateException("Silnik lokalny nie zwrócił contextId")

            contextId = newContextId
            loadedPath = modelPath
            Log.i(TAG, "Model lokalny załadowany: $modelPath (contextId=$newContextId, wątki=$threads)")
        }
    }

    override suspend fun unloadModel() {
        withContext(Dispatchers.IO) {
            val llama = engine
            val ctx = contextId
            if (llama != null && ctx != null) {
                runCatching { llama.releaseContext(ctx) }
            }
            contextId = null
            loadedPath = null
        }
    }

    override fun isModelLoaded(): Boolean = contextId != null

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): Result<GenerationResult> = runCatching {
        val llama = engine ?: throw IllegalStateException("Silnik nie jest zainicjalizowany")
        val ctx = contextId ?: throw IllegalStateException("Model nie jest załadowany")

        tokenCounter.set(0)
        val fullText = StringBuilder()
        synchronized(tokenCallbackLock) {
            tokenCollector = { token ->
                tokenCounter.incrementAndGet()
                fullText.append(token)
                onToken(token)
            }
        }

        val params = mapOf(
            "prompt" to prompt,
            "emit_partial_completion" to true,
            "temperature" to GENERATION_TEMPERATURE,
            "top_p" to GENERATION_TOP_P,
            "top_k" to GENERATION_TOP_K,
            "n_predict" to maxTokens,
            "penalty_repeat" to GENERATION_REPETITION_PENALTY,
            "seed" to -1,
            "stop" to PromptTemplates.QWEN_STOP_SEQUENCES,
        )

        val result = try {
            withContext(Dispatchers.IO) { llama.launchCompletion(ctx, params) }
        } finally {
            synchronized(tokenCallbackLock) { tokenCollector = null }
        }

        val text = fullText.toString().ifBlank { (result?.get("text") as? String).orEmpty() }
        GenerationResult(fullText = text, tokenCount = tokenCounter.get())
    }

    override suspend fun cancelGeneration() {
        withContext(Dispatchers.IO) {
            val llama = engine ?: return@withContext
            val ctx = contextId ?: return@withContext
            runCatching { llama.stopCompletion(ctx) }
        }
    }

    private fun createLlamaAndroid(): LlamaAndroid = LlamaAndroid()

    private fun openModelFd(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return pfd.detachFd()
    }

    private companion object {
        const val TAG = "LlamaCppEngine"
        const val GENERATION_TEMPERATURE = 0.7
        const val GENERATION_TOP_P = 0.9
        const val GENERATION_TOP_K = 40
        const val GENERATION_REPETITION_PENALTY = 1.1
    }
}
