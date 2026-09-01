package pl.victor.app.ui.history

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.victor.app.data.ConversationEntry
import pl.victor.app.ui.theme.VictorTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran historii rozmów.
 * Lista ostatnich 20 rozmów z miniaturką pierwszego zdjęcia.
 */
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VictorTheme {
                HistoryScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val entries by viewModel.entries.collectAsState()
    var selectedEntry by remember { mutableStateOf<ConversationEntry?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Wyczyść")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    HistoryItem(
                        entry = entry,
                        onClick = { selectedEntry = entry },
                        onDelete = { viewModel.delete(entry.id) }
                    )
                }
            }
        }
    }

    // Dialog: szczegóły wpisu
    selectedEntry?.let { entry ->
        EntryDetailDialog(
            entry = entry,
            onDismiss = { selectedEntry = null }
        )
    }

    // Dialog: potwierdzenie czyszczenia
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Wyczyść historię?") },
            text = { Text("Wszystkie ${entries.size} wpisów zostanie usuniętych. Tej operacji nie można cofnąć.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll()
                    showClearConfirm = false
                }) {
                    Text("Wyczyść")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Anuluj")
                }
            }
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("Brak historii", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Twoje rozmowy z AI pojawią się tutaj",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryItem(
    entry: ConversationEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Miniaturka pierwszego zdjęcia
            Thumbnail(entry.firstPhotoPath)

            Spacer(Modifier.size(12.dp))

            // Tekst
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.userQuestion.ifBlank { "(brak pytania)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = entry.aiResponse,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = formatTimestamp(entry.timestamp) + " · " + entry.providerId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Delete
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Usuń",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String?) {
    if (path == null || !File(path).exists()) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val bitmap = remember(path) {
        try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun EntryDetailDialog(entry: ConversationEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Szczegóły rozmowy") },
        text = {
            Column {
                if (entry.firstPhotoPath != null && File(entry.firstPhotoPath).exists()) {
                    val bitmap = remember(entry.firstPhotoPath) {
                        BitmapFactory.decodeFile(entry.firstPhotoPath)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text("Pytanie:", style = MaterialTheme.typography.labelMedium)
                Text(entry.userQuestion.ifBlank { "(brak)" }, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text("Odpowiedź:", style = MaterialTheme.typography.labelMedium)
                Text(entry.aiResponse, style = MaterialTheme.typography.bodyMedium)

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text(
                    text = "${formatTimestamp(entry.timestamp)} · ${entry.providerId} · ${entry.tokensUsed} tokenów · ${entry.photoCount} zdjęć",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zamknij") }
        }
    )
}

private fun formatTimestamp(ms: Long): String {
    val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return df.format(Date(ms))
}
