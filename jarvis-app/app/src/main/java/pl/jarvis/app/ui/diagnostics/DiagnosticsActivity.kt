package pl.jarvis.app.ui.diagnostics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.jarvis.app.ble.ConnectionState
import pl.jarvis.app.ble.NotifyLogEntry
import pl.jarvis.app.ui.theme.JarvisTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran diagnostyczny okularów.
 *
 * Pokazuje stan połączenia, baterię, IP, liczniki plików i **surowe ramki notify**
 * razem z tym, jak je zrozumiał [pl.jarvis.app.ble.GlassesProtocol]. Gdy coś nie
 * działa ze sprzętem, to jest pierwsze miejsce do sprawdzenia: widać, czy okulary
 * w ogóle coś przysłały, czy problem jest po naszej stronie.
 *
 * Przełącznik symulacji pozwala przejść całą ścieżkę bez okularów.
 */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                DiagnosticsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val connection by viewModel.connectionState.collectAsState()
    val battery by viewModel.batteryLevel.collectAsState()
    val charging by viewModel.isCharging.collectAsState()
    val ip by viewModel.glassesIp.collectAsState()
    val media by viewModel.mediaCount.collectAsState()
    val log by viewModel.notifyLog.collectAsState()
    val lastCommand by viewModel.lastCommand.collectAsState()
    val simulated by viewModel.simulationEnabled.collectAsState()
    val result by viewModel.result.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val recordingFileType by viewModel.recordingFileType.collectAsState()
    val recordingProgress by viewModel.recordingProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostyka okularów") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearLog() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Wyczyść dziennik")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    connection = connection,
                    battery = battery,
                    charging = charging,
                    ip = ip,
                    mediaSummary = media?.let {
                        "${it.images} zdjęć, ${it.videos} wideo, ${it.records} nagrań"
                    },
                    lastCommand = lastCommand,
                    simulated = simulated
                )
            }

            item {
                SimulationCard(
                    enabled = simulated,
                    onToggle = viewModel::setSimulation,
                    onConnect = viewModel::connectSimulated,
                    onDisconnect = viewModel::disconnect,
                    onButton = viewModel::injectButtonPress,
                    onLowBattery = viewModel::injectLowBattery,
                    onLowMemory = viewModel::injectLowMemory
                )
            }

            item {
                SectionCard("Testy funkcji") {
                    Text(
                        "Te same przyciski działają na symulatorze i na prawdziwych okularach - " +
                            "po podłączeniu sprzętu przejdź je po kolei.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = viewModel::testBattery) { Text("Bateria") }
                        FilledTonalButton(onClick = viewModel::testMediaCount) { Text("Liczba plików") }
                        FilledTonalButton(
                            onClick = viewModel::testPhoto,
                            enabled = !busy
                        ) { Text("Zdjęcie") }
                        FilledTonalButton(onClick = viewModel::testVideo) { Text("Start wideo") }
                        FilledTonalButton(onClick = viewModel::stopVideo) { Text("Stop wideo") }
                        FilledTonalButton(onClick = viewModel::testAudio) { Text("Start audio") }
                        FilledTonalButton(onClick = viewModel::stopAudio) { Text("Stop audio") }
                        FilledTonalButton(onClick = viewModel::testTransferMode) { Text("Tryb transferu") }
                        FilledTonalButton(
                            onClick = viewModel::testFileList,
                            enabled = !busy
                        ) { Text("Lista plików") }
                        FilledTonalButton(
                            onClick = viewModel::testDownloadPhoto,
                            enabled = !busy
                        ) { Text("Pobierz zdjęcie") }
                        OutlinedButton(onClick = viewModel::resetP2p) { Text("Reset P2P") }
                    }
                    result?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                RecordingsCard(
                    fileType = recordingFileType,
                    onFileTypeChange = viewModel::setRecordingFileType,
                    progress = recordingProgress,
                    busy = busy,
                    onList = viewModel::testListRecordings,
                    onDownload = viewModel::testDownloadRecording
                )
            }

            item {
                Text(
                    "Dziennik ramek notify (${log.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (log.isEmpty()) {
                item {
                    Text(
                        "Pusto. Okulary nie przysłały jeszcze żadnej ramki - " +
                            "albo nie są połączone, albo nasłuch nie wystartował.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(log) { entry -> NotifyRow(entry) }
            }
        }
    }
}

@Composable
private fun StatusCard(
    connection: ConnectionState,
    battery: Int?,
    charging: Boolean,
    ip: String?,
    mediaSummary: String?,
    lastCommand: String?,
    simulated: Boolean
) {
    SectionCard(if (simulated) "Stan (SYMULACJA)" else "Stan") {
        StatusRow("Połączenie", connection.polish())
        StatusRow(
            "Bateria",
            battery?.let { "$it%" + if (charging) " (ładowanie)" else "" } ?: "nieznana"
        )
        StatusRow("IP okularów", ip ?: "brak (tryb transferu wyłączony)")
        StatusRow("Pliki", mediaSummary ?: "nie sprawdzono")
        StatusRow("Ostatnia komenda", lastCommand ?: "żadna")
    }
}

@Composable
private fun RecordingsCard(
    fileType: Int,
    onFileTypeChange: (Int) -> Unit,
    progress: Float?,
    busy: Boolean,
    onList: () -> Unit,
    onDownload: () -> Unit
) {
    SectionCard("Nagrania głosowe przez BLE") {
        Text(
            "Osobny kanał vendor SDK - działa bez Wi-Fi Direct, więc to droga " +
                "awaryjna, gdy grupa P2P nie chce się podnieść.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Producent nie podaje numeru typu pliku, a SDK startuje z zerem. " +
                "Jeśli lista wraca pusta mimo nagrań w pamięci, przejdź kolejne typy.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Typ pliku: $fileType", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { onFileTypeChange(fileType - 1) },
                enabled = fileType > 0,
                modifier = Modifier.padding(start = 12.dp)
            ) { Text("−") }
            OutlinedButton(
                onClick = { onFileTypeChange(fileType + 1) },
                enabled = fileType < 7,
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("+") }
        }

        Spacer(Modifier.height(8.dp))
        progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        FlowRowCompat {
            FilledTonalButton(onClick = onList, enabled = !busy) { Text("Lista nagrań") }
            FilledTonalButton(onClick = onDownload, enabled = !busy) { Text("Pobierz nagranie") }
        }
    }
}

