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
     * Ile razy dana awaria wystąpiła z rzędu. Zero znaczy "następną zgłoś".
     *
     * ## Dlaczego to w ogóle istnieje
     * Wszystkie trzy tryby dostępności działają w pętli i po każdym
     * niepowodzeniu po prostu leciały dalej. Dla osoby widzącej to niezauważalne
     * opóźnienie; dla niewidomej jedynym objawem jest CISZA - nie do odróżnienia
     * od niedziałającej aplikacji. Zgłoszono to dwa razy: raz jako "asystent
     * niewidomych w ogóle nie działa", raz jako "słychać, że okulary robią
     * zdjęcia, ale AI nic nie mówi".
     */
    private val failureCounters = mutableMapOf<String, Int>()

    /**
     * Mówi o awarii - ale przy trwałej usterce nie za każdym obrotem pętli,
     * bo to zamieniłoby pomoc w hałas.
     */
    private fun reportFailure(key: String, message: String) {
        val seen = failureCounters[key] ?: 0
        if (seen == 0) audio.speak(message, language = "pl")
        failureCounters[key] = (seen + 1) % FAILURES_BETWEEN_REPORTS
    }

    /** Po udanym obrocie kasujemy licznik - następna awaria ma być słyszalna. */
    private fun clearFailure(key: String) {
        failureCounters[key] = 0
    }

    /**
     * Robi zdjęcie, a gdy się nie uda - MÓWI, czemu.
     *
     * @param sharp czy potrzebny jest ORYGINAŁ z pamięci okularów zamiast
     *   miniatury. Kosztuje kilkanaście sekund (Wi-Fi Direct), więc używa go
     *   tylko czytanie tekstu - i dopiero wtedy, gdy miniatura nie wystarczyła.
     */
    private suspend fun capturePhotoOrExplain(sharp: Boolean = false): ByteArray? {
        val photo = if (sharp) {
            glassesManager.captureSharpPhoto()
        } else {
            glassesManager.capturePhoto()
        }
        if (photo != null) {
            clearFailure(FAILURE_PHOTO)
            return photo
        }
        reportFailure(
            FAILURE_PHOTO,
            "Nie mam obrazu z okularów. " +
                (glassesManager.lastPhotoFailure ?: "Okulary nie przysłały zdjęcia.")
        )
        return null
    }

    /**
     * Pyta model o opis i MÓWI, gdy zapytanie się nie uda.
     *
     * Bez tego każdy błąd sieci, brak klucza API i limit u dostawcy kończyły
     * się wpisem w dzienniku i ciszą - a użytkownik słyszał tylko migawkę
     * okularów i nic więcej.
     */
    private suspend fun askOrExplain(
        photo: ByteArray,
        key: String,
        ask: suspend (ByteArray) -> String
    ): String? = try {
        val answer = ask(photo)
        clearFailure(key)
        answer.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(tag, "Zapytanie do modelu nie powiodło się", e)
        reportFailure(
            key,
            "Nie mogę teraz zapytać asystenta. " +
                (e.message?.take(MAX_SPOKEN_ERROR) ?: "Sprawdź internet i klucz API.")
        )
        null
    }

    /**
     * Jednorazowy opis sceny (komenda "co przede mną").
     */
    suspend fun describeOnce(): String? {
        val photo = capturePhotoOrExplain() ?: return null
        return askOrExplain(photo, FAILURE_DESCRIBE, onDescribeScene)
    }

    /**
     * Loop dla trybu czytania tekstu.
     *
     * ## Dwa podejścia, nie jedno
     * Najpierw miniatura po BLE - jest w sekundę i do dużego druku (szyld,
     * nagłówek, tablica) w zupełności wystarcza. Dopiero gdy OCR nic na niej nie
     * znajdzie, sięgamy po ORYGINAŁ przez Wi-Fi Direct: kilkanaście sekund, ale
     * to jedyna droga do drobnego druku. Zgłoszone jako "AI nie potrafi
     * rozczytać większości tekstu ze zdjęć" - bo do tej pory istniała tylko
     * miniatura i nic poza nią.
     */
    private suspend fun readTextLoop() {
        var lastReadText = ""
        while (active.get()) {
            try {
                var ocr: OCRResult? = capturePhotoOrExplain()?.let { ocrReader.readBytes(it) }

                if (ocr?.isSuccess != true || ocr.fullText.isBlank()) {
                    if (!active.get()) break
                    Log.i(tag, "Miniatura bez tekstu - biorę zdjęcie w pełnej jakości")
                    audio.speak("Przyglądam się dokładniej.", language = "pl")
                    ocr = capturePhotoOrExplain(sharp = true)?.let { ocrReader.readBytes(it) }
                }

                val newText = ocr?.fullText?.trim().orEmpty()
                if (ocr?.isSuccess == true && newText.length > MIN_READABLE_TEXT) {
                    clearFailure(FAILURE_NO_TEXT)
                    if (newText != lastReadText) {
                        Log.d(tag, "Nowy tekst: ${newText.length} znaków")
                        playBeep(BeepType.TEXT_DETECTED)
                        audio.speak(newText, language = "pl")
                        _lastDescription.value = newText
                        lastReadText = newText
                        // Czekamy, aż user przewróci stronę albo przesunie wzrok.
                        delay(readPageTimeoutMs)
                    }
                } else {
                    reportFailure(
                        FAILURE_NO_TEXT,
                        "Nie widzę tu tekstu. Skieruj okulary prosto na napis " +
                            "i przybliż się."
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "readTextLoop error", e)
                reportFailure(FAILURE_READ, "Czytanie się nie powiodło. Próbuję dalej.")
            }
            delay(READ_LOOP_INTERVAL_MS)
        }
    }

    /**
     * Loop dla trybu opisu sceny.
     */
    private suspend fun describeSceneLoop() {
        var lastHash = 0
        while (active.get()) {
            try {
                val photo = capturePhotoOrExplain()
                if (photo != null) {
                    val hash = photo.contentHashCode()
                    if (hash != lastHash) {
                        lastHash = hash
                        val description = askOrExplain(photo, FAILURE_DESCRIBE, onDescribeScene)
                        if (description != null) {
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
                val photo = capturePhotoOrExplain()
                if (photo != null) {
                    val alert = askOrExplain(photo, FAILURE_NAVIGATE, onNavigate)
                    if (alert != null) {
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

    private companion object {
        /**
         * Co ile nieudanych zdjęć powtarzamy komunikat. Przy trwałej awarii
         * pętla kręci się co kilka sekund - mówienie za każdym razem zamieniłoby
         * pomoc w hałas.
         */
        const val FAILURES_BETWEEN_REPORTS = 10

        /** Klucze liczników - każda awaria ma własny, żeby jedna nie uciszała drugiej. */
        const val FAILURE_PHOTO = "photo"
        const val FAILURE_DESCRIBE = "describe"
        const val FAILURE_NAVIGATE = "navigate"
        const val FAILURE_NO_TEXT = "no_text"
        const val FAILURE_READ = "read"

        /** Ile znaków komunikatu błędu wypowiadamy - reszta to i tak stos wywołań. */
        const val MAX_SPOKEN_ERROR = 120

        /** Krótszy tekst to zwykle szum OCR, nie napis. */
        const val MIN_READABLE_TEXT = 5

        /**
         * Odstęp między obrotami pętli czytania. Dłuższy niż dawne 500 ms, bo
         * jeden obrót potrafi teraz zawierać zdjęcie w pełnej jakości.
         */
        const val READ_LOOP_INTERVAL_MS = 1_500L
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
