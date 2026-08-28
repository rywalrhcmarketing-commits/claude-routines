package pl.jarvis.app.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jarvis.app.audio.AudioManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Detektor wake word używający Porcupine (Picovoice).
 *
 * On-device, offline, ultra-low-latency. Obsługuje wiele języków i custom keywords.
 *
 * Jak uzyskać AccessKey:
 * 1. Zarejestruj się na https://console.picovoice.ai/ (darmowy tier)
 * 2. Utwórz projekt
 * 3. Skopiuj AccessKey
 * 4. Wpisz w Ustawieniach aplikacji
 *
 * Domyślne wbudowane keywords (angielskie):
 * - "jarvis" (Iron Man)
 * - "computer"
 * - "alexa"
 * - "hey siri" (uwaga: może kolidować z iPhone)
 * - "ok google"
 * - "porcupine"
 *
 * Dla polskiego trzeba wytrenować custom keyword w konsoli Picovoice.
 */
class WakeWordDetector(
    private val context: Context
) {
    private val tag = "WakeWordDetector"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var porcupine: Porcupine? = null
    private var audioManager: VoiceProcessor? = null
    private var isListening = false
    private var selectedKeyword: String = "jarvis"

    // Event po wykryciu
    private val _detectionEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val detectionEvent: SharedFlow<String> = _detectionEvent.asSharedFlow()

    // Stan
    private val _state = MutableStateFlow(WakeWordState.IDLE)
    val state: kotlinx.coroutines.flow.StateFlow<WakeWordState> = _state.asStateFlow()

    /**
     * Inicjalizuje Porcupine z danym AccessKey.
     * @return true jeśli OK
     */
    suspend fun initialize(accessKey: String, keyword: String = "jarvis"): Boolean {
        if (!hasRecordPermission()) {
            Log.w(tag, "Brak RECORD_AUDIO permission")
            _state.value = WakeWordState.NO_PERMISSION
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // Cleanup stary
                release()

                porcupine = Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(keyword)
                    .build()

                selectedKeyword = keyword
                _state.value = WakeWordState.READY
                Log.i(tag, "Porcupine initialized with keyword: $keyword")
                true
            } catch (e: PorcupineException) {
                Log.e(tag, "Porcupine init failed", e)
                _state.value = WakeWordState.ERROR
                false
            } catch (e: Exception) {
                Log.e(tag, "Porcupine init failed", e)
                _state.value = WakeWordState.ERROR
                false
            }
        }
    }

    /**
     * Rozpoczyna nasłuchiwanie w tle.
     */
    fun startListening() {
        if (isListening) return
        val p = porcupine ?: run {
            Log.w(tag, "Porcupine not initialized")
            return
        }

        if (!hasRecordPermission()) {
            _state.value = WakeWordState.NO_PERMISSION
            return
        }

        try {
            audioManager = VoiceProcessor.Builder()
                .sampleRate(p.sampleRate)
                .frameLength(p.frameLength)
                .build()

            audioManager?.addListener(object : VoiceProcessor.VoiceProcessorListener {
                override fun onFrame(pcm: ShortArray) {
                    try {
                        val result = porcupine?.process(pcm) ?: -1
                        if (result >= 0) {
                            // Wykryto wake word!
                            Log.i(tag, "Wake word detected: $selectedKeyword")
                            _detectionEvent.tryEmit(selectedKeyword)
                        }
                    } catch (e: PorcupineException) {
                        Log.e(tag, "Process error", e)
                    }
                }
            })

            audioManager?.start()
            isListening = true
            _state.value = WakeWordState.LISTENING
            Log.i(tag, "Started listening for '$selectedKeyword'")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start listening", e)
            _state.value = WakeWordState.ERROR
        }
    }

    /**
     * Zatrzymuje nasłuchiwanie.
     */
    fun stopListening() {
        if (!isListening) return
        try {
            audioManager?.stop()
            isListening = false
            _state.value = WakeWordState.READY
            Log.i(tag, "Stopped listening")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping", e)
        }
    }

    /**
     * Cleanup zasobów.
     */
    fun release() {
        stopListening()
        try {
            porcupine?.delete()
        } catch (_: Exception) {}
        porcupine = null
        _state.value = WakeWordState.IDLE
    }

    private fun hasRecordPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lista wbudowanych keywords (angielskie).
     */
    companion object {
        val BUILT_IN_KEYWORDS = listOf(
            "jarvis",
            "computer",
            "alexa",
            "hey siri",
            "ok google",
            "picovoice",
            "bumblebee",
            "grasshopper",
            "porcupine",
            "terminator"
        )

        // Wymagane uprawnienia
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO
        )
    }
}

/**
 * Stan detektora.
 */
enum class WakeWordState {
    IDLE,           // nie zainicjalizowany
    READY,          // zainicjalizowany, nie nasłuchuje
    LISTENING,      // nasłuchuje
    NO_PERMISSION,  // brak RECORD_AUDIO
    ERROR           // błąd
}

/**
 * Wrapper na VoiceProcessor (Picovoice) - opakowuje dostęp do mikrofonu.
 *
 * UWAGA: VoiceProcessor w wersji 3.x ma inne API niż Porcupine 2.x.
 * Ten wrapper izoluje różnice.
 */
class VoiceProcessor private constructor(
    val sampleRate: Int,
    val frameLength: Int
) {
    private val listeners = mutableListOf<VoiceProcessorListener>()
    private val recorder = android.media.AudioRecord(
        android.media.MediaRecorder.AudioSource.MIC,
        sampleRate,
        android.media.AudioFormat.CHANNEL_IN_MONO,
        android.media.AudioFormat.ENCODING_PCM_16BIT,
        sampleRate * 2  // buffer size
    )
    private var running = false
    private var thread: Thread? = null

    interface VoiceProcessorListener {
        fun onFrame(pcm: ShortArray)
    }

    fun addListener(l: VoiceProcessorListener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun start() {
        if (running) return
        running = true
        recorder.startRecording()
        thread = thread(name = "VoiceProcessor") {
            val frame = ShortArray(frameLength)
            while (running) {
                val read = recorder.read(frame, 0, frame.size)
                if (read > 0) {
                    synchronized(listeners) {
                        listeners.toList().forEach { it.onFrame(frame) }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: Exception) {}
        thread = null
        try {
            recorder.stop()
        } catch (_: Exception) {}
    }

    data class Builder(
        private var sampleRate: Int = 16000,
        private var frameLength: Int = 512
    ) {
        fun sampleRate(rate: Int) = apply { sampleRate = rate }
        fun frameLength(length: Int) = apply { frameLength = length }
        fun build() = VoiceProcessor(sampleRate, frameLength)
    }
}
