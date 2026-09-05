package pl.victor.app.conversation

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.victor.app.audio.AudioManager
import pl.victor.app.wakeword.WakeWordDetector
import pl.victor.app.wakeword.WakeWordState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tryb konwersacyjny - po odpowiedzi AI apka automatycznie słucha kolejnego pytania.
 *
 * Workflow:
 * 1. User mówi pytanie → AI odpowiada
 * 2. AI kończy mówić (TTS done)
 * 3. Apka czeka 800ms (cisza)
 * 4. Apka zaczyna słuchać (continuous mode)
 * 5. User mówi nowe pytanie
 * 6. Powrót do kroku 2
 *
 * Wyłączany gdy:
 * - User powie "koniec" / "stop" / "dziękuję"
 * - Upłynie 30s bez pytania (timeout)
 * - User naciśnie przycisk fizyczny
 * - User włączy tryb manualny w settings
 */
class ConversationalMode(
    private val audio: AudioManager,
    private val wakeWord: WakeWordDetector? = null,
    private val speechToText: SpeechToText? = null,
    private val onUserSpoke: suspend (String) -> Unit,
    private val onActivated: () -> Unit = {},
    private val onDeactivated: () -> Unit = {}
) {
    private val tag = "ConversationalMode"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            pl.victor.app.utils.loggingExceptionHandler(tag)
    )

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastActivityTime = MutableStateFlow(0L)
    val lastActivityTime: StateFlow<Long> = _lastActivityTime.asStateFlow()

    private val active = AtomicBoolean(false)
    private var listenJob: Job? = null

    // Konfiguracja
    /** Język rozpoznawania w formacie BCP-47 - ustawiany z preferencji użytkownika. */
    var recognitionLanguageTag: String = "pl-PL"
    var listenTimeoutMs: Long = 30_000L  // 30s bez aktywności = wyłącz
    var postAnswerDelayMs: Long = 800L   // cisza po odpowiedzi
    var silenceThreshold: Int = 3        // ile "ciszy" zanim kończymy nagrywanie

    /**
     * Włącza tryb konwersacyjny.
     */
    fun enable() {
        if (_enabled.value) return
        Log.i(tag, "Tryb konwersacyjny WŁĄCZONY")
        _enabled.value = true
        active.set(true)
        onActivated()
        startListeningLoop()
    }

    /**
     * Wyłącza tryb.
     */
    fun disable(reason: String = "manual") {
        if (!_enabled.value) return
        Log.i(tag, "Tryb konwersacyjny WYŁĄCZONY: $reason")
        _enabled.value = false
        active.set(false)
        listenJob?.cancel()
        listenJob = null
        _isListening.value = false
        onDeactivated()
    }

    /**
     * Hook wywoływany gdy AI skończy mówić.
     * Rozpoczyna nasłuchiwanie kolejnego pytania.
     */
    fun onAiFinishedSpeaking() {
        if (!_enabled.value) return
        Log.d(tag, "AI skończył mówić, startuję nasłuchiwanie...")
        listenJob?.cancel()
        listenJob = scope.launch {
            delay(postAnswerDelayMs)
            if (active.get()) {
                startSingleListen()
            }
        }
    }

    /**
     * Hook wywoływany gdy TTS jest w trakcie.
     * Pauzuje nasłuchiwanie żeby nie łapać echa.
     */
    fun onAiStartedSpeaking() {
        listenJob?.cancel()
        _isListening.value = false
    }

    /**
     * Sprawdza czy tekst to komenda wyłączająca.
     */
    fun isExitCommand(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in listOf(
            "koniec", "stop", "zakończ", "dziękuję", "dziekuje",
            "wyłącz", "wylacz", "wyjdź", "wyjdz", "pauza",
            "koniec rozmowy", "do widzenia", "pa pa", "nara"
        )
    }

    private companion object {
        /** Odstęp odpytywania [deliverSpeech] w wariancie zapasowym. */
        const val POLL_INTERVAL_MS = 200L
    }

    private fun startListeningLoop() {
        listenJob = scope.launch {
            while (active.get()) {
                startSingleListen()
                if (!active.get()) break
                delay(100)  // krótka pauza przed ponownym słuchaniem
            }
        }
    }

    private suspend fun startSingleListen() {
        if (!active.get()) return
        _isListening.value = true
        _lastActivityTime.value = System.currentTimeMillis()

        try {
            val result = withTimeoutOrNull(listenTimeoutMs) {
                waitForUserSpeech()
            }

            if (result == null) {
                Log.d(tag, "Timeout nasłuchiwania - wyłączam tryb")
                disable(reason = "timeout")
                return
            }

            if (isExitCommand(result)) {
                Log.i(tag, "Exit command: $result")
                disable(reason = "exit command")
                audio.speak("Do widzenia", language = "pl")
                return
            }

            // Przetwórz pytanie
            onUserSpoke(result)
        } catch (e: Exception) {
            Log.e(tag, "Listen error", e)
        } finally {
            _isListening.value = false
        }
    }

    /**
     * Czeka na wypowiedź użytkownika.
     *
     * Główną drogą jest systemowe rozpoznawanie mowy. Gdy go nie ma (emulator
     * bez pakietu rozpoznawania, brak uprawnienia), schodzimy na odpytywanie
     * [speechResult] - dzięki temu [deliverSpeech] nadal działa jako punkt
     * wstrzyknięcia dla testów i dla innych źródeł tekstu.
     */
    private suspend fun waitForUserSpeech(): String? {
        val stt = speechToText
        if (stt != null && stt.isAvailable()) {
            return listenWithRecognizer(stt)
        }
        Log.d(tag, "Brak rozpoznawania mowy - czekam na deliverSpeech()")
        return pollDeliveredSpeech()
    }

    /**
     * Mikrofon jest wyłączny: dopóki Porcupine czyta z `AudioRecord`,
     * [SpeechRecognizer] dostanie ERROR_RECOGNIZER_BUSY albo ciszę. Dlatego
     * wykrywanie słowa kluczowego jest wstrzymywane na czas nasłuchiwania
     * i wznawiane w `finally`, także gdy rozpoznawanie rzuci wyjątkiem.
     */
    /**
     * Jedno nasłuchanie, ze zwolnieniem mikrofonu na czas rozpoznawania.
     *
     * Publiczne, bo z tej samej ochrony musi korzystać KAŻDY, kto otwiera
     * mikrofon: tryb konwersacyjny, przycisk "Powiedz" i wybudzenie z okularów.
     * Bez niej wykrywanie słowa kluczowego trzyma `AudioRecord`, a
     * `SpeechRecognizer` dostaje ERROR_RECOGNIZER_BUSY albo samą ciszę - i z
     * perspektywy użytkownika "mikrofon nie działa".
     *
     * @return rozpoznany tekst albo `null` przy ciszy, błędzie lub braku STT
     */
    suspend fun listenOnce(languageTag: String = recognitionLanguageTag): String? {
        val stt = speechToText ?: return null
        if (!stt.isAvailable()) return null
        return listenWithRecognizer(stt, languageTag)
    }

    private suspend fun listenWithRecognizer(
        stt: SpeechToText,
        languageTag: String = recognitionLanguageTag
    ): String? {
        val wakeWordWasRunning = wakeWord?.state?.value == WakeWordState.LISTENING
        if (wakeWordWasRunning) {
            Log.d(tag, "Wstrzymuję wykrywanie słowa kluczowego - zwalniam mikrofon")
            runCatching { wakeWord?.stopListening() }
                .onFailure { Log.w(tag, "Nie udało się zatrzymać wake worda", it) }
        }
        return try {
            stt.listen(languageTag = languageTag)
        } finally {
            if (wakeWordWasRunning) {
                runCatching { wakeWord?.startListening() }
                    .onFailure { Log.w(tag, "Nie udało się wznowić wake worda", it) }
            }
        }
    }

    private suspend fun pollDeliveredSpeech(): String? {
        repeat((listenTimeoutMs / POLL_INTERVAL_MS).toInt()) { _ ->
            speechResult.value?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
        return speechResult.value
    }

    private val speechResult = MutableStateFlow<String?>(null)

    /**
     * Zewnętrznie - user mógł mówić przez STT.
     * Tu dostarczamy tekst do nasłuchiwania.
     */
    fun deliverSpeech(text: String) {
        speechResult.value = text
    }

    /**
     * Czyści dostarczony tekst (przed następnym nasłuchiwaniem).
     */
    fun clearSpeech() {
        speechResult.value = null
    }

    /**
     * Sprawdza czy tryb jest aktywny.
     */
    fun isActive(): Boolean = active.get()

    /**
     * Ile czasu zostało do timeoutu (w sekundach).
     */
    fun remainingSeconds(): Int {
        if (!_enabled.value) return 0
        val elapsed = System.currentTimeMillis() - _lastActivityTime.value
        return ((listenTimeoutMs - elapsed) / 1000).toInt().coerceAtLeast(0)
    }
}
