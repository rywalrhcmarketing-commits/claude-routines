package pl.victor.app.accessibility

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
import pl.victor.app.audio.AudioManager
import pl.victor.app.data.HistoryRepository
import pl.victor.app.vision.OCRReader
import pl.victor.app.vision.OCRResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility Service - funkcje dla niewidomych i słabowidzących.
 *
 * 3 tryby:
 * 1. READ_TEXT (czytaj) - czyta tekst z otoczenia (OCR + TTS)
 * 2. DESCRIBE_SCENE (opisuj) - opisuje co przed userem (capture + AI co X sekund)
 * 3. NAVIGATE (nawiguj) - ostrzega o przeszkodach + prowadzi
 *
 * Sterowanie głosowe:
 * - "V.I.C.T.O.R., czytaj" → READ_TEXT
 * - "V.I.C.T.O.R., opisz" → DESCRIBE_SCENE
 * - "V.I.C.T.O.R., prowadź" → NAVIGATE
 * - "V.I.C.T.O.R., stop" → kończy tryb
 * - "V.I.C.T.O.R., co przede mną" → jednorazowy opis
 *
 * Dźwiękowe sygnały:
 * - Nowa scena wykryta (inny obraz) → krótki "bip"
 * - Wykryto tekst → 2 krótkie "bip"
 * - Wykryto twarz → długi "bip"
 * - Niebezpieczeństwo (w trybie NAVIGATE) → ciągły sygnał
 */