@Composable
private fun SimulationCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onButton: () -> Unit,
    onLowBattery: () -> Unit,
    onLowMemory: () -> Unit
) {
    SectionCard("Symulowane okulary") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Tryb symulacji", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Udawany jest tylko transport BLE - dekodowanie ramek, stan i cała " +
                        "warstwa AI działają na prawdziwym kodzie.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        if (enabled) {
            Spacer(Modifier.height(12.dp))
            FlowRowCompat {
                Button(onClick = onConnect) { Text("Połącz") }
                OutlinedButton(onClick = onDisconnect) { Text("Rozłącz") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Wstrzyknij zdarzenie:", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            FlowRowCompat {
                OutlinedButton(onClick = onButton) { Text("Przycisk AI") }
                OutlinedButton(onClick = onLowBattery) { Text("Bateria 9%") }
                OutlinedButton(onClick = onLowMemory) { Text("Brak pamięci") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NotifyRow(entry: NotifyLogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(entry.meaning, style = MaterialTheme.typography.bodyMedium)
            Text(
                TIME_FORMAT.format(Date(entry.timestampMs)),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            entry.hex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

private fun ConnectionState.polish(): String = when (this) {
    ConnectionState.DISCONNECTED -> "rozłączone"
    ConnectionState.SCANNING -> "skanowanie"
    ConnectionState.CONNECTING -> "łączenie"
    ConnectionState.CONNECTED -> "połączone (trwa wykrywanie usług)"
    ConnectionState.READY -> "gotowe"
    ConnectionState.ERROR -> "błąd"
}
