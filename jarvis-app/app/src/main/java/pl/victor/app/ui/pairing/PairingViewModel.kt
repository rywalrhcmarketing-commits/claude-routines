package pl.victor.app.ui.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.victor.app.VictorApplication
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.DiscoveredDevice
import pl.victor.app.ble.VictorManager

/**
 * ViewModel dla ekranu parowania.
 * Zarządza skanem BLE i obserwacją wyników.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication
    private val manager: VictorManager = app.glassesManager

    // Lista znalezionych urządzeń (z VictorManager)
    val devices: StateFlow<List<DiscoveredDevice>> = manager.discoveredDevices
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Stan UI
    private val _state = MutableStateFlow(PairingState.IDLE)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    init {
        // Obserwuj stan połączenia - mapuj na PairingState
        viewModelScope.launch {
            manager.connectionState.collect { connState ->
                _state.value = when (connState) {
                    ConnectionState.DISCONNECTED -> PairingState.IDLE
                    ConnectionState.SCANNING -> PairingState.SCANNING
                    ConnectionState.CONNECTING -> PairingState.CONNECTING
                    ConnectionState.CONNECTED -> PairingState.CONNECTING  // jeszcze nie gotowe
                    ConnectionState.READY -> PairingState.CONNECTED
                    ConnectionState.ERROR -> PairingState.ERROR
                }
            }
        }
    }

    /**
     * Rozpoczyna skanowanie BLE.
     */
    fun startScan() {
        viewModelScope.launch {
            _state.value = PairingState.SCANNING
            try {
                manager.startScan()
            } catch (e: SecurityException) {
                _state.value = PairingState.ERROR
            } catch (e: Exception) {
                _state.value = PairingState.ERROR
            }
        }
    }

    /**
     * Łączy z wybranym urządzeniem.
     */
    fun connect(device: DiscoveredDevice) {
        viewModelScope.launch {
            _state.value = PairingState.CONNECTING
            try {
                manager.stopScan()
                manager.connect(device.address)
            } catch (e: Exception) {
                _state.value = PairingState.ERROR
            }
        }
    }

    /**
     * Użytkownik odmówił uprawnień Bluetooth - bez nich skan nigdy nie wystartuje,
     * więc ekran musi to jawnie pokazać zamiast cicho zostać na "Gotowy do skanowania".
     */
    fun onPermissionsDenied() {
        _state.value = PairingState.PERMISSIONS_DENIED
    }
}

enum class PairingState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR,
    PERMISSIONS_DENIED
}