class AccessibilityService(
    private val audio: AudioManager,
    private val ocrReader: OCRReader,
    private val glassesManager: pl.victor.app.ble.VictorManager,
    private val onDescribeScene: suspend (ByteArray) -> String,
    private val onNavigate: suspend (ByteArray) -> String
) {
    private val tag = "AccessibilityService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _mode = MutableStateFlow<AccessibilityMode>(AccessibilityMode.OFF)
    val mode: StateFlow<AccessibilityMode> = _mode.asStateFlow()

    private val _lastDescription = MutableStateFlow<String?>(null)
    val lastDescription: StateFlow<String?> = _lastDescription.asStateFlow()

    private val active = AtomicBoolean(false)
    private var workerJob: Job? = null

    // Konfiguracja
    var describeIntervalMs: Long = 5_000L      // co 5s opis
    var navigateIntervalMs: Long = 1_500L      // co 1.5s sprawdzenie
    var readPageTimeoutMs: Long = 10_000L      // ile czekamy aż user przewróci stronę

    /**
     * Włącza tryb czytania tekstu.
     * User klika "czytaj" - apka czeka aż wykryje tekst i czyta go.
     */
    fun enableReadText() {
        if (_mode.value != AccessibilityMode.OFF) return
        Log.i(tag, "Tryb READ_TEXT włączony")
        _mode.value = AccessibilityMode.READ_TEXT
        active.set(true)
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb czytania włączony. Skieruj okulary na tekst.", language = "pl")
        workerJob = scope.launch { readTextLoop() }
    }

    /**
     * Włącza tryb opisu otoczenia.
     * Apka co X sekund robi zdjęcie i opisuje co widzi.
     */
    fun enableDescribeScene() {
        if (_mode.value != AccessibilityMode.OFF) return
        Log.i(tag, "Tryb DESCRIBE_SCENE włączony")
        _mode.value = AccessibilityMode.DESCRIBE_SCENE
        active.set(true)
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb opisu włączony. Będę Ci mówił co widzisz.", language = "pl")
        workerJob = scope.launch { describeSceneLoop() }
    }

    /**
     * Włącza tryb nawigacji.
     * Ciągłe sprawdzanie otoczenia, ostrzeganie o przeszkodach.
     */
    fun enableNavigate() {
        if (_mode.value != AccessibilityMode.OFF) return
        Log.i(tag, "Tryb NAVIGATE włączony")
        _mode.value = AccessibilityMode.NAVIGATE
        active.set(true)
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb nawigacji włączony. Uważaj - będę Cię prowadził.", language = "pl")
        workerJob = scope.launch { navigateLoop() }
    }

    /**
     * Wyłącza aktywny tryb.
     */
    fun disable(reason: String = "user") {
        if (_mode.value == AccessibilityMode.OFF) return
        Log.i(tag, "Tryb ${_mode.value} wyłączony: $reason")
        _mode.value = AccessibilityMode.OFF
        active.set(false)
        workerJob?.cancel()
        workerJob = null
        playBeep(BeepType.MODE_CHANGED)
        audio.speak("Tryb wyłączony", language = "pl")
    }

    /**
     * Jednorazowy opis sceny (komenda "co przede mną").
     */
    suspend fun describeOnce(): String? {
        // Miniatura po BLE: jedna komenda robi świeże zdjęcie i odsyła bajty.
        val photo = glassesManager.capturePhoto() ?: return null
        return onDescribeScene(photo)
    }

    /**
     * Loop dla trybu czytania tekstu.
     * Ciągle skanuje - gdy wykryje nowy tekst, czyta go.
     */
    private suspend fun readTextLoop() {
        var lastReadText = ""
        while (active.get()) {
            try {
                // 1. Zrób zdjęcie i odbierz miniaturę po BLE
                val photo = glassesManager.capturePhoto()
                if (photo != null) {
                    // 3. OCR
                    val ocr = ocrReader.readBytes(photo)

                    if (ocr.isSuccess && ocr.fullText.isNotBlank()) {
                        val newText = ocr.fullText.trim()

                        // 4. Czy to inny tekst niż ostatnio?
                        if (newText != lastReadText && newText.length > 5) {
                            Log.d(tag, "Nowy tekst: ${newText.length} znaków")
                            playBeep(BeepType.TEXT_DETECTED)
                            audio.speak(newText, language = "pl")
                            _lastDescription.value = newText
                            lastReadText = newText

                            // Czekamy aż user powie "dalej" lub upłynie timeout
                            delay(readPageTimeoutMs)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "readTextLoop error", e)
            }
            delay(500)
        }
    }

    /**
     * Loop dla trybu opisu sceny.
     */
    private suspend fun describeSceneLoop() {
        var lastHash = 0
        while (active.get()) {
            try {
                val photo = glassesManager.capturePhoto()
                if (photo != null) {
                    val hash = photo.contentHashCode()
                    if (hash != lastHash) {
                        lastHash = hash
                        val description = onDescribeScene(photo)
                        if (description.isNotBlank()) {
                            playBeep(BeepType.NEW_SCENE)
                            audio.speak(description, language = "pl")
                            _lastDescription.value = description
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "describeSceneLoop error", e)
            }
            delay(describeIntervalMs)
        }
    }

    /**
     * Loop dla trybu nawigacji.
     */
    private suspend fun navigateLoop() {
        while (active.get()) {
            try {
                val photo = glassesManager.capturePhoto()
                if (photo != null) {
                    val alert = onNavigate(photo)
                    if (alert.isNotBlank()) {
                        // Alert nawigacyjny - krótszy, bardziej pilny
                        playBeep(BeepType.NAVIGATION_ALERT)
                        audio.speak(alert, language = "pl")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "navigateLoop error", e)
            }
            delay(navigateIntervalMs)
        }
    }

    /**
     * Sygnały dźwiękowe - pomagają użytkownikowi wiedzieć co się dzieje
     * nawet bez patrzenia na ekran.
     */
    private fun playBeep(type: BeepType) {
        // Tu można użyć ToneGenerator dla systemowych sygnałów
        // Na razie uproszczone - log + brak dźwięku
        Log.d(tag, "Beep: $type")
    }
}

enum class AccessibilityMode(val displayName: String, val emoji: String) {
    OFF("Wyłączony", "⏹️"),
    READ_TEXT("Czytanie tekstu", "📖"),
    DESCRIBE_SCENE("Opis otoczenia", "👁️"),
    NAVIGATE("Nawigacja", "🧭")
}

enum class BeepType {
    MODE_CHANGED,    // Tryb włączony/wyłączony
    TEXT_DETECTED,   // Wykryto nowy tekst
    NEW_SCENE,       // Nowa scena
    FACE_DETECTED,   // Wykryto twarz
    NAVIGATION_ALERT // Alert nawigacyjny
}
