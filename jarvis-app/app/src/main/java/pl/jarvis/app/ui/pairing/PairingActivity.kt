package pl.jarvis.app.ui.pairing

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.jarvis.app.ble.ConnectionState
import pl.jarvis.app.ble.DiscoveredDevice
import pl.jarvis.app.ui.theme.JarvisTheme

/**
 * Ekran parowania z okularami HeyCyan.
 * - Skan BLE
 * - Lista wykrytych urządzeń
 * - Klik = połącz
 * - Pokazuje status połączenia
 */
class PairingActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Po uzyskaniu uprawnień - rozpocznij skanowanie
        // (viewModel jest tworzony w setContent, więc trigger przez intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
        setContent {
            JarvisTheme {
                PairingScreen(onBack = { finish() })
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit,
    viewModel: PairingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val devices by viewModel.devices.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Połącz z okularami") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startScan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Skanuj ponownie")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status card
            StatusCard(state)

            HorizontalDivider()

            // Info text
            Text(
                text = "1. Włącz okulary (przytrzymaj przycisk 3 sekundy)\n" +
                        "2. Poczekaj aż dioda LED zacznie migać\n" +
                        "3. Wybierz urządzenie z listy poniżej",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Lista urządzeń
            if (devices.isEmpty() && state == PairingState.SCANNING) {
                Text("Szukam urządzeń...", style = MaterialTheme.typography.bodyMedium)
            } else if (devices.isEmpty()) {
                Text(
                    "Brak urządzeń. Wciśnij przycisk odświeżania.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            onClick = { viewModel.connect(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: PairingState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                PairingState.IDLE -> {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Text("Gotowy do skanowania")
                }
                PairingState.SCANNING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Skanowanie Bluetooth...", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                PairingState.CONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Łączenie...")
                }
                PairingState.CONNECTED -> {
                    Icon(
                        Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(
                            "Połączono!",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Okulary gotowe do użycia",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                PairingState.ERROR -> {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Błąd połączenia", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClick) {
                Text("Połącz")
            }
        }
    }
}
