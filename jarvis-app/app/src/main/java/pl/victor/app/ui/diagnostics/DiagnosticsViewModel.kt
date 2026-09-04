package pl.victor.app.ui.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.VictorApplication
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.GlassesRecordings
import pl.victor.app.ble.GlassesSimulator
import pl.victor.app.ble.VictorManager
import pl.victor.app.ble.MediaCount
import pl.victor.app.ble.NotifyLogEntry
import pl.victor.app.power.BatteryOptimizationHelper

/**
 * ViewModel ekranu diagnostycznego.
 *
 * Ekran ma dwa zastosowania:
 * 1. **Przed przyjściem okularów** - włącz symulację i przejdź całą ścieżkę
 *    aplikacji, żeby wiedzieć, że wszystko poza sprzętem działa.
 * 2. **Po podłączeniu okularów** - podglądaj surowe ramki notify i sprawdzaj
 *    kolejne funkcje pojedynczo, zamiast zgadywać, co poszło nie tak.
 */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication
    private val manager: VictorManager = app.glassesManager

    val connectionState: StateFlow<ConnectionState> = manager.connectionState
    val batteryLevel: StateFlow<Int?> = manager.batteryLevel
    val isCharging: StateFlow<Boolean> = manager.isCharging
    val glassesIp: StateFlow<String?> = manager.glassesIp
    val mediaCount: StateFlow<MediaCount?> = manager.mediaCount
    val notifyLog: StateFlow<List<NotifyLogEntry>> = manager.notifyLog
    val lastCommand: StateFlow<String?> = manager.lastCommand
    val simulationEnabled: StateFlow<Boolean> = manager.simulationEnabled

    /** Wynik ostatniego testu - jedna linia do pokazania pod przyciskami. */
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Postęp pobierania nagrania po BLE. */
    val recordingProgress: StateFlow<Float?> = manager.recordingProgress

    /**
     * Czy Android ma wyłączoną optymalizację baterii dla V.I.C.T.O.R. To jest zwykle
     * prawdziwa przyczyna, gdy wake word albo połączenie z okularami "działa przez
     * chwilę, a potem samo się rozłącza" - Doze usypia proces, zanim cokolwiek
     * w kodzie zdąży zgłosić błąd. Odświeżane w onResume, bo to ekran Ustawień
     * systemowych, nie runtime permission z callbackiem.
     */
    private val _batteryExemptionGranted = MutableStateFlow(
        BatteryOptimizationHelper.isIgnoringOptimizations(application)
    )
    val batteryExemptionGranted: StateFlow<Boolean> = _batteryExemptionGranted.asStateFlow()

    fun refreshBatteryExemption() {
        _batteryExemptionGranted.value = BatteryOptimizationHelper.isIgnoringOptimizations(app)
    }

    /**
     * Numer typu pliku dla kanału nagrań. Producent go nie udokumentował,
     * więc użytkownik musi go znaleźć metodą prób - stąd suwak zamiast stałej.
     */
    private val _recordingFileType = MutableStateFlow(GlassesRecordings.DEFAULT_FILE_TYPE)
    val recordingFileType: StateFlow<Int> = _recordingFileType.asStateFlow()

    fun setRecordingFileType(type: Int) {
        _recordingFileType.value = type.coerceIn(RECORDING_FILE_TYPE_RANGE)
    }

    // === Tryb symulacji ===

    fun setSimulation(enabled: Boolean) {
        app.settings.setGlassesSimulationEnabled(enabled)
        manager.setSimulationEnabled(enabled)
        manager.initialize()
        _result.value = if (enabled) {
            "Symulacja włączona. Kliknij \"Połącz\", żeby zacząć."
        } else {
            "Symulacja wyłączona - aplikacja wróciła na prawdziwe BLE."
        }
    }

    /** W symulacji łączy od razu; na sprzęcie trzeba przejść przez ekran parowania. */
    fun connectSimulated() {
        val sim = manager.simulatorOrNull()
        if (sim == null) {
            _result.value = "To działa tylko w trybie symulacji - na sprzęcie użyj ekranu parowania."
            return
        }
        manager.connect(GlassesSimulator.SIMULATED_MAC)
    }

    fun disconnect() {
        manager.disconnect()
        _result.value = "Rozłączono."
    }

    // === Wstrzykiwanie zdarzeń (tylko symulacja) ===

    fun injectButtonPress() = withSimulator("Nie ma czego wstrzykiwać - symulacja wyłączona.") {
        it.pressButton()
        _result.value = "Wstrzyknięto wciśnięcie przycisku AI."
    }

    fun injectLowBattery() = withSimulator("Symulacja wyłączona.") {
        it.setBattery(9, isCharging = false)
        _result.value = "Ustawiono baterię na 9%."
    }

    fun injectLowMemory() = withSimulator("Symulacja wyłączona.") {
        it.signalLowMemory()
        _result.value = "Zgłoszono brak pamięci na okularach."
    }

    /**
     * Udaje wybudzenie po stronie okularów - to samo zdarzenie, które przychodzi,
     * gdy okulary usłyszą swoją frazę. Cała ścieżka rozmowy (audio -> nasłuch ->
     * AI -> odpowiedź głosem) da się dzięki temu przejść bez sprzętu na głowie.
     */
    fun injectWakeWord() = withSimulator("Symulacja wyłączona.") {
        it.requestAiSession(realtimeText = false)
        _result.value = "Zgłoszono wybudzenie - powinien ruszyć nasłuch."
    }

    /** Udaje dotknięcie zauszników w trakcie mówienia ("cicho"). */
    fun injectInterrupt() = withSimulator("Symulacja wyłączona.") {
        it.interruptSpeech()
        _result.value = "Zgłoszono przerwanie wypowiedzi."
    }

    /** Udaje zmianę głośności na zausznikach. */
    fun injectVolume() = withSimulator("Symulacja wyłączona.") {
        it.setVolume(7)
        _result.value = "Zgłoszono zmianę głośności."
    }

    /**
     * Sprawdza, czy telefon widzi okulary jako zestaw audio Bluetooth, i mówi
     * przez nie zdanie testowe.
     *
     * To najczęstsza przyczyna "okulary nie mówią": BLE jest połączone (bo to
     * inny kanał), ale część klasyczna nie została sparowana w ustawieniach
     * Bluetooth telefonu - i wtedy dźwięk idzie w głośnik telefonu.
     */
    fun testGlassesAudio() {
        viewModelScope.launch {
            val audio = app.audio
            if (!audio.hasBluetoothAudioDevice()) {
                _result.value = "Telefon NIE widzi żadnego zestawu audio Bluetooth.\n" +
                    "Okulary trzeba sparować osobno, w ustawieniach Bluetooth telefonu " +
                    "(BLE to inny kanał niż dźwięk). Jeśli ich tam nie ma - połącz się " +
                    "najpierw z aplikacji, bo dopiero wtedy okulary włączają część audio."
                return@launch
            }
            val held = audio.beginConversationRouting()
            try {
                val name = audio.conversationDeviceName() ?: "nieznane urządzenie"
                _result.value = if (held) {
                    "Mówię przez: $name.\nJeśli słyszysz to w okularach - audio działa."
                } else {
                    "Widzę $name, ale nie udało się przełączyć rozmowy na ten zestaw. " +
                        "Dźwięk pójdzie przez telefon."
                }
                audio.speakAndAwait(
                    "Test dźwięku. Jeśli mnie słyszysz w okularach, wszystko gra.",
                    language = app.settings.getResponseLanguage()
                )
            } finally {
                if (held) audio.endConversationRouting()
            }
        }
    }

    private inline fun withSimulator(onMissing: String, block: (GlassesSimulator) -> Unit) {
        val sim = manager.simulatorOrNull()
        if (sim == null) _result.value = onMissing else block(sim)
    }

    // === Testy funkcji - działają tak samo na symulatorze i na sprzęcie ===

    fun testBattery() {
        manager.requestBatteryLevel()
        _result.value = "Wysłano zapytanie o baterię - odpowiedź pojawi się w dzienniku."
    }

    fun testMediaCount() {
        manager.requestMediaCount { images, videos, records ->
            _result.value = "Na okularach: $images zdjęć, $videos wideo, $records nagrań."
        }
    }

    fun testPhoto() = runTest("Zdjęcie") {
        val bytes = manager.capturePhoto()
        if (bytes == null) {
            "Zdjęcie NIE dotarło (brak odpowiedzi w limicie czasu)."
        } else {
            "Zdjęcie OK: ${bytes.size} B, nagłówek ${bytes.take(2).joinToString(" ") { b ->
                "%02X".format(b)
            }}."
        }
    }

    fun testVideo() {
        manager.startVideoRecording()
        _result.value = "Rozpoczęto nagrywanie wideo - zatrzymaj drugim przyciskiem."
    }

    fun stopVideo() {
        manager.stopVideoRecording()
        _result.value = "Zatrzymano nagrywanie wideo."
    }

    fun testAudio() {
        manager.startAudioRecording()
        _result.value = "Rozpoczęto nagrywanie audio."
    }

    fun stopAudio() {
        manager.stopAudioRecording()
        _result.value = "Zatrzymano nagrywanie audio."
    }

    fun testTransferMode() {
        manager.enableTransferMode()
        _result.value = "Włączono tryb transferu - czekam na ramkę 0x08 z adresem IP."
    }

    fun testFileList() = runTest("Lista plików") {
        val files = manager.getMediaFileList()
        if (files.isEmpty()) "Okulary nie zgłosiły żadnych plików."
        else "Plików: ${files.size}. Pierwsze: ${files.take(3).joinToString(", ")}"
    }

    fun testDownloadPhoto() = runTest("Pobranie zdjęcia") {
        val bytes = manager.downloadLatestPhoto()
        if (bytes == null) "Nie udało się pobrać zdjęcia przez Wi-Fi Direct."
        else "Pobrano zdjęcie: ${bytes.size} B."
    }

    /**
     * Lista nagrań kanałem BLE - działa bez Wi-Fi Direct, więc jest to droga
     * awaryjna, gdy grupa P2P nie chce się podnieść.
     */
    fun testListRecordings() = runTest("Nagrania (BLE)") {
        val type = _recordingFileType.value
        val list = manager.listRecordings(type)
        if (list.isEmpty()) {
            "Typ $type: brak nagrań. Nagraj coś przyciskiem albo spróbuj innego typu pliku."
        } else {
            "Typ $type: ${list.size} nagrań. Pierwsze: " +
                list.take(3).joinToString(", ") { "${it.fileName} (${it.lengthBytes} B)" }
        }
    }

    fun testDownloadRecording() = runTest("Pobranie nagrania (BLE)") {
        val type = _recordingFileType.value
        val first = manager.listRecordings(type).firstOrNull()
            ?: return@runTest "Typ $type: nie ma czego pobrać."
        val bytes = manager.downloadRecording(first.fileName, type)
        if (bytes == null) "Nie udało się pobrać ${first.fileName}."
        else "Pobrano ${first.fileName}: ${bytes.size} B."
    }

    fun resetP2p() {
        manager.resetP2p()
        _result.value = "Wysłano reset P2P."
    }

    fun clearLog() {
        manager.clearNotifyLog()
        _result.value = null
    }

    /**
     * Uruchamia test w tle i zapisuje wynik. Wyjątki są łapane celowo -
     * ekran diagnostyczny ma pokazać błąd, a nie wywalić aplikację.
     */
    private companion object {
        /** Rozsądny zakres do przeszukania; typów jest niewiele. */
        val RECORDING_FILE_TYPE_RANGE = 0..7
    }

    private fun runTest(label: String, block: suspend () -> String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _result.value = "$label: w toku..."
            _result.value = try {
                block()
            } catch (e: Exception) {
                "$label - błąd: ${e.message ?: e::class.java.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }
}
