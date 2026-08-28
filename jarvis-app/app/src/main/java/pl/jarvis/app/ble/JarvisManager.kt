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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _mediaCount = MutableStateFlow<MediaCount?>(null)
    val mediaCount: StateFlow<MediaCount?> = _mediaCount.asStateFlow()

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
        largeDataHandler.addOutDeviceListener(DEVICE_NOTIFY_KEY, deviceNotifyListener)
        notifyListenerRegistered = true
        Log.d(tag, "Zarejestrowano nasłuch notify (klucz=$DEVICE_NOTIFY_KEY)")
    }

    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val load = response.loadData
            if (load == null || load.size <= NOTIFY_TYPE_INDEX) {
                Log.w(tag, "Notify: ramka za krótka (${load?.size ?: 0} B)")
                return
            }
            when (load[NOTIFY_TYPE_INDEX].toInt() and 0xFF) {
                NOTIFY_PHOTO_READY -> {
                    Log.i(tag, "Notify: zdjęcie AI gotowe")
                    _photoReady.value = true
                }
                NOTIFY_AI_BUTTON -> {
                    if (load.size > 7 && load[7].toInt() == 1) {
                        Log.i(tag, "Notify: wciśnięto przycisk AI")
                        _buttonEvent.value = ButtonEvent.ShortClick
                    }
                }
                NOTIFY_BATTERY -> {
                    if (load.size > 8) {
                        val level = load[7].toInt() and 0xFF
                        val charging = (load[8].toInt() and 0xFF) == 1
                        Log.i(tag, "Notify: bateria $level%, ładowanie=$charging")
                        _batteryLevel.value = level
                        _isCharging.value = charging
                    }
                }
                NOTIFY_GLASSES_IP -> {
                    if (load.size > 10) {
                        val ip = buildString {
                            append(load[7].toInt() and 0xFF).append('.')
                            append(load[8].toInt() and 0xFF).append('.')
                            append(load[9].toInt() and 0xFF).append('.')
                            append(load[10].toInt() and 0xFF)
                        }
                        Log.i(tag, "Notify: IP okularów = $ip")
                        _glassesIp.value = ip
                    }
                }
                NOTIFY_P2P_ERROR -> {
                    val code = if (load.size > 7) load[7].toInt() and 0xFF else -1
                    // 0xFF jest częsty i nie zawsze oznacza realną awarię - tylko logujemy.
                    Log.w(tag, "Notify: błąd P2P (kod=$code)")
                }
                NOTIFY_LOW_MEMORY -> Log.w(tag, "Notify: mało pamięci na okularach")
                else -> Log.d(tag, "Notify: nieobsłużony typ 0x${(load[NOTIFY_TYPE_INDEX].toInt() and 0xFF).toString(16)}")
            }
        }
    }

    /** Kasuje ostatnie zdarzenie przycisku po jego obsłużeniu. */
    fun consumeButtonEvent() {
        _buttonEvent.value = null
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
        runCatching { BleScannerHelper.getInstance().stopScan(appContext) }
            .onFailure { Log.w(tag, "stopScan nie powiodło się", it) }
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
        runCatching { BleOperateManager.getInstance().disconnect() }
            .onFailure { Log.w(tag, "disconnect nie powiodło się", it) }
        _connectionState.value = ConnectionState.DISCONNECTED
        _glassesIp.value = null
    }

    /** Czy okulary są realnie połączone (odpytuje vendor SDK). */
    fun isConnected(): Boolean =
        runCatching { BleOperateManager.getInstance().isConnected }.getOrDefault(false)

    // === Komendy sterujące ===

    /**
     * Wysyła komendę sterującą do okularów.
     * `glassesControl` wymaga callbacku - vendor SDK rejestruje go pod kluczem
     * ACTION_GLASSES_CONTROL (65) i ma tylko jeden slot na odpowiedź.
     */
    private fun sendControl(vararg payload: Int, onResponse: ((Int) -> Unit)? = null) {
        val bytes = ByteArray(payload.size) { payload[it].toByte() }
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
        sendControl(0x02, 0x01, WORK_TRANSFER)
    }

    /** Resetuje połączenie P2P na okularach (gdy transfer się zawiesi). */
    fun resetP2p() {
        Log.d(tag, "Reset P2P")
        _glassesIp.value = null
        sendControl(0x02, 0x01, WORK_RESET_P2P)
    }

    /**
     * Robi zdjęcie okularami.
     * Uwaga: to tylko wyzwala migawkę - plik zostaje w pamięci okularów.
     * Po bajty zdjęcia sięgnij przez [capturePhoto] (miniatura po BLE)
     * albo [downloadLatestPhoto] (pełny plik po Wi-Fi Direct).
     */
    fun takePhoto() {
        Log.d(tag, "Zdjęcie")
        sendControl(0x02, 0x01, WORK_PHOTO)
    }

    fun startVideoRecording() {
        Log.d(tag, "Start nagrywania wideo")
        sendControl(0x02, 0x01, WORK_VIDEO_START)
    }

    fun stopVideoRecording() {
        Log.d(tag, "Stop nagrywania wideo")
        sendControl(0x02, 0x01, WORK_VIDEO_STOP)
    }

    fun startAudioRecording() {
        Log.d(tag, "Start nagrywania audio")
        sendControl(0x02, 0x01, WORK_AUDIO_START)
    }

    fun stopAudioRecording() {
        Log.d(tag, "Stop nagrywania audio")
        sendControl(0x02, 0x01, WORK_AUDIO_STOP)
    }

    /**
     * Pyta okulary ile niezsynchronizowanych plików mają w pamięci.
     * Odpowiedź ma `dataType == 4` i niesie liczniki zdjęć, wideo i nagrań.
     */
    fun requestMediaCount(onResult: (images: Int, videos: Int, records: Int) -> Unit) {
        val bytes = byteArrayOf(0x02, 0x04)
        try {
            largeDataHandler.glassesControl(bytes) { _, response ->
                if (response != null && response.dataType == DATA_TYPE_MEDIA_COUNT) {
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
        sendControl(0x02, 0x01, WORK_AI_PHOTO, quality, quality, 0x02)
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
        if (_glassesIp.value != null) return true
        enableTransferMode()
        val ip = withTimeoutOrNull(IP_TIMEOUT_MS) {
            while (_glassesIp.value == null) {
                delay(IP_POLL_INTERVAL_MS)
            }
            _glassesIp.value
        }
        if (ip == null) {
            Log.w(tag, "Nie doczekano się IP okularów")
            return false
        }
        return true
    }

    /** Pobiera najnowsze zdjęcie w pełnej rozdzielczości przez Wi-Fi Direct. */
    suspend fun downloadLatestPhoto(): ByteArray? = downloadLatest(PHOTO_EXTENSIONS, "zdjęcia")

    /** Pobiera najnowsze wideo przez Wi-Fi Direct. */
    suspend fun downloadLatestVideo(): ByteArray? = downloadLatest(VIDEO_EXTENSIONS, "wideo")

    /** Pobiera najnowsze nagranie audio przez Wi-Fi Direct. */
    suspend fun downloadLatestAudio(): ByteArray? = downloadLatest(AUDIO_EXTENSIONS, "audio")

    private suspend fun downloadLatest(extensions: List<String>, label: String): ByteArray? {
        if (!awaitGlassesIp()) return null

        val matching = getMediaFileList().filter { file ->
            extensions.any { file.endsWith(it, ignoreCase = true) }
        }
        if (matching.isEmpty()) {
            Log.w(tag, "Brak plików typu $label na okularach")
            return null
        }
        // Nazwy plików z okularów są sekwencyjne/oparte na czasie, więc największa = najnowsza.
        val latest = matching.max()
        Log.i(tag, "Pobieranie najnowszego pliku $label: $latest")
        return downloadFile(latest)
    }

    // === Sprzątanie ===

    /** Zwalnia zasoby SDK - wywołaj gdy aplikacja kończy pracę. */
    @Synchronized
    fun release() {
        Log.i(tag, "Zwalnianie zasobów")
        stopScan()
        runCatching { appContext.unregisterReceiver(bleStateReceiver) }
            .onFailure { Log.w(tag, "unregisterReceiver nie powiodło się", it) }
        if (notifyListenerRegistered) {
            runCatching { largeDataHandler.removeOutDeviceListener(DEVICE_NOTIFY_KEY) }
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

        /** Klucz nasłuchu ogólnych ramek notify z okularów. */
        private const val DEVICE_NOTIFY_KEY = 100

        /** Indeks bajtu typu zdarzenia w ramce notify. */
        private const val NOTIFY_TYPE_INDEX = 6

        private const val NOTIFY_PHOTO_READY = 0x02
        private const val NOTIFY_AI_BUTTON = 0x03
        private const val NOTIFY_BATTERY = 0x05
        private const val NOTIFY_GLASSES_IP = 0x08
        private const val NOTIFY_P2P_ERROR = 0x09
        private const val NOTIFY_LOW_MEMORY = 0x0e

        // Tryby pracy okularów - drugi bajt komendy 0x02 0x01 <tryb>.
        // Wartości z oficjalnego przewodnika SDK producenta.
        private const val WORK_PHOTO = 0x01
        private const val WORK_VIDEO_START = 0x02
        private const val WORK_VIDEO_STOP = 0x03
        private const val WORK_TRANSFER = 0x04
        private const val WORK_OTA = 0x05
        private const val WORK_AI_PHOTO = 0x06
        private const val WORK_AUDIO_START = 0x08
        private const val WORK_AUDIO_STOP = 0x0C
        private const val WORK_RESET_P2P = 0x0F

        /** dataType == 4 w odpowiedzi oznacza liczniki mediów. */
        private const val DATA_TYPE_MEDIA_COUNT = 4

        /** Jakość miniatury: zakres 0..6 wg dokumentacji producenta. */
        private const val DEFAULT_THUMBNAIL_QUALITY = 2

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

/** Liczba niezsynchronizowanych plików w pamięci okularów. */
data class MediaCount(
    val images: Int,
    val videos: Int,
    val records: Int
) {
    val total: Int get() = images + videos + records
}

/** Urządzenie znalezione podczas skanowania BLE. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int
)

/** Stan połączenia z okularami. */
enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    READY,
    ERROR
}

/** Zdarzenie z fizycznego przycisku na okularach. */
sealed class ButtonEvent {
    object ShortClick : ButtonEvent()
    object DoubleClick : ButtonEvent()
    object TripleClick : ButtonEvent()
    object LongPress : ButtonEvent()
    object Release : ButtonEvent()
}

/** Błąd warstwy komunikacji z okularami. */
class JarvisException(message: String) : Exception(message)
