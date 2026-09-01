package pl.victor.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wi-Fi Direct do pobierania plików z okularów.
 *
 * Zdjęcia da się dostać po BLE jako miniatury, ale wideo i pliki w pełnej
 * rozdzielczości idą wyłącznie po HTTP z serwera na okularach. Żeby telefon
 * miał do niego trasę, musi najpierw dołączyć do grupy Wi-Fi Direct okularów.
 *
 * Kolejność operacji:
 * 1. BLE: `glassesControl(0x02 0x01 0x04)` włącza tryb transferu
 * 2. Wi-Fi Direct: odkrycie urządzeń i połączenie (WPS PBC)
 * 3. Przypięcie procesu do sieci P2P - bez tego na części telefonów
 *    (zwłaszcza Samsung) ruch idzie domyślną siecią i HTTP nie dochodzi
 * 4. BLE notify 0x08 podaje IP okularów
 * 5. HTTP: `/files/media.config`, potem `/files/<nazwa>`
 *
 * Pułapka: `WifiP2pInfo.groupOwnerAddress` to zwykle **telefon**
 * (`192.168.49.1`), a nie okulary. Adresu okularów szukamy w ramce BLE 0x08.
 */
class GlassesWifiTransfer(context: Context) {

    private val appContext: Context = context.applicationContext
    private val tag = TAG

    private val wifiP2pManager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _state = MutableStateFlow(TransferState.IDLE)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var peersDeferred: CompletableDeferred<List<WifiP2pDevice>>? = null
    private var connectionDeferred: CompletableDeferred<WifiP2pInfo>? = null
    private var receiverRegistered = false
    private var boundNetwork: Network? = null

    /** Czy urządzenie ma uprawnienie wymagane do Wi-Fi Direct. */
    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Czy Wi-Fi Direct jest w ogóle dostępny na tym urządzeniu. */
    fun isAvailable(): Boolean = wifiP2pManager != null

    // === Cykl życia ===

