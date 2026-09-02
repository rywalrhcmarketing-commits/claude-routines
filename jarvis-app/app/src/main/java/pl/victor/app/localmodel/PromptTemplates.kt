package pl.victor.app.localmodel

/**
 * Formatowanie promptu pod konkretny model - llama.cpp dostaje gotowy,
 * płaski tekst, nie wie nic o rolach system/user/assistant.
 */
object PromptTemplates {
    /** Qwen/ChatML - jedyny szablon na razie, bo katalog ma tylko Qwen. */
    fun qwenChat(systemPrompt: String, userMessage: String): String = buildString {
        append("<|im_start|>system\n")
        append(systemPrompt.ifBlank { "Jesteś pomocnym asystentem." }.trim())
        append("\n<|im_end|>\n")
        append("<|im_start|>user\n")
        append(userMessage.trim())
        append("\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    /** Sekwencje zatrzymujące generowanie - model powtórzyłby je w kółko bez tego. */
    val QWEN_STOP_SEQUENCES = listOf("<|im_end|>", "<|im_start|>")
}
