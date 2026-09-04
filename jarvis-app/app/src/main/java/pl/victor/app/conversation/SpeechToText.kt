package pl.victor.app.conversation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Rozpoznawanie mowy przez systemowy [SpeechRecognizer].
 *
 * Bez tego [ConversationalMode] nie miał skąd wziąć tekstu: `deliverSpeech()`
 * nie było wołane z żadnego miejsca w aplikacji, więc tryb konwersacyjny
 * zawsze kończył się timeoutem, nie usłyszawszy ani słowa.
 *
 * ## Dwie pułapki, które to API ma
 * 1. `SpeechRecognizer` **musi** być tworzony i wołany z wątku głównego -
 *    z innego rzuca wyjątkiem. Stąd `withContext(Dispatchers.Main)`.
 * 2. Instancja jest jednorazowa w praktyce: po `onError`/`onResults` bywa
 *    w stanie, z którego kolejne `startListening()` nie wraca. Dlatego każde
 *    nasłuchiwanie dostaje świeżą instancję, zwalnianą w `finally`.
 *
 * Mikrofon jest wyłączny - jeśli trzyma go wykrywanie słowa kluczowego
 * (Porcupine), rozpoznawanie nie dostanie dźwięku. Zatrzymanie go na czas
 * słuchania należy do wołającego.
 */
class SpeechToText(private val context: Context) {

    private val tag = TAG
    private val bluetoothRouter = pl.victor.app.audio.BluetoothAudioRouter.getInstance(context)

    /** Czy urządzenie w ogóle ma rozpoznawanie mowy (emulator bywa go pozbawiony). */
    fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Słucha jednej wypowiedzi i zwraca rozpoznany tekst.
     *
     * Próbuje najpierw urządzenia audio Bluetooth (patrz [pl.victor.app.audio.BluetoothAudioRouter]) -
     * bez podłączonego urządzenia albo gdy się nie uda, wraca na mikrofon
     * telefonu bez dodatkowego kroku. Negocjacja Bluetooth idzie POZA
     * [timeoutMs], żeby nie zjadała budżetu czasu na samo słuchanie.
     *
     * @param languageTag język w formacie BCP-47 (np. `pl-PL`); domyślnie z ustawień systemu
     * @param timeoutMs twardy limit - `SpeechRecognizer` potrafi nie oddać sterowania
     * @return rozpoznany tekst albo `null` przy ciszy, błędzie lub przekroczeniu czasu
     */
    suspend fun listen(
        languageTag: String = Locale.getDefault().toLanguageTag(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        if (!isAvailable()) {
            Log.w(tag, "Rozpoznawanie mowy niedostępne na tym urządzeniu")
            return null
        }
        // Router jest zliczany: gdy orkiestrator trzyma łącze na całą rozmowę,
        // to wywołanie tylko dokłada odwołanie i nie ma żadnej przerwy w dźwięku.
        val usedBluetooth = bluetoothRouter.acquire()
        try {
            return withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main) { listenOnMainThread(languageTag) }
            }
        } finally {
            if (usedBluetooth) bluetoothRouter.release()
        }
    }

    private suspend fun listenOnMainThread(languageTag: String): String? =
        suspendCancellableCoroutine { continuation ->
            val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                .getOrNull()
            if (recognizer == null) {
                Log.w(tag, "Nie udało się utworzyć SpeechRecognizer")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            // onError i onResults potrafią przyjść oba - wznawiamy dokładnie raz.
            val resumed = AtomicBoolean(false)
            fun finish(result: String?) {
                if (resumed.compareAndSet(false, true)) {
                    runCatching { recognizer.destroy() }
                    continuation.resume(result)
                }
            }

            recognizer.setRecognitionListener(listener(::finish))
            continuation.invokeOnCancellation {
                // Anulowanie (np. z withTimeoutOrNull) przychodzi z dowolnego wątku,
                // a SpeechRecognizer wolno ruszać tylko z głównego - stąd post().
                if (resumed.compareAndSet(false, true)) {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { recognizer.cancel() }
                        runCatching { recognizer.destroy() }
                    }
                }
            }

            runCatching { recognizer.startListening(intent(languageTag)) }
                .onFailure {
                    Log.w(tag, "startListening nie powiodło się", it)
                    finish(null)
                }
        }

    private fun listener(finish: (String?) -> Unit) = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
            Log.d(TAG, "Rozpoznano: ${text ?: "(nic)"}")
            finish(text)
        }

        override fun onError(error: Int) {
            // Cisza i brak dopasowania to normalny koniec nasłuchiwania, nie awaria.
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (quiet) {
                Log.d(TAG, "Cisza albo brak dopasowania (kod $error)")
            } else {
                Log.w(TAG, "Błąd rozpoznawania: ${describeError(error)}")
            }
            finish(null)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun intent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /** Opis kodu błędu - inaczej w logu zostaje sama liczba. */
    internal fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "problem z nagrywaniem dźwięku"
        SpeechRecognizer.ERROR_CLIENT -> "błąd po stronie klienta"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "brak uprawnienia RECORD_AUDIO"
        SpeechRecognizer.ERROR_NETWORK -> "błąd sieci"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "przekroczony czas odpowiedzi sieci"
        SpeechRecognizer.ERROR_NO_MATCH -> "nic nie rozpoznano"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "rozpoznawanie zajęte (mikrofon zajęty?)"
        SpeechRecognizer.ERROR_SERVER -> "błąd serwera rozpoznawania"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "cisza"
        else -> "nieznany błąd ($error)"
    }

    companion object {
        private const val TAG = "SpeechToText"

        /**
         * Jedna wypowiedź rzadko trwa dłużej. Limit jest twardy, bo
         * `SpeechRecognizer` potrafi nie oddać sterowania po zajęciu mikrofonu.
         */
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
