package pl.victor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.victor.app.localmodel.DeviceCapability
import pl.victor.app.localmodel.LocalModelCatalog
import pl.victor.app.localmodel.LocalModelDownloadService
import pl.victor.app.localmodel.LocalModelStorage

/**
 * Pobieranie i status modelu lokalnego (offline). Osobna sekcja, nie
 * generyczny wybór modelu z [ModelSection] - tu chodzi o pobranie pliku,
 * nie wybór wersji API.
 */
@Composable
fun LocalModelSection() {
    val context = LocalContext.current
    val entry = LocalModelCatalog.QWEN_0_8B
    val downloadState by LocalModelDownloadService.state.collectAsState()

    var isDownloaded by remember { mutableStateOf(LocalModelStorage.isDownloaded(context, entry)) }
    val assessment = remember { DeviceCapability.assess(context, entry) }

    // Odśwież status po zakończeniu pobierania.
    LaunchedEffect(downloadState) {
        if (downloadState is LocalModelDownloadService.DownloadState.Done) {
            isDownloaded = LocalModelStorage.isDownloaded(context, entry)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Model lokalny (offline)", style = MaterialTheme.typography.titleMedium)
        Text(
            "${entry.displayName} · ${entry.sizeBytes / 1_000_000}MB · min. ${entry.minRamGb.toInt()}GB RAM",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!assessment.supported) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Ten telefon może nie udźwignąć modelu lokalnego:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    assessment.blockers.forEach { blocker ->
                        Text("• $blocker", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        when {
            isDownloaded -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ Pobrany i gotowy", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = {
                        LocalModelStorage.delete(context, entry)
                        isDownloaded = false
                    }) {
                        Text("Usuń")
                    }
                }
                Text(
                    "Wybierz \"Model lokalny (offline)\" jako providera AI wyżej, żeby go używać.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            downloadState is LocalModelDownloadService.DownloadState.InProgress -> {
                val percent = (downloadState as LocalModelDownloadService.DownloadState.InProgress).percent
                Column {
                    LinearProgressIndicator(
                        progress = percent / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Pobieranie... $percent%", style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> {
                if (downloadState is LocalModelDownloadService.DownloadState.Failed) {
                    Text(
                        "Ostatnie pobieranie nie powiodło się: " +
                            (downloadState as LocalModelDownloadService.DownloadState.Failed).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = { LocalModelDownloadService.start(context, entry.id) },
                    enabled = assessment.supported
                ) {
                    Text("Pobierz (${entry.sizeBytes / 1_000_000}MB)")
                }
            }
        }
    }
}
