package pl.jarvis.app.conversation

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
import pl.jarvis.app.audio.AudioManager
import pl.jarvis.app.wakeword.WakeWordDetector
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
    @Suppress("unused") private val wakeWord: WakeWordDetector? = null,
    private val onUserSpoke: suspend (String) -> Unit,
    private val onActivated: () -> Unit = {},
    private val onDeactivated: () -> Unit = {}
) {
    private val tag = "ConversationalMode"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastActivityTime = MutableStateFlow(0L)
    val lastActivityTime: StateFlow<Long> = _lastActivityTime.asStateFlow()

    private val active = AtomicBoolean(false)
    private var listenJob: Job? = null

    // Konfiguracja
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
            // Czekaj na speech recognition - tu trzeba podłączyć STT
            // Na razie placeholder - czekamy na integrację
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
     * Czeka na speech-to-text.
     * W prawdziwej implementacji użyje Android SpeechRecognizer.
     * Na razie - polling na speechResult.
     */
    private suspend fun waitForUserSpeech(): String? {
        // Poll speechResult co 200ms
        repeat((listenTimeoutMs / 200).toInt()) { _ ->
            speechResult.value?.let { return it }
            kotlinx.coroutines.delay(200)
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
