package pl.jarvis.app.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * JarvisManager - warstwa dostępu do okularów HeyCyan (vendor SDK `glasses_sdk_20250723_v01.aar`).
 *
 * Implementacja oparta o zweryfikowane API AAR (javap na classes.jar) oraz o protokół
 * potwierdzony w działającej aplikacji referencyjnej CyanBridge
 * (github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK).
 *
 * ## Architektura
 *
 * Sterowanie okularami idzie przez BLE (`LargeDataHandler.glassesControl`), a zdarzenia
 * z okularów wracają jako ramki notify (`addOutDeviceListener(100, ...)`).
 *
 * ### Mapa ramek notify (`loadData[6]`)
 * | Kod   | Znaczenie                          | Dane                                    |
 * |-------|------------------------------------|-----------------------------------------|
 * | 0x02  | Zdjęcie AI gotowe / przycisk foto   | -                                       |
 * | 0x03  | Przycisk AI / mikrofon              | `loadData[7] == 1` → wciśnięty          |
 * | 0x04  | Postęp OTA                          | download/soc/nor                        |
 * | 0x05  | Bateria                             | `[7]` = %, `[8]` = 1 gdy ładowanie      |
 * | 0x08  | IP okularów (Wi-Fi Direct)          | `[7..10]` = IPv4                        |
 * | 0x09  | Błąd P2P                            | `[7] == 0xFF` częsty, nie zawsze fatalny|
 * | 0x0c  | Pauza / komunikat głosowy           | `loadData[7] == 1`                      |
 * | 0x0e  | Mało pamięci na okularach           | -                                       |
 *
 * ### Dwie ścieżki pobierania obrazu
 * 1. **Miniatura przez BLE** (`capturePhoto()`) - szybka, bez Wi-Fi, ~4 s + transfer.
 *    To jest domyślna ścieżka dla Jarvisa (pytanie → zdjęcie → AI → TTS).
 * 2. **Pełne pliki przez Wi-Fi Direct** (`downloadLatestPhoto()` / `downloadLatestVideo()`) -
 *    pełna rozdzielczość, wymaga trybu transferu i IP z ramki 0x08.
 */
class JarvisManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val tag = TAG

    private val largeDataHandler: LargeDataHandler = LargeDataHandler.getInstance()

    /** Wi-Fi Direct - potrzebny do pobierania wideo i plików w pełnej rozdzielczości. */
    private val wifiTransfer = GlassesWifiTransfer(context)

    /** Nagrania głosowe po BLE - osobny kanał vendor SDK, działa bez Wi-Fi. */
    private val recordings = GlassesRecordings()

    /** Własny scope - symulator odgrywa zdarzenia asynchronicznie. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Symulator okularów. Niepusty tylko w trybie symulacji - wtedy przejmuje
     * cały transport, a reszta klasy działa na niezmienionym kodzie.
     */
    @Volatile
    private var simulator: GlassesSimulator? = null


    // === Stan ===

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _glassesIp = MutableStateFlow<String?>(null)
    val glassesIp: StateFlow<String?> = _glassesIp.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _buttonEvent = MutableStateFlow<ButtonEvent?>(null)
    val buttonEvent: StateFlow<ButtonEvent?> = _buttonEvent.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    /** Stan połączenia Wi-Fi Direct. */
    val wifiTransferState: StateFlow<TransferState> get() = wifiTransfer.state

    /** Ostatnia ramka notify w postaci szesnastkowej - dla ekranu diagnostycznego. */
    private val _lastNotifyFrame = MutableStateFlow<String?>(null)
    val lastNotifyFrame: StateFlow<String?> = _lastNotifyFrame.asStateFlow()

    /**
     * Ostatnie ramki notify wraz z odczytanym znaczeniem - najnowsze na górze.
     * Bufor jest ograniczony do [NOTIFY_LOG_SIZE], żeby nie puchł w nieskończoność.
     */
    private val _notifyLog = MutableStateFlow<List<NotifyLogEntry>>(emptyList())
    val notifyLog: StateFlow<List<NotifyLogEntry>> = _notifyLog.asStateFlow()

    private val _mediaCount = MutableStateFlow<MediaCount?>(null)
    val mediaCount: StateFlow<MediaCount?> = _mediaCount.asStateFlow()

    /** Czy działamy na symulatorze zamiast na sprzęcie. */
    private val _simulationEnabled = MutableStateFlow(false)
    val simulationEnabled: StateFlow<Boolean> = _simulationEnabled.asStateFlow()

    /** Ostatnia komenda wysłana do okularów - dla ekranu diagnostycznego. */
    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()

    /** Ustawiane na `true` gdy okulary zgłoszą gotowe zdjęcie AI (ramka 0x02). */
    private val _photoReady = MutableStateFlow(false)
    val photoReady: StateFlow<Boolean> = _photoReady.asStateFlow()

    private var initialized = false
    private var scanning = false
    private var notifyListenerRegistered = false

    // === Inicjalizacja ===

    /**
     * Inicjalizuje vendor SDK i rejestruje nasłuch zdarzeń.
     * Bezpieczne do wielokrotnego wywołania - kolejne wywołania są ignorowane.
     */
    @Synchronized
    fun initialize() {
        if (initialized) {
            Log.d(tag, "initialize() pominięte - już zainicjalizowane")
            return
        }

        simulator?.let {
            Log.i(tag, "Inicjalizacja w trybie symulacji - vendor SDK nie jest ruszane")
            _connectionState.value = ConnectionState.DISCONNECTED
            initialized = true
            return
        }

        Log.i(tag, "Inicjalizacja vendor SDK")

        val application = appContext as? Application
        if (application == null) {
            Log.e(tag, "appContext nie jest Application - SDK nie zostanie zainicjalizowane")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        try {
            BleOperateManager.getInstance().setApplication(application)
            BleOperateManager.getInstance().init()
            largeDataHandler.initEnable()
            registerBleBroadcastReceiver()
            registerDeviceNotifyListener()
            // Odtwórz realny stan - SDK mogło być już połączone (np. po obrocie ekranu).
            _connectionState.value =
                if (BleOperateManager.getInstance().isConnected) ConnectionState.READY
                else ConnectionState.DISCONNECTED
            initialized = true
            Log.i(tag, "SDK zainicjalizowane, stan=${_connectionState.value}")
        } catch (e: Exception) {
            Log.e(tag, "Inicjalizacja SDK nie powiodła się", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Rejestruje odbiornik broadcastów BLE z vendor SDK - to jest realne źródło stanu połączenia.
     */
    private fun registerBleBroadcastReceiver() {
        ContextCompat.registerReceiver(
            appContext,
            bleStateReceiver,
            BleAction.getIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val bleStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BleAction.BLE_START_CONNECT -> {
                    Log.d(tag, "BLE: rozpoczęto łączenie")
                    _connectionState.value = ConnectionState.CONNECTING
                }
                BleAction.BLE_GATT_CONNECTED -> {
                    Log.i(tag, "BLE: GATT połączony")
                    _connectionState.value = ConnectionState.CONNECTED
                }
                BleAction.BLE_SERVICE_DISCOVERED -> {
                    Log.i(tag, "BLE: usługi wykryte - okulary gotowe")
                    _connectionState.value = ConnectionState.READY
                    // Po pełnym połączeniu odpytaj o baterię.
                    runCatching { largeDataHandler.syncBattery() }
                        .onFailure { Log.w(tag, "syncBattery nie powiodło się", it) }
                }
                BleAction.BLE_GATT_DISCONNECTED -> {
                    Log.i(tag, "BLE: rozłączono")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _glassesIp.value = null
                }
                BleAction.BLE_NOT_SUPPORTED,
                BleAction.BLE_NO_BT_ADAPTER,
                BleAction.BLE_STATUS_ABNORMAL -> {
                    Log.e(tag, "BLE: błąd adaptera (${intent.action})")
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        }
    }

    /**
     * Rejestruje nasłuch ramek notify z okularów (bateria, przycisk, IP, gotowe zdjęcie).
     */
    private fun registerDeviceNotifyListener() {
        if (notifyListenerRegistered) return
        largeDataHandler.addOutDeviceListener(GlassesProtocol.DEVICE_NOTIFY_KEY, deviceNotifyListener)
        notifyListenerRegistered = true
        Log.d(tag, "Zarejestrowano nasłuch notify (klucz=${GlassesProtocol.DEVICE_NOTIFY_KEY})")
    }

    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            handleNotify(response.loadData)
        }
    }

    /**
     * Jedyne miejsce, w którym ramka notify zamienia się w stan aplikacji.
     *
     * Wchodzą tędy zarówno ramki ze sprzętu, jak i te z [GlassesSimulator] -
     * dzięki temu symulacja przechodzi przez ten sam kod, co prawdziwe okulary.
     */
    private fun handleNotify(load: ByteArray?) {
        val hex = GlassesProtocol.formatFrame(load)
        _lastNotifyFrame.value = hex

        val decoded = GlassesProtocol.decodeNotify(load)
        _notifyLog.update { log ->
            (listOf(NotifyLogEntry(System.currentTimeMillis(), hex, describe(decoded))) + log)
                .take(NOTIFY_LOG_SIZE)
        }

        when (val event = decoded) {
            is NotifyEvent.PhotoReady -> {
                Log.i(tag, "Notify: zdjęcie AI gotowe")
                _photoReady.value = true
            }
            is NotifyEvent.ButtonPressed -> {
                Log.i(tag, "Notify: wciśnięto przycisk AI")
                _buttonEvent.value = ButtonEvent.ShortClick
            }
            is NotifyEvent.Battery -> {
                Log.i(tag, "Notify: bateria ${event.level}%, ładowanie=${event.charging}")
                _batteryLevel.value = event.level
                _isCharging.value = event.charging
            }
            is NotifyEvent.GlassesIp -> {
                Log.i(tag, "Notify: IP okularów = ${event.ip}")
                _glassesIp.value = event.ip
            }
            is NotifyEvent.P2pError -> {
                // Kod 255 okulary zgłaszają rutynowo - nie panikujemy.
                Log.w(tag, "Notify: błąd P2P (kod=${event.code})")
            }
            is NotifyEvent.OtaProgress -> {
                Log.d(tag, "Notify: OTA ${event.download}/${event.soc}/${event.nor}")
            }
            is NotifyEvent.LowMemory -> Log.w(tag, "Notify: mało pamięci na okularach")
            is NotifyEvent.Paused -> Log.d(tag, "Notify: pauza")
            is NotifyEvent.Unbound -> Log.w(tag, "Notify: okulary odpięły aplikację")
            is NotifyEvent.Unknown ->
                Log.d(tag, "Notify: nieobsługiwany typ 0x${event.type.toString(16)}")
            is NotifyEvent.Malformed ->
                Log.w(tag, "Notify: ramka za krótka (${event.size} B)")
        }
    }

    /** Opis zdarzenia po polsku - na ekran diagnostyczny. */
    private fun describe(event: NotifyEvent): String = when (event) {
        is NotifyEvent.PhotoReady -> "Zdjęcie gotowe"
        is NotifyEvent.ButtonPressed -> "Wciśnięto przycisk AI"
        is NotifyEvent.Battery ->
            "Bateria ${event.level}%" + if (event.charging) " (ładowanie)" else ""
        is NotifyEvent.GlassesIp -> "IP okularów: ${event.ip}"
        is NotifyEvent.P2pError -> "Błąd P2P, kod ${event.code}"
        is NotifyEvent.OtaProgress ->
            "OTA: pobrano ${event.download}%, SoC ${event.soc}%, NOR ${event.nor}%"
        is NotifyEvent.LowMemory -> "Mało pamięci na okularach"
        is NotifyEvent.Paused -> "Pauza"
        is NotifyEvent.Unbound -> "Okulary odpięły aplikację"
        is NotifyEvent.Unknown -> "Nieobsługiwany typ 0x%02X".format(event.type)
        is NotifyEvent.Malformed -> "Ramka uszkodzona (${event.size} B)"
    }

    /** Czyści dziennik ramek. */
    fun clearNotifyLog() {
        _notifyLog.value = emptyList()
    }

    /** Kasuje ostatnie zdarzenie przycisku po jego obsłużeniu. */
    fun consumeButtonEvent() {
        _buttonEvent.value = null
    }

    // === Tryb symulacji ===

    /**
     * Włącza albo wyłącza symulowane okulary.
     *
     * W trybie symulacji podmieniany jest **wyłącznie transport**: komendy nie idą
     * przez BLE, a ramki notify składa [GlassesSimulator]. Wszystko powyżej -
     * dekodowanie, stan, UI, warstwa AI - działa na tym samym kodzie co ze sprzętem.
     *
     * Przełączenie rozłącza to, co jest aktualnie połączone, i wymaga ponownego
     * [initialize] - dlatego wywołuj to zanim aplikacja zacznie łączyć się z okularami.
     *
     * @param photoSource źródło zdjęć; na Androidzie [CanvasPhotoSource] rysuje
     *        czytelne sceny testowe, w testach wystarczy [EmbeddedPhotoSource]
     */
    @Synchronized
    fun setSimulationEnabled(
        enabled: Boolean,
        photoSource: SimulatedPhotoSource = CanvasPhotoSource(),
        timings: GlassesSimulator.Timings = GlassesSimulator.Timings(),
        faults: GlassesSimulator.Faults = GlassesSimulator.Faults()
    ) {
        if (enabled == _simulationEnabled.value) return

        // Posprzątaj po poprzednim trybie - inaczej zostaje wiszące połączenie.
        if (initialized) release()
        resetState()

        simulator = if (enabled) {
            GlassesSimulator(
                scope = scope,
                timings = timings,
                photos = photoSource,
                faults = faults,
                onNotify = ::handleNotify
            )
        } else {
            null
        }
        _simulationEnabled.value = enabled
        Log.i(tag, if (enabled) "Włączono symulowane okulary" else "Wyłączono symulację")
    }

    /**
     * Symulator, gdy tryb symulacji jest włączony.
     * Ekran diagnostyczny sięga po niego, żeby wstrzykiwać zdarzenia.
     */
    fun simulatorOrNull(): GlassesSimulator? = simulator

    private fun resetState() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _glassesIp.value = null
        _discoveredDevices.value = emptyList()
        _buttonEvent.value = null
        _batteryLevel.value = null
        _isCharging.value = false
        _lastNotifyFrame.value = null
        _lastCommand.value = null
        _notifyLog.value = emptyList()
        _mediaCount.value = null
        _photoReady.value = false
    }

    // === Skanowanie i parowanie ===

    /**
     * Rozpoczyna skanowanie BLE w poszukiwaniu okularów.
     * Wymaga uprawnień BLUETOOTH_SCAN (API 31+) oraz lokalizacji na starszych wersjach.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) {
            Log.d(tag, "startScan() pominięte - skan już trwa")
            return
        }
        Log.i(tag, "Start skanowania BLE")
        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionState.SCANNING
        scanning = true

        simulator?.let { sim ->
            scope.launch {
                delay(SIMULATED_SCAN_DELAY_MS)
                if (scanning) _discoveredDevices.value = listOf(sim.advertisedDevice())
            }
            return
        }

        try {
            BleScannerHelper.getInstance().reSetCallback()
            BleScannerHelper.getInstance().scanDevice(appContext, null, scanCallback)
        } catch (e: Exception) {
            scanning = false
            Log.e(tag, "Skanowanie nie wystartowało", e)
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }

    /** Zatrzymuje skanowanie BLE. */
    fun stopScan() {
        if (!scanning) return
        Log.i(tag, "Stop skanowania BLE")
        scanning = false
        if (simulator == null) {
            runCatching { BleScannerHelper.getInstance().stopScan(appContext) }
                .onFailure { Log.w(tag, "stopScan nie powiodło się", it) }
        }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanWrapperCallback {
        override fun onStart() {
            scanning = true
        }

        override fun onStop() {
            scanning = false
        }

        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            val bluetoothDevice = device ?: return
            val name = runCatching { bluetoothDevice.name }.getOrNull()
            upsertDevice(bluetoothDevice.address, name, rssi)
        }

        @SuppressLint("MissingPermission")
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            val bluetoothDevice = device ?: return
            val name = runCatching { scanRecord?.deviceName ?: bluetoothDevice.name }.getOrNull()
            upsertDevice(bluetoothDevice.address, name, null)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.e(tag, "Skanowanie nie powiodło się (kod=$errorCode)")
            _connectionState.value = ConnectionState.ERROR
        }

        override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>?) {
            // Nieużywane - wyniki przychodzą przez onLeScan/onParsedData.
        }
    }

    /**
     * Dodaje lub aktualizuje urządzenie na liście wyników skanu.
     * `rssi == null` zachowuje poprzednio znaną siłę sygnału.
     */
    private fun upsertDevice(address: String, name: String?, rssi: Int?) {
        _discoveredDevices.update { current ->
            val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
            val updated = DiscoveredDevice(
                address = address,
                name = name ?: existing?.name,
                rssi = rssi ?: existing?.rssi ?: 0
            )
            if (existing == null) {
                current + updated
            } else {
                current.map { if (it.address.equals(address, ignoreCase = true)) updated else it }
            }
        }
    }

    /**
     * Łączy się z okularami o podanym adresie MAC.
     * Stan połączenia śledzony jest przez broadcasty BLE (patrz [bleStateReceiver]).
     */
    fun connect(address: String) {
        Log.i(tag, "Łączenie z $address")
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING

        simulator?.let { sim ->
            sim.connect { state -> _connectionState.value = state }
            return
        }

        try {
            BleOperateManager.getInstance().connectDirectly(address)
        } catch (e: Exception) {
            Log.e(tag, "Łączenie nie powiodło się", e)
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }

    /** Rozłącza okulary i czyści stan. */
    fun disconnect() {
        Log.i(tag, "Rozłączanie")
        val sim = simulator
        if (sim != null) {
            sim.disconnect()
        } else {
            runCatching { BleOperateManager.getInstance().disconnect() }
                .onFailure { Log.w(tag, "disconnect nie powiodło się", it) }
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        _glassesIp.value = null
    }

    /** Czy okulary są realnie połączone (odpytuje vendor SDK). */
    fun isConnected(): Boolean =
        simulator?.connected
            ?: runCatching { BleOperateManager.getInstance().isConnected }.getOrDefault(false)

    // === Komendy sterujące ===

    /**
     * Wysyła komendę sterującą do okularów.
     * `glassesControl` wymaga callbacku - vendor SDK rejestruje go pod kluczem
     * ACTION_GLASSES_CONTROL (65) i ma tylko jeden slot na odpowiedź.
     */
    private fun send(bytes: ByteArray, onResponse: ((Int) -> Unit)? = null) {
        simulator?.let { sim ->
            _lastCommand.value = sim.handleCommand(bytes)
            onResponse?.invoke(0)
            return
        }
        _lastCommand.value = GlassesProtocol.describeCommand(bytes)
        try {
            largeDataHandler.glassesControl(bytes) { _, response ->
                val error = runCatching { response?.errorCode ?: 0 }.getOrDefault(0)
                if (error != 0) {
                    Log.w(tag, "glassesControl: błąd urządzenia (kod=$error)")
                }
                onResponse?.invoke(error)
            }
        } catch (e: Exception) {
            Log.e(tag, "glassesControl nie powiodło się", e)
        }
    }

    /**
     * Włącza tryb transferu plików (Wi-Fi Direct).
     * IP okularów przyjdzie asynchronicznie jako ramka notify 0x08.
     */
    fun enableTransferMode() {
        Log.d(tag, "Włączanie trybu transferu")
        send(GlassesProtocol.enableTransferMode())
    }

    /** Resetuje połączenie P2P na okularach (gdy transfer się zawiesi). */
    fun resetP2p() {
        Log.d(tag, "Reset P2P")
        _glassesIp.value = null
        send(GlassesProtocol.resetP2p())
    }

    /**
     * Robi zdjęcie okularami.
     * Uwaga: to tylko wyzwala migawkę - plik zostaje w pamięci okularów.
     * Po bajty zdjęcia sięgnij przez [capturePhoto] (miniatura po BLE)
     * albo [downloadLatestPhoto] (pełny plik po Wi-Fi Direct).
     */
    fun takePhoto() {
        Log.d(tag, "Zdjęcie")
        send(GlassesProtocol.takePhoto())
    }

    fun startVideoRecording() {
        Log.d(tag, "Start nagrywania wideo")
        send(GlassesProtocol.startVideo())
    }

    fun stopVideoRecording() {
        Log.d(tag, "Stop nagrywania wideo")
        send(GlassesProtocol.stopVideo())
    }

    fun startAudioRecording() {
        Log.d(tag, "Start nagrywania audio")
        send(GlassesProtocol.startAudio())
    }

    fun stopAudioRecording() {
        Log.d(tag, "Stop nagrywania audio")
        send(GlassesProtocol.stopAudio())
    }

    /**
     * Pyta okulary ile niezsynchronizowanych plików mają w pamięci.
     * Odpowiedź ma `dataType == 4` i niesie liczniki zdjęć, wideo i nagrań.
     */
    fun requestMediaCount(onResult: (images: Int, videos: Int, records: Int) -> Unit) {
        val bytes = GlassesProtocol.requestMediaCount()

        simulator?.let { sim ->
            _lastCommand.value = sim.handleCommand(bytes)
            val count = sim.mediaCount()
            _mediaCount.value = count
            onResult(count.images, count.videos, count.records)
            return
        }

        try {
            largeDataHandler.glassesControl(bytes) { _, response ->
                if (response != null && response.dataType == GlassesProtocol.DATA_TYPE_MEDIA_COUNT) {
                    val i = response.imageCount
                    val v = response.videoCount
                    val r = response.recordCount
                    Log.i(tag, "Na okularach: $i zdjęć, $v wideo, $r nagrań")
                    _mediaCount.value = MediaCount(i, v, r)
                    onResult(i, v, r)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "requestMediaCount nie powiodło się", e)
        }
    }

    /** Prosi okulary o aktualny poziom baterii - odpowiedź wraca jako notify 0x05. */
    fun requestBatteryLevel() {
        Log.d(tag, "Zapytanie o baterię")
        val sim = simulator
        if (sim != null) {
            sim.requestBattery()
            return
        }
        runCatching { largeDataHandler.syncBattery() }
            .onFailure { Log.w(tag, "syncBattery nie powiodło się", it) }
    }

    // === Szybka ścieżka: zdjęcie jako miniatura po BLE ===

    /**
     * Robi zdjęcie i pobiera je jako miniaturę przez BLE - bez Wi-Fi Direct.
     * To jest domyślna ścieżka dla Jarvisa: najszybsza droga od migawki do bajtów JPEG.
     *
     * @param quality wartość jakości miniatury przekazywana do okularów (0-2; wyżej = lepiej)
     * @return bajty JPEG albo `null` gdy okulary nie odpowiedziały w czasie
     */
    suspend fun capturePhoto(quality: Int = DEFAULT_THUMBNAIL_QUALITY): ByteArray? {
        if (!isConnected()) {
            Log.w(tag, "capturePhoto: okulary nie są połączone")
            return null
        }
        _photoReady.value = false
        send(GlassesProtocol.captureAiPhoto(quality))
        awaitPhotoReady()
        return receiveThumbnail()
    }

    /**
     * Czeka aż okulary zgłoszą gotowe zdjęcie ramką notify 0x02.
     * Gdy notify nie dotrze (starszy firmware), wraca do sztywnego odczekania -
     * dzięki temu przechwytywanie działa tak szybko, jak pozwala sprzęt.
     */
    private suspend fun awaitPhotoReady() {
        val signalled = withTimeoutOrNull(PHOTO_READY_TIMEOUT_MS) {
            while (!_photoReady.value) {
                delay(PHOTO_READY_POLL_MS)
            }
            true
        }
        if (signalled == null) {
            Log.d(tag, "Brak notify o gotowym zdjęciu - odczekuję ${CAPTURE_SETTLE_MS} ms")
            delay(CAPTURE_SETTLE_MS)
        }
    }

    /**
     * Pobiera miniaturę zdjęcia zrobionego fizycznym przyciskiem na okularach
     * (bez wyzwalania nowej migawki).
     */
    suspend fun capturePhotoFromHardwareButton(): ByteArray? {
        if (!isConnected()) {
            Log.w(tag, "capturePhotoFromHardwareButton: okulary nie są połączone")
            return null
        }
        _photoReady.value = false
        return receiveThumbnail()
    }

    /**
     * Odbiera miniaturę po BLE. Vendor SDK dostarcza ją w kawałkach -
     * `isComplete == true` oznacza koniec transferu.
     */
    private suspend fun receiveThumbnail(): ByteArray? {
        simulator?.let { return it.thumbnail() }

        val output = ByteArrayOutputStream()
        val complete = CompletableDeferred<Boolean>()
        try {
            largeDataHandler.getPictureThumbnails { _, isComplete, data ->
                if (data != null && data.isNotEmpty()) output.write(data)
                if (isComplete && !complete.isCompleted) complete.complete(output.size() > 0)
            }
        } catch (e: Exception) {
            Log.e(tag, "getPictureThumbnails nie powiodło się", e)
            return null
        }

        val ok = withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) { complete.await() }
        if (ok != true) {
            Log.w(tag, "Transfer miniatury przekroczył limit czasu")
            return null
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    // === Pełne pliki przez Wi-Fi Direct (HTTP) ===

    /**
     * Pobiera listę plików multimedialnych z okularów.
     * Endpoint `/files/media.config` zwraca zwykły tekst - jedna nazwa pliku na linię.
     */
    suspend fun getMediaFileList(): List<String> = withContext(Dispatchers.IO) {
        simulator?.let { return@withContext it.mediaFileList() }

        val ip = _glassesIp.value
            ?: throw JarvisException("Brak IP okularów - najpierw enableTransferMode()")

        val conn = URL("http://$ip/files/media.config").openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = LIST_READ_TIMEOUT_MS
        try {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8)
                .readText()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    /** Pobiera pojedynczy plik z okularów przez HTTP. */
    suspend fun downloadFile(filename: String): ByteArray = withContext(Dispatchers.IO) {
        simulator?.let { return@withContext it.fileBytes(filename) }

        val ip = _glassesIp.value
            ?: throw JarvisException("Brak IP okularów - najpierw enableTransferMode()")

        Log.d(tag, "Pobieranie $filename z $ip")
        val conn = URL("http://$ip/files/$filename").openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = FILE_READ_TIMEOUT_MS
        try {
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Włącza tryb transferu i czeka aż okulary zgłoszą swoje IP ramką notify 0x08.
     * @return `true` gdy IP jest dostępne
     */
    private suspend fun awaitGlassesIp(): Boolean {
        // 1. Poproś okulary o wejście w tryb transferu - zaczną rozgłaszać grupę Wi-Fi Direct.
        enableTransferMode()

        // 2. Dołącz do tej grupy. Bez tego telefon nie ma trasy do serwera HTTP okularów.
        //    W symulacji nie ma czego podnosić - IP przyjdzie samą ramką 0x08.
        if (simulator == null) {
            if (!joinWifiDirectGroup()) return false
        }

        // 3. IP okularów przychodzi ramką notify 0x08 - groupOwnerAddress to zwykle telefon.
        val ip = withTimeoutOrNull(IP_TIMEOUT_MS) {
            while (_glassesIp.value == null) {
                delay(IP_POLL_INTERVAL_MS)
            }
            _glassesIp.value
        }
        if (ip == null) {
            Log.w(tag, "Nie doczekano się IP okularów (ramka notify 0x08)")
            return false
        }
        Log.i(tag, "Okulary osiągalne pod $ip")
        return true
    }

    /** Dołącza do grupy Wi-Fi Direct okularów. @return `true` gdy się udało */
    private suspend fun joinWifiDirectGroup(): Boolean {
        if (!wifiTransfer.isAvailable()) {
            Log.w(tag, "Wi-Fi Direct niedostępny - nie pobiorę plików")
            return false
        }
        if (!wifiTransfer.hasPermission()) {
            Log.w(
                tag,
                "Brak uprawnienia do Wi-Fi Direct (NEARBY_WIFI_DEVICES na Androidzie 13+, " +
                    "wcześniej ACCESS_FINE_LOCATION)"
            )
            return false
        }
        if (!wifiTransfer.connect(deviceNameHint = WIFI_DEVICE_NAME_HINT)) {
            Log.w(tag, "Nie udało się dołączyć do grupy Wi-Fi Direct okularów")
            return false
        }
        wifiTransfer.awaitServerReady()
        return true
    }

    // === Nagrania głosowe przez BLE (bez Wi-Fi Direct) ===

    /** Postęp pobierania nagrania: 0.0 - 1.0, albo `null` gdy nic nie trwa. */
    val recordingProgress: StateFlow<Float?> get() = recordings.progress

    /**
     * Lista nagrań głosowych w pamięci okularów, pobierana kanałem BLE.
     * Nie wymaga trybu transferu ani Wi-Fi Direct.
     *
     * @param fileType numer typu pliku; producent go nie udokumentował, więc
     *        właściwą wartość ustala się doświadczalnie - patrz [GlassesRecordings]
     */
    suspend fun listRecordings(
        fileType: Int = GlassesRecordings.DEFAULT_FILE_TYPE
    ): List<Recording> {
        simulator?.let { return it.recordings() }
        if (!isConnected()) {
            Log.w(tag, "listRecordings: okulary nie są połączone")
            return emptyList()
        }
        return recordings.list(fileType)
    }

    /** Pobiera nagranie głosowe kanałem BLE. */
    suspend fun downloadRecording(
        fileName: String,
        fileType: Int = GlassesRecordings.DEFAULT_FILE_TYPE
    ): ByteArray? {
        simulator?.let { return it.recordingBytes(fileName) }
        if (!isConnected()) {
            Log.w(tag, "downloadRecording: okulary nie są połączone")
            return null
        }
        return recordings.download(fileName, fileType)
    }

    /** Kończy sesję transferu: rozłącza Wi-Fi Direct i przywraca domyślny routing. */
    fun endTransferSession() {
        if (simulator == null) wifiTransfer.stop()
        _glassesIp.value = null
    }

    /** Pobiera najnowsze zdjęcie w pełnej rozdzielczości przez Wi-Fi Direct. */
    suspend fun downloadLatestPhoto(): ByteArray? = downloadLatest(PHOTO_EXTENSIONS, "zdjęcia")

    /** Pobiera najnowsze wideo przez Wi-Fi Direct. */
    suspend fun downloadLatestVideo(): ByteArray? = downloadLatest(VIDEO_EXTENSIONS, "wideo")

    /** Pobiera najnowsze nagranie audio przez Wi-Fi Direct. */
    suspend fun downloadLatestAudio(): ByteArray? = downloadLatest(AUDIO_EXTENSIONS, "audio")

    private suspend fun downloadLatest(extensions: List<String>, label: String): ByteArray? {
        if (!awaitGlassesIp()) return null

        return try {
            val matching = getMediaFileList().filter { file ->
                extensions.any { file.endsWith(it, ignoreCase = true) }
            }
            if (matching.isEmpty()) {
                Log.w(tag, "Brak plików typu $label na okularach")
                return null
            }
            // Nazwy plików z okularów są sekwencyjne/oparte na czasie - największa = najnowsza.
            val latest = matching.max()
            Log.i(tag, "Pobieranie najnowszego pliku $label: $latest")
            downloadFile(latest)
        } catch (e: Exception) {
            Log.e(tag, "Pobieranie pliku $label nie powiodło się", e)
            null
        } finally {
            // Zwolnij sieć - inaczej cały ruch aplikacji zostaje na grupie okularów.
            endTransferSession()
        }
    }

    // === Sprzątanie ===

    /** Zwalnia zasoby SDK - wywołaj gdy aplikacja kończy pracę. */
    @Synchronized
    fun release() {
        Log.i(tag, "Zwalnianie zasobów")
        stopScan()

        val sim = simulator
        if (sim != null) {
            sim.disconnect()
            _connectionState.value = ConnectionState.DISCONNECTED
            initialized = false
            return
        }

        wifiTransfer.stop()
        runCatching { appContext.unregisterReceiver(bleStateReceiver) }
            .onFailure { Log.w(tag, "unregisterReceiver nie powiodło się", it) }
        if (notifyListenerRegistered) {
            runCatching { largeDataHandler.removeOutDeviceListener(GlassesProtocol.DEVICE_NOTIFY_KEY) }
                .onFailure { Log.w(tag, "removeOutDeviceListener nie powiodło się", it) }
            notifyListenerRegistered = false
        }
        runCatching { largeDataHandler.removeGlassesControlCallback() }
            .onFailure { Log.w(tag, "removeGlassesControlCallback nie powiodło się", it) }
        runCatching { largeDataHandler.disEnable() }
            .onFailure { Log.w(tag, "disEnable nie powiodło się", it) }
        initialized = false
    }

    companion object {
        private const val TAG = "JarvisManager"






        /** Jakość miniatury: zakres 0..6 wg dokumentacji producenta. */
        /** Fragment nazwy urządzenia Wi-Fi Direct okularów. */
        private const val WIFI_DEVICE_NAME_HINT = "cyan"

        private const val DEFAULT_THUMBNAIL_QUALITY = 2

        /** Symulowane okulary "znajdują się" po chwili, jak prawdziwy skan BLE. */
        private const val SIMULATED_SCAN_DELAY_MS = 700L

        /** Ile ramek notify trzymamy na potrzeby diagnostyki. */
        private const val NOTIFY_LOG_SIZE = 50

        /** Czas potrzebny okularom na zrobienie zdjęcia zanim poprosimy o miniaturę. */
        private const val CAPTURE_SETTLE_MS = 4_000L
        private const val PHOTO_READY_TIMEOUT_MS = 8_000L
        private const val PHOTO_READY_POLL_MS = 50L
        private const val THUMBNAIL_TIMEOUT_MS = 10_000L

        private const val IP_TIMEOUT_MS = 15_000L
        private const val IP_POLL_INTERVAL_MS = 100L

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val LIST_READ_TIMEOUT_MS = 10_000
        private const val FILE_READ_TIMEOUT_MS = 60_000

        private val PHOTO_EXTENSIONS = listOf(".jpg", ".jpeg", ".png")
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".mov", ".avi")
        private val AUDIO_EXTENSIONS = listOf(".opus", ".ogg", ".wav", ".mp3")

        @Volatile
        private var instance: JarvisManager? = null

        fun getInstance(context: Context): JarvisManager =
            instance ?: synchronized(this) {
                instance ?: JarvisManager(context).also { instance = it }
            }
    }
}