    /** Inicjalizuje kanał P2P i rejestruje nasłuch zdarzeń. */
    @Synchronized
    fun start() {
        val manager = wifiP2pManager ?: run {
            Log.w(tag, "Wi-Fi Direct niedostępny na tym urządzeniu")
            return
        }
        if (channel == null) {
            channel = manager.initialize(appContext, Looper.getMainLooper()) {
                Log.w(tag, "Kanał P2P rozłączony")
                channel = null
                _state.value = TransferState.IDLE
            }
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                p2pReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    /** Rozłącza grupę P2P i zwalnia zasoby. */
    @Synchronized
    fun stop() {
        unbindProcessFromNetwork()
        removeGroup()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(p2pReceiver) }
                .onFailure { Log.w(tag, "unregisterReceiver nie powiodło się", it) }
            receiverRegistered = false
        }
        _state.value = TransferState.IDLE
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val manager = wifiP2pManager ?: return
            val ch = channel ?: return

            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!hasPermission()) return
                    manager.requestPeers(ch) { peers ->
                        val list = peers.deviceList.toList()
                        Log.d(tag, "Znaleziono ${list.size} urządzeń P2P")
                        peersDeferred?.takeIf { !it.isCompleted && list.isNotEmpty() }
                            ?.complete(list)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (!hasPermission()) return
                    manager.requestConnectionInfo(ch) { info ->
                        if (info != null && info.groupFormed) {
                            Log.i(tag, "Grupa P2P utworzona (właściciel=${info.isGroupOwner})")
                            connectionDeferred?.takeIf { !it.isCompleted }?.complete(info)
                        } else {
                            Log.d(tag, "Grupa P2P rozwiązana")
                            _state.value = TransferState.IDLE
                        }
                    }
                }
            }
        }
    }

    // === Połączenie ===

    /**
     * Łączy telefon z grupą Wi-Fi Direct okularów.
     *
     * @param deviceNameHint fragment nazwy urządzenia okularów; gdy `null`,
     *                       brany jest pierwszy widoczny peer
     * @return `true` gdy grupa została utworzona i proces przypięty do sieci P2P
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceNameHint: String? = null): Boolean {
        val manager = wifiP2pManager ?: return false
        if (!hasPermission()) {
            Log.w(tag, "Brak uprawnienia do Wi-Fi Direct")
            _state.value = TransferState.NO_PERMISSION
            return false
        }

        start()
        val ch = channel ?: return false

        _state.value = TransferState.DISCOVERING
        val peers = discoverPeers(manager, ch)
        if (peers.isEmpty()) {
            Log.w(tag, "Nie znaleziono urządzeń Wi-Fi Direct")
            _state.value = TransferState.FAILED
            return false
        }

        val target = deviceNameHint
            ?.let { hint -> peers.firstOrNull { it.deviceName.contains(hint, ignoreCase = true) } }
            ?: peers.first()
        Log.i(tag, "Łączę z ${target.deviceName} (${target.deviceAddress})")

        _state.value = TransferState.CONNECTING
        val info = connectToDevice(manager, ch, target)
        if (info == null) {
            Log.w(tag, "Nie udało się utworzyć grupy P2P")
            _state.value = TransferState.FAILED
            return false
        }

        // Bez tego na części telefonów ruch HTTP pójdzie zwykłym Wi-Fi.
        bindProcessToP2pNetwork()
        _state.value = TransferState.CONNECTED
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverPeers(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): List<WifiP2pDevice> {
        val deferred = CompletableDeferred<List<WifiP2pDevice>>()
        peersDeferred = deferred

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Skanowanie P2P wystartowało")
            }

            override fun onFailure(reason: Int) {
                Log.w(tag, "Skanowanie P2P nie wystartowało (kod=$reason)")
                if (!deferred.isCompleted) deferred.complete(emptyList())
            }
        })

        val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { deferred.await() } ?: emptyList()
        peersDeferred = null
        return result
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        device: WifiP2pDevice
    ): WifiP2pInfo? {
        val deferred = CompletableDeferred<WifiP2pInfo>()
        connectionDeferred = deferred

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // WPS Push Button - tak łączy się oficjalna aplikacja producenta.
            wps.setup = android.net.wifi.WpsInfo.PBC
        }

        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Żądanie połączenia P2P wysłane")
            }

            override fun onFailure(reason: Int) {
                Log.w(tag, "Żądanie połączenia P2P odrzucone (kod=$reason)")
                if (!deferred.isCompleted) deferred.cancel()
            }
        })

        val info = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { deferred.await() }.getOrNull()
        }
        connectionDeferred = null
        return info
    }

    @SuppressLint("MissingPermission")
    private fun removeGroup() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(tag, "Grupa P2P usunięta")
            }

            override fun onFailure(reason: Int) {
                Log.d(tag, "Nie usunięto grupy P2P (kod=$reason)")
            }
        })
    }

    // === Routing ===

    /**
     * Przypina proces do sieci Wi-Fi Direct, żeby żądania HTTP trafiały
     * do okularów, a nie domyślną trasą (np. przez sieć komórkową).
     */
    private fun bindProcessToP2pNetwork() {
        val cm = connectivityManager ?: return
        val network = findP2pNetwork(cm)
        if (network == null) {
            Log.i(tag, "Nie znalazłem interfejsu p2p - zostawiam domyślny routing")
            return
        }
        val bound = runCatching { cm.bindProcessToNetwork(network) }
            .onFailure { Log.w(tag, "bindProcessToNetwork nie powiodło się", it) }
            .getOrDefault(false)
        if (bound) {
            boundNetwork = network
            Log.i(tag, "Proces przypięty do sieci P2P")
        }
    }

    /** Zdejmuje przypięcie procesu do sieci P2P. */
    private fun unbindProcessFromNetwork() {
        if (boundNetwork == null) return
        runCatching { connectivityManager?.bindProcessToNetwork(null) }
            .onFailure { Log.w(tag, "Odpięcie od sieci nie powiodło się", it) }
        boundNetwork = null
        Log.d(tag, "Proces odpięty od sieci P2P")
    }

    /** Szuka sieci, której interfejs nazywa się `p2p...` - to grupa Wi-Fi Direct. */
    private fun findP2pNetwork(cm: ConnectivityManager): Network? = try {
        cm.allNetworks.firstOrNull { network ->
            cm.getLinkProperties(network)?.interfaceName?.startsWith("p2p") == true
        }
    } catch (e: Exception) {
        Log.w(tag, "Nie udało się wyszukać sieci P2P", e)
        null
    }

    /** Krótka pauza po zestawieniu grupy - serwer HTTP na okularach wstaje z opóźnieniem. */
    suspend fun awaitServerReady() {
        delay(SERVER_WARMUP_MS)
    }

    companion object {
        private const val TAG = "GlassesWifiTransfer"

        private const val DISCOVERY_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val SERVER_WARMUP_MS = 1_500L
    }
}

/** Stan połączenia Wi-Fi Direct z okularami. */
enum class TransferState {
    IDLE,
    NO_PERMISSION,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    FAILED
}
