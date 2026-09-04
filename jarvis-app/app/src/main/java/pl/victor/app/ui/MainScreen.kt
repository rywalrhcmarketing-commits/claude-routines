package pl.victor.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.victor.app.OrchestratorState
import pl.victor.app.ui.components.ActionConfirmationDialog
import pl.victor.app.ui.components.ModelBadge
import pl.victor.app.ui.components.NewModelsBanner
import pl.victor.app.ui.history.HistoryActivity
import pl.victor.app.ui.pairing.PairingActivity
import pl.victor.app.ui.settings.SettingsActivity

/**
 * Główny ekran apki.
 * - Przycisk "Zadaj pytanie" (capture)
 * - Pole tekstowe (alternatywa)
 * - Status / odpowiedź AI
 * - Top bar: ustawienia, historia
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit = {},
    onRequestGoogleSignIn: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val modelWarning by viewModel.modelWarning.collectAsState()
    val newModels by viewModel.newModels.collectAsState()
    val currentModelId by viewModel.currentModelId.collectAsState()
    val pendingAction by viewModel.pendingActionConfirmation.collectAsState()
    var textInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val glassesManager = pl.victor.app.VictorApplication.get().glassesManager

    // Model mógł zostać zmieniony w ustawieniach - odśwież badge po powrocie na ten ekran
    // (ustawienia to osobne Activity, więc wracamy tu przez ON_RESUME).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshModelBadge()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val connState by glassesManager.connectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pl.victor.app.ui.brand.VictorMark(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("V.I.C.T.O.R.")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, PairingActivity::class.java))
                    }) {
                        Icon(
                            if (connState == pl.victor.app.ble.ConnectionState.READY)
                                Icons.Default.BluetoothConnected
                            else
                                Icons.Default.Bluetooth,
                            contentDescription = "Połącz z okularami"
                        )
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent(context, HistoryActivity::class.java))
                    }) {
                        Icon(Icons.Default.History, contentDescription = "Historia")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ustawienia")
                    }
                    // Badge dla trybu konwersacyjnego
                    val orch = pl.victor.app.VictorApplication.get().orchestrator
                    val convOn by orch.conversationalModeFlow.collectAsState()
                    if (convOn) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Tryb konwersacyjny ON",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {

        // Banner o nowych modelach
        NewModelsBanner(
            newModels = newModels,
            onDismiss = { viewModel.clearNewModels() },
            onCheckAgain = { viewModel.refreshModels() }
        )

        // Animowany badge aktualnego modelu
        ModelBadge(
            modelId = currentModelId,
            showDetails = state is OrchestratorState.Idle
        )

        // Banner ostrzegawczy o modelu
        modelWarning?.let { warning ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⚠️",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearModelWarning() }) {
                        Text("OK")
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val currentState = state) {
                is OrchestratorState.Idle -> IdleContent(
                    textInput = textInput,
                    onTextChange = { textInput = it },
                    onCaptureClick = { viewModel.onCaptureButtonPressed() },
                    onTextSubmit = {
                        viewModel.onTextSubmit(textInput)
                        textInput = ""
                    }
                )

                is OrchestratorState.Capturing -> CapturingContent(currentState)

                is OrchestratorState.Thinking -> ThinkingContent()

                is OrchestratorState.Streaming -> StreamingContent(currentState.text)

                is OrchestratorState.Completed -> CompletedContent(
                    text = currentState.text,
                    onReset = { viewModel.resetState() }
                )

                is OrchestratorState.Error -> ErrorContent(
                    message = currentState.message,
                    onReset = { viewModel.resetState() }
                )
            }
        }
        }
    }

    // Dialog potwierdzenia akcji (SMS, call w trybie DIRECT)
    pendingAction?.let { pending ->
        ActionConfirmationDialog(
            pending = pending,
            onConfirm = { viewModel.confirmAction() },
            onCancel = { viewModel.cancelAction() }
        )
    }
}

@Composable
private fun IdleContent(
    textInput: String,
    onTextChange: (String) -> Unit,
    onCaptureClick: () -> Unit,
    onTextSubmit: () -> Unit
) {
    val orch = pl.victor.app.VictorApplication.get().orchestrator
    val accessibilityMode by orch.accessibility.mode.collectAsState()

    Text(
        text = "Hej, jestem Twoim asystentem AI",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Wciśnij przycisk na okularach, przycisk poniżej, lub wpisz pytanie",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // === Panel Accessibility (niewidomi) ===
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🦯", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    "Asystent niewidomych",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                if (accessibilityMode != pl.victor.app.accessibility.AccessibilityMode.OFF)
                    "Aktywny: ${accessibilityMode.emoji} ${accessibilityMode.displayName}"
                else "Komendy: \"czytaj\", \"co przede mną\", \"prowadź\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { orch.accessibility.enableReadText() },
                    modifier = Modifier.weight(1f),
                    enabled = accessibilityMode == pl.victor.app.accessibility.AccessibilityMode.OFF
                ) { Text("📖 Czytaj", fontSize = 11.sp) }
                Button(
                    onClick = { orch.accessibility.enableDescribeScene() },
                    modifier = Modifier.weight(1f),
                    enabled = accessibilityMode == pl.victor.app.accessibility.AccessibilityMode.OFF
                ) { Text("👁️ Opisuj", fontSize = 11.sp) }
                Button(
                    onClick = { orch.accessibility.enableNavigate() },
                    modifier = Modifier.weight(1f),
                    enabled = accessibilityMode == pl.victor.app.accessibility.AccessibilityMode.OFF
                ) { Text("🧭 Prowadź", fontSize = 11.sp) }
            }
            if (accessibilityMode != pl.victor.app.accessibility.AccessibilityMode.OFF) {
                Spacer(modifier = Modifier.size(8.dp))
                Button(
                    onClick = { orch.accessibility.disable() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("⏹️ Zatrzymaj tryb") }
            }
        }
    }

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onCaptureClick,
        modifier = Modifier.size(160.dp)
    ) {
        Icon(
            Icons.Default.Camera,
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
    }

    Spacer(modifier = Modifier.height(48.dp))

    OutlinedTextField(
        value = textInput,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Lub wpisz pytanie…") },
        singleLine = true,
        trailingIcon = {
            if (textInput.isNotBlank()) {
                Button(onClick = onTextSubmit) {
                    Text("Wyślij")
                }
            }
        }
    )
}

@Composable
private fun CapturingContent(state: OrchestratorState.Capturing) {
    CircularProgressIndicator(
        progress = { state.progress.toFloat() / state.total },
        modifier = Modifier.size(120.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Przechwytuję obraz…",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "${state.progress}/${state.total}",
        style = MaterialTheme.typography.headlineLarge
    )
}

@Composable
private fun ThinkingContent() {
    CircularProgressIndicator(modifier = Modifier.size(80.dp))

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "AI myśli…",
        style = MaterialTheme.typography.titleMedium
    )
}

@Composable
private fun StreamingContent(text: String) {
    // Pulsujące kropki
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha))
        Spacer(Modifier.size(4.dp))
        Text("●", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha * 0.7f))
        Spacer(Modifier.size(4.dp))
        Text("●", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha * 0.4f))
    }

    Spacer(Modifier.height(16.dp))

    Text(
        text = "AI odpowiada (streamuje)…",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))

    // Tekst pojawia się z animacją
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )
}

@Composable
private fun CompletedContent(text: String, onReset: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(onClick = onReset) {
        Text("OK")
    }
}

@Composable
private fun ErrorContent(message: String, onReset: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(onClick = onReset) {
        Text("Spróbuj ponownie")
    }
}
