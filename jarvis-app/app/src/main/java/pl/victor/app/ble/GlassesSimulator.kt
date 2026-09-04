package pl.victor.app.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/**
 * Symulator okularów HeyCyan - pozwala przejść całą ścieżkę aplikacji
 * (przycisk → zdjęcie → AI → TTS) bez sprzętu, na emulatorze albo na telefonie.
 *
 * ## Co jest udawane, a co prawdziwe
 * Udawany jest **wyłącznie transport**: zamiast wysyłać bajty przez BLE i czekać
 * na ramkę z okularów, symulator sam składa ramkę notify i oddaje ją tą samą
 * ścieżką, którą idą ramki ze sprzętu. Dekodowanie ([GlassesProtocol.decodeNotify]),
 * aktualizacja stanu w [VictorManager], reakcja UI i cała warstwa AI działają
 * na prawdziwym kodzie. Dzięki temu test na symulatorze realnie coś sprawdza.
 *
 * Symulator odwzorowuje też rzeczy, które psują aplikacje w terenie:
 * opóźnienia migawki, rozładowywanie baterii, rutynowy błąd P2P przed podaniem IP
 * oraz - opcjonalnie - awarie (patrz [Faults]).
 *
 * Klasa nie zależy od Androida, więc da się ją odpalić w testach jednostkowych.
 */
