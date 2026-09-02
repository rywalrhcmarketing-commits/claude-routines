package pl.victor.app.localmodel

/**
 * Jeden wpis katalogu lokalnych modeli - na razie tylko Qwen3.5 0.8B (GGUF,
 * silnik llama.cpp). To jedyny model, który realnie zmieści się na tanim
 * telefonie z ~4GB RAM (telefon użytkownika: Samsung A23 5G).
 *
 * Gemma przez LiteRT/MediaPipe (wyższe progi RAM) to osobny silnik - celowo
 * pominięty w tym pierwszym przejściu, żeby nie dokładać drugiej zależności
 * natywnej bez realnego urządzenia do przetestowania. Dodanie kolejnych
 * wpisów tutaj (i drugiego [LocalInferenceEngine]) nie wymaga zmian gdzie
 * indziej - [pl.victor.app.ai.LocalAIProvider] czyta tylko [minRamGb]/[engine].
 */
data class LocalModelCatalogEntry(
    val id: String,
    val displayName: String,
    val engine: String,
    val sourceUrl: String,
    val expectedFilename: String,
    val sizeBytes: Long,
    val quantization: String,
    val contextSize: Int,
    /**
     * Próg RAM. Celowo niższy niż nominalna "4GB" telefonu: Android raportuje
     * mniej niż nominał (rezerwacje OS/sprzętu), więc telefon sprzedawany
     * jako "4GB" bywa zgłaszany jako ok. 3.4-3.8GB przez ActivityManager.
     * Model waży ~560MB, więc 3.0GB to wciąż bezpieczny, realny próg - nie
     * chcemy blokować dokładnie tego telefonu, dla którego to budujemy.
     */
    val minRamGb: Double,
    val minFreeStorageGb: Double,
    val shortDescription: String
)

object LocalModelCatalog {
    val QWEN_0_8B = LocalModelCatalogEntry(
        id = "qwen3.5-0.8b-q4",
        displayName = "Qwen3.5 0.8B (Q4_0)",
        engine = ENGINE_LLAMA_CPP,
        sourceUrl = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_0.gguf",
        expectedFilename = "Qwen3.5-0.8B-Q4_0.gguf",
        sizeBytes = 563_000_000L,
        quantization = "Q4_0",
        contextSize = 2048,
        minRamGb = 3.0,
        minFreeStorageGb = 1.0,
        shortDescription = "Mały model tekstowy - działa offline, bez internetu i bez kluczy API."
    )

    val all: List<LocalModelCatalogEntry> = listOf(QWEN_0_8B)

    fun findById(id: String?): LocalModelCatalogEntry? = all.firstOrNull { it.id == id }

    const val ENGINE_LLAMA_CPP = "llama_cpp"
}
