package pl.jarvis.app.translation

import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tłumacz symultaniczny - offline, on-device.
 *
 * Używa ML Kit Translation:
 * - Modele pobierane przy pierwszym użyciu (~30MB na parę języków)
 * - Potem w pełni offline
 * - Obsługuje 50+ języków
 *
 * Workflow:
 * 1. User mówi "tłumacz" lub wybiera tryb
 * 2. Apka słucha lub czyta tekst (OCR/ASR)
 * 3. Tłumaczy na żywo
 * 4. Mówi przez TTS
 */
class SimultaneousTranslator {

    private val tag = "SimultaneousTranslator"
    private val translators = mutableMapOf<String, Translator>()

    /**
     * Pobiera (lub tworzy) tłumacz dla pary języków.
     */
    private suspend fun getTranslator(from: String, to: String): Translator {
        val key = "$from-$to"
        return translators.getOrPut(key) {
            Log.i(tag, "Tworzę tłumacz $from → $to")
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(from)
                .setTargetLanguage(to)
                .build()
            val translator = Translation.getClient(options)
            // Czekaj na pobranie modelu
            awaitDownload(translator)
            translator
        }
    }

    private suspend fun awaitDownload(translator: Translator) {
        suspendCancellableCoroutine<Unit> { cont ->
            translator.downloadModelIfNeeded()
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    /**
     * Tłumaczy tekst.
     */
    suspend fun translate(text: String, from: String, to: String): String {
        if (text.isBlank() || from == to) return text
        return try {
            val translator = getTranslator(from, to)
            val result = suspendCancellableCoroutine<String> { cont ->
                translator.translate(text)
                    .addOnSuccessListener { translated -> cont.resume(translated) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
            Log.d(tag, "Translated ${text.length} chars: ${result.take(50)}...")
            result
        } catch (e: Exception) {
            Log.e(tag, "Translation failed", e)
            text  // fallback - zwróć oryginał
        }
    }

    /**
     * Tłumaczy z auto-detect źródłowego języka.
     * Wymaga najpierw rozpoznania języka (np. przez OCR z locale).
     */
    suspend fun translateAuto(text: String, to: String): String {
        // Domyślnie zakładamy PL→target lub EN→target
        // Prawdziwa detekcja wymaga Language Detection API (osobna zależność)
        return translate(text, "pl", to)
    }

    /**
     * Czyści zasoby.
     */
    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    companion object {
        /**
         * Mapowanie języków polskich na ML Kit.
         */
        val SUPPORTED_LANGUAGES = mapOf(
            "pl" to TranslateLanguage.POLISH,
            "en" to TranslateLanguage.ENGLISH,
            "de" to TranslateLanguage.GERMAN,
            "fr" to TranslateLanguage.FRENCH,
            "es" to TranslateLanguage.SPANISH,
            "it" to TranslateLanguage.ITALIAN,
            "pt" to TranslateLanguage.PORTUGUESE,
            "ru" to TranslateLanguage.RUSSIAN,
            "uk" to TranslateLanguage.UKRAINIAN,
            "ja" to TranslateLanguage.JAPANESE,
            "ko" to TranslateLanguage.KOREAN,
            "zh" to TranslateLanguage.CHINESE,
            "ar" to TranslateLanguage.ARABIC,
            "cs" to TranslateLanguage.CZECH,
            "sk" to TranslateLanguage.SLOVAK,
            "nl" to TranslateLanguage.DUTCH,
            "sv" to TranslateLanguage.SWEDISH
        )

        /**
         * Ładny label dla języka.
         */
        fun languageName(code: String): String = when (code) {
            "pl" -> "Polski"
            "en" -> "English"
            "de" -> "Deutsch"
            "fr" -> "Français"
            "es" -> "Español"
            "it" -> "Italiano"
            "pt" -> "Português"
            "ru" -> "Русский"
            "uk" -> "Українська"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "zh" -> "中文"
            "ar" -> "العربية"
            "cs" -> "Čeština"
            "sk" -> "Slovenčina"
            "nl" -> "Nederlands"
            "sv" -> "Svenska"
            else -> code
        }
    }
}

/**
 * Stan trybu tłumacza symultanicznego.
 */
sealed class TranslationState {
    object Idle : TranslationState()
    data class Listening(val fromLang: String, val toLang: String) : TranslationState()
    data class Translating(val text: String) : TranslationState()
    data class Result(val original: String, val translated: String,
                     val from: String, val to: String) : TranslationState()
}