class GlassesSimulator(
    private val scope: CoroutineScope,
    private val timings: Timings = Timings(),
    private val photos: SimulatedPhotoSource = EmbeddedPhotoSource,
    var faults: Faults = Faults(),
    private val onNotify: (ByteArray) -> Unit
) {

    /**
     * Opóźnienia symulatora. Wartości domyślne odpowiadają temu, co widać na
     * prawdziwych okularach; w testach wstawia się zera, żeby nie czekać.
     */
    data class Timings(
        val connectMs: Long = 1_200,
        val serviceDiscoveryMs: Long = 800,
        /** Ile okulary mielą zdjęcie, zanim zgłoszą ramkę 0x02. */
        val shutterMs: Long = 1_800,
        /** Transfer miniatury po BLE. */
        val thumbnailMs: Long = 900,
        /** Ile trwa podniesienie grupy Wi-Fi Direct i przydzielenie IP. */
        val transferModeMs: Long = 2_500,
        val otaStepMs: Long = 400,
        val batteryReplyMs: Long = 150,
        val httpLatencyMs: Long = 300
    ) {
        companion object {
            /** Bez czekania - do testów jednostkowych. */
            val INSTANT = Timings(0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    /**
     * Wstrzykiwane awarie - do sprawdzenia, czy aplikacja nie wiesza się
     * na błędach, które prawdziwe okulary potrafią zgłosić.
     */
    data class Faults(
        /** Okulary nie odsyłają ramki 0x02 - aplikacja musi wejść na wariant zapasowy. */
        val dropPhotoReadyNotify: Boolean = false,
        /** Tryb transferu nigdy nie podaje IP - pobieranie plików ma się poddać. */
        val transferModeNeverGivesIp: Boolean = false,
        /** Miniatura wraca pusta. */
        val emptyThumbnail: Boolean = false,
        /** Bateria startuje na krytycznym poziomie. */
        val startBatteryPercent: Int = 87
    )

    // === Stan symulowanego urządzenia ===

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var recordingVideo: Boolean = false
        private set

    @Volatile
    var recordingAudio: Boolean = false
        private set

    /** Ostatnia komenda, jaką aplikacja wysłała - dla ekranu diagnostycznego. */
    @Volatile
    var lastCommand: String? = null
        private set

    private var batteryPercent: Int = faults.startBatteryPercent
    private var charging: Boolean = false

    private var imageCount: Int = 3
    private var videoCount: Int = 1
    private var recordCount: Int = 2

    private var photoIndex: Int = 0
    private val files: MutableList<String> = mutableListOf(
        "IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg",
        "VID_0001.mp4",
        "REC_0001.opus", "REC_0002.opus"
    )

    /** IP, które symulator poda po wejściu w tryb transferu. */
    val simulatedIp: String = SIMULATED_IP

    // === Cykl życia ===

    /** Urządzenie widoczne w skanowaniu BLE. */
    fun advertisedDevice(): DiscoveredDevice =
        DiscoveredDevice(address = SIMULATED_MAC, name = SIMULATED_NAME, rssi = -54)

    /**
     * Symuluje połączenie. Wywołuje [onState] w kolejnych fazach, tak jak
     * robiłyby to broadcasty vendor SDK.
     */
    fun connect(onState: (ConnectionState) -> Unit) {
        onState(ConnectionState.CONNECTING)
        scope.launch {
            delay(timings.connectMs)
            connected = true
            onState(ConnectionState.CONNECTED)
            delay(timings.serviceDiscoveryMs)
            onState(ConnectionState.READY)
            // Prawdziwe okulary po połączeniu same zgłaszają baterię.
            emit(GlassesProtocol.batteryFrame(batteryPercent, charging))
        }
    }

    fun disconnect() {
        connected = false
        recordingVideo = false
        recordingAudio = false
    }

    // === Komendy ===

    /**
     * Przyjmuje komendę wysłaną przez [VictorManager] i odgrywa reakcję okularów.
     * Zwraca opis komendy, żeby diagnostyka miała co pokazać.
     */
    fun handleCommand(command: ByteArray): String {
        val description = GlassesProtocol.describeCommand(command)
        lastCommand = description

        if (GlassesProtocol.isMediaCountRequest(command)) {
            return description
        }

        when (GlassesProtocol.workTypeOf(command)) {
            GlassesProtocol.WORK_PHOTO -> takePhoto()
            GlassesProtocol.WORK_AI_PHOTO -> takePhoto()
            GlassesProtocol.WORK_VIDEO_START -> recordingVideo = true
            GlassesProtocol.WORK_VIDEO_STOP -> stopVideo()
            GlassesProtocol.WORK_AUDIO_START -> recordingAudio = true
            GlassesProtocol.WORK_AUDIO_STOP -> stopAudio()
            GlassesProtocol.WORK_TRANSFER -> enterTransferMode()
            GlassesProtocol.WORK_RESET_P2P -> Unit
            GlassesProtocol.WORK_OTA -> runOta()
        }
        return description
    }

    private fun takePhoto() {
        scope.launch {
            delay(timings.shutterMs)
            imageCount++
            files.add(String.format(Locale.US, "IMG_%04d.jpg", imageCount + 1))
            drainBattery(1)
            if (!faults.dropPhotoReadyNotify) {
                emit(GlassesProtocol.photoReadyFrame())
            }
        }
    }

    private fun stopVideo() {
        if (!recordingVideo) return
        recordingVideo = false
        videoCount++
        files.add(String.format(Locale.US, "VID_%04d.mp4", videoCount))
        drainBattery(3)
    }

    private fun stopAudio() {
        if (!recordingAudio) return
        recordingAudio = false
        recordCount++
        files.add(String.format(Locale.US, "REC_%04d.opus", recordCount))
        drainBattery(1)
    }

    private fun enterTransferMode() {
        scope.launch {
            // Prawdziwe okulary rutynowo rzucają błędem P2P 255, zanim podniosą grupę.
            emit(GlassesProtocol.p2pErrorFrame(255))
            delay(timings.transferModeMs)
            if (!faults.transferModeNeverGivesIp) {
                emit(GlassesProtocol.glassesIpFrame(SIMULATED_IP))
            }
        }
    }

    private fun runOta() {
        scope.launch {
            for (percent in 0..100 step 20) {
                delay(timings.otaStepMs)
                emit(GlassesProtocol.otaProgressFrame(percent, 0, 0))
            }
            for (percent in 0..100 step 25) {
                delay(timings.otaStepMs)
                emit(GlassesProtocol.otaProgressFrame(100, percent, 0))
            }
        }
    }

    /** Odpowiednik `syncBattery()` - odsyła ramkę 0x05. */
    fun requestBattery() {
        scope.launch {
            delay(timings.batteryReplyMs)
            emit(GlassesProtocol.batteryFrame(batteryPercent, charging))
        }
    }

    fun mediaCount(): MediaCount = MediaCount(imageCount, videoCount, recordCount)

    // === Sterowanie z ekranu diagnostycznego ===

    /** Udaje wciśnięcie fizycznego przycisku AI na okularach. */
    fun pressButton() {
        emit(GlassesProtocol.buttonPressedFrame())
    }

    /** Udaje samo zgłoszenie gotowego zdjęcia (bez migawki). */
    fun signalPhotoReady() {
        emit(GlassesProtocol.photoReadyFrame())
    }

    fun setBattery(percent: Int, isCharging: Boolean = charging) {
        batteryPercent = percent.coerceIn(0, 100)
        charging = isCharging
        emit(GlassesProtocol.batteryFrame(batteryPercent, charging))
    }

    fun signalLowMemory() {
        emit(GlassesProtocol.lowMemoryFrame())
    }

    /**
     * Udaje wybudzenie słowem kluczowym po stronie okularów - czyli to, co
     * okulary wysyłają po `aiVoiceWake(true)`, gdy usłyszą swoją frazę.
     * Dzięki temu całą ścieżkę rozmowy da się przejść bez sprzętu.
     */
    fun requestAiSession(realtimeText: Boolean = false) {
        emit(GlassesProtocol.aiSessionFrame(realtimeText))
    }

    /** Udaje uciszenie asystenta dotknięciem zauszników. */
    fun interruptSpeech() {
        emit(GlassesProtocol.interruptSpeechFrame())
    }

    /** Udaje zmianę głośności na zausznikach. */
    fun setVolume(level: Int) {
        emit(GlassesProtocol.volumeFrame(level))
    }

    /** Wstrzykuje dowolną ramkę - do odtwarzania ramek zebranych ze sprzętu. */
    fun injectFrame(frame: ByteArray) {
        emit(frame)
    }

    // === Dane multimedialne ===

    /** Bajty miniatury, którą "przysłałyby" okulary. */
    suspend fun thumbnail(): ByteArray? {
        delay(timings.thumbnailMs)
        if (faults.emptyThumbnail) return null
        return photos.photoBytes(photoIndex++)
    }

    suspend fun mediaFileList(): List<String> {
        delay(timings.httpLatencyMs)
        return files.toList()
    }

    suspend fun fileBytes(filename: String): ByteArray {
        delay(timings.httpLatencyMs)
        if (filename !in files) {
            throw VictorException("Symulator: brak pliku $filename")
        }
        return when {
            filename.endsWith(".jpg", ignoreCase = true) -> photos.photoBytes(photoIndex++)
            // Wideo i audio to atrapy - aplikacja i tak tylko zapisuje je na dysk.
            else -> ByteArray(SIMULATED_BLOB_BYTES) { (it % 251).toByte() }
        }
    }

    /** Nagrania widoczne kanałem `RecordHandle` (BLE, bez Wi-Fi Direct). */
    suspend fun recordings(): List<Recording> {
        delay(timings.httpLatencyMs)
        return files.filter { it.startsWith("REC_") }
            .map { Recording(fileName = it, lengthBytes = SIMULATED_RECORDING_BYTES) }
    }

    suspend fun recordingBytes(fileName: String): ByteArray? {
        delay(timings.thumbnailMs)
        if (fileName !in files) return null
        return ByteArray(SIMULATED_RECORDING_BYTES) { (it % 251).toByte() }
    }

    private fun drainBattery(percent: Int) {
        batteryPercent = max(0, batteryPercent - percent)
    }

    private fun emit(frame: ByteArray) {
        onNotify(frame)
    }

    companion object {
        const val SIMULATED_MAC = "00:11:22:33:44:55"
        const val SIMULATED_NAME = "HeyCyan-SYM"
        const val SIMULATED_IP = "192.168.49.1"
        private const val SIMULATED_BLOB_BYTES = 64 * 1024
        private const val SIMULATED_RECORDING_BYTES = 24 * 1024
    }
}

/** Źródło bajtów zdjęcia dla symulatora. */
interface SimulatedPhotoSource {
    /**
     * @param index numer kolejnego zdjęcia w sesji - implementacja może
     *              zwracać różne sceny, żeby kolejne zapytania do AI
     *              nie dostawały wciąż tego samego obrazu
     */
    fun photoBytes(index: Int): ByteArray
}

/**
 * Awaryjne źródło zdjęć: osadzony, poprawny baseline JPEG 8x8.
 *
 * Nie zależy od Androida, więc działa w testach jednostkowych. Na urządzeniu
 * i na emulatorze [pl.victor.app.ble.CanvasPhotoSource] rysuje czytelne sceny.
 */
object EmbeddedPhotoSource : SimulatedPhotoSource {

    private val jpeg: ByteArray by lazy {
        java.util.Base64.getDecoder().decode(BASE64_JPEG_8X8)
    }

    override fun photoBytes(index: Int): ByteArray = jpeg.copyOf()

    /** Baseline JPEG 8x8, jeden komponent (skala szarości). */
    private const val BASE64_JPEG_8X8 =
        "/9j/2wBDABAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ" +
            "EBAQEBAQEBAQEBAQEBAQEBD/wAALCAAIAAgBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAEC" +
            "AwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEI" +
            "I0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZn" +
            "aGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJ" +
            "ytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/ACv/2Q=="
}
