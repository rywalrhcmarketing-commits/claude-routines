package pl.victor.app.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.victor.app.ai.AIProviderFactory
import pl.victor.app.ai.ProviderInfo
import pl.victor.app.data.ModelInfo
import pl.victor.app.ui.theme.VictorTheme

/**
 * Ekran ustawień - wybór providera AI, klucze API, opcje.
 */
class SettingsActivity : ComponentActivity() {

    private val googleSignInLauncher = androidx.activity.result.contract.ActivityResultContracts
        .StartActivityForResult()
        .let { contract ->
            registerForActivityResult(contract) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val app = application as pl.victor.app.VictorApplication
                    app.settings.setGoogleAccountConnected(true)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekran pokazuje klucze API - blokuj zrzuty ekranu i podgląd w menu zadań.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            VictorTheme {
                SettingsScreen(
                    onBack = { finish() },
                    onRequestGoogleSignIn = {
                        try {
                            val googleAccount = pl.victor.app.google.GoogleAccountManager(this@SettingsActivity)
                            val signInIntent = googleAccount.getSignInIntent()
                            googleSignInLauncher.launch(signInIntent)
                        } catch (e: Exception) {
                            android.util.Log.e("SettingsActivity", "Google Sign-In failed", e)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequestGoogleSignIn: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wstecz")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sekcja: Provider AI
            ProviderSection(
                currentProviderId = state.activeProviderId,
                providers = AIProviderFactory.supportedProviders(),
                onProviderSelected = { viewModel.setActiveProvider(it) }
            )

            HorizontalDivider()

            // Sekcja: Model
            ModelSection(
                providerId = state.activeProviderId,
                selectedModelId = state.selectedModelId,
                onModelSelected = { viewModel.setSelectedModel(it) }
            )

            HorizontalDivider()

            // Sekcja: Klucze API
            ProviderKeysSection(
                providers = AIProviderFactory.supportedProviders(),
                getKey = { viewModel.getApiKey(it) },
                onKeyChange = { id, key -> viewModel.setApiKey(id, key) },
                isTestRunning = state.isTestRunning,
                onTestClick = { viewModel.testConnection() }
            )

            HorizontalDivider()

            // Sekcja: Model lokalny (offline)
            LocalModelSection()

            HorizontalDivider()

            // Sekcja: Opcje AI
            AIOptionsSection(
                webSearchEnabled = state.webSearchEnabled,
                onWebSearchChange = { viewModel.setWebSearchEnabled(it) },
                responseLanguage = state.responseLanguage,
                onLanguageChange = { viewModel.setResponseLanguage(it) }
            )

            HorizontalDivider()

            // Sekcja: Capture
            CaptureSection(
                count = state.captureCount,
                intervalMs = state.captureIntervalMs,
                onCountChange = { viewModel.setCaptureCount(it) },
                onIntervalChange = { viewModel.setCaptureInterval(it) }
            )

            HorizontalDivider()

            // Sekcja: Głos TTS
            VoiceSection(
                voices = state.availableVoices,
                currentVoice = state.currentVoice,
                speechRate = state.ttsSpeechRate,
                pitch = state.ttsPitch,
                onVoiceSelected = { viewModel.setTtsVoice(it) },
                onRateChange = { viewModel.setTtsRate(it) },
                onPitchChange = { viewModel.setTtsPitch(it) },
                onTestClick = { viewModel.testVoice() }
            )

            // Sekcja: Jak pobrać więcej głosów
            VoiceInstallGuideSection()

            HorizontalDivider()

            // Sekcja: Persona / styl komunikacji
            PersonaSection(
                selectedPersonaId = state.selectedPersonaId,
                customPrompt = state.customPersonaPrompt,
                onPersonaSelected = { viewModel.setPersona(it) },
                onCustomPromptChange = { viewModel.setCustomPersonaPrompt(it) }
            )

            HorizontalDivider()

            // Sekcja: Test skanera QR
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🧪 Narzędzia diagnostyczne",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Test ML Kit Barcode Scanner - wybierz zdjęcie z kodem QR z galerii.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    // LocalContext.current da się odczytać tylko w kontekście
                    // composable - nie wewnątrz lambdy onClick.
                    val qrTestContext = LocalContext.current
                    TextButton(
                        onClick = {
                            qrTestContext.startActivity(
                                android.content.Intent(
                                    qrTestContext,
                                    pl.victor.app.vision.QRTestActivity::class.java
                                )
                            )
                        }
                    ) {
                        Text("🔍 Test skanera QR")
                    }

                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Diagnostyka okularów: stan połączenia, surowe ramki notify i tryb " +
                            "symulacji, który pozwala przejść całą ścieżkę bez sprzętu.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = {
                            qrTestContext.startActivity(
                                android.content.Intent(
                                    qrTestContext,
                                    pl.victor.app.ui.diagnostics.DiagnosticsActivity::class.java
                                )
                            )
                        }
                    ) {
                        Text("🕶️ Diagnostyka okularów")
                    }
                }
            }

            HorizontalDivider()

            // Sekcja: Akcje (komendy głosowe)
            ActionsSection()

            HorizontalDivider()

            // Sekcja: Inteligentne funkcje (nowe v1.2)
            IntelligenceSection(
                onManageGoogleAccount = { onRequestGoogleSignIn() }
            )

            HorizontalDivider()

            // Sekcja: Aparat i tryb przechwytywania
            CaptureModeSection()

            HorizontalDivider()

            // Sekcja: Dostępność
            AccessibilitySection()

            HorizontalDivider()

            // Sekcja: Proaktywne alerty
            ProactiveAlertsSection()

            HorizontalDivider()

            // Sekcja: Onboarding
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🔄 Konfiguracja",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Restartuj onboarding (dla siebie lub kogoś nowego)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = {
                            pl.victor.app.VictorApplication.get().settings.resetOnboarding()
                            val intent = android.content.Intent(
                                context,
                                pl.victor.app.ui.onboarding.OnboardingActivity::class.java
                            )
                            context.startActivity(intent)
                            (context as? android.app.Activity)?.finish()
                        }
                    ) {
                        Text("🚀 Restartuj onboarding")
                    }
                }
            }

            HorizontalDivider()

            // Sekcja: Wake word (v1.1)
            WakeWordSection(
                enabled = state.wakeWordEnabled,
                selectedId = state.wakeWordId,
                customPhrase = state.customWakeWord,
                keywordPath = state.customKeywordPath,
                modelPath = state.customModelPath,
                picovoiceAccessKey = state.picovoiceAccessKey,
                onEnabledChange = { viewModel.setWakeWordEnabled(it) },
                onWakeWordSelected = { viewModel.setWakeWordId(it) },
                onCustomPhraseChange = { viewModel.setCustomWakeWord(it) },
                onPicovoiceKeyChange = { viewModel.setPicovoiceAccessKey(it) },
                onKeywordPathChange = { viewModel.setCustomKeywordPath(it) },
                onModelPathChange = { viewModel.setCustomModelPath(it) }
            )

            // Status message
            state.statusMessage?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.size(24.dp))
            DeveloperOptionsGate()
        }
    }
}

/**
 * Ukryta furtka do "Opcji programistycznych" - stuknij numer wersji
 * [TAPS_TO_UNLOCK] razy, tak jak w Androidowym "Numer kompilacji".
 *
 * Wolno stukać z przerwami do 1.5 s - dłuższa pauza resetuje licznik, żeby
 * przypadkowe pojedyncze stuknięcia (np. przy scrollowaniu) nic nie odblokowały.
 */
@Composable
private fun DeveloperOptionsGate() {
    val context = LocalContext.current
    var tapCount by remember { mutableStateOf(0) }
    var lastTapAtMs by remember { mutableStateOf(0L) }

    Text(
        "V.I.C.T.O.R. ${pl.victor.app.BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clickable {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAtMs > TAP_RESET_WINDOW_MS) 1 else tapCount + 1
                lastTapAtMs = now
                when {
                    tapCount >= TAPS_TO_UNLOCK -> {
                        tapCount = 0
                        context.startActivity(
                            android.content.Intent(
                                context,
                                pl.victor.app.ui.developer.DeveloperOptionsActivity::class.java
                            )
                        )
                    }
                    tapCount >= TAPS_TO_UNLOCK - 3 -> {
                        android.widget.Toast.makeText(
                            context,
                            "Jeszcze ${TAPS_TO_UNLOCK - tapCount} stuknięć do Opcji programistycznych",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
    )
}

private const val TAPS_TO_UNLOCK = 7
private const val TAP_RESET_WINDOW_MS = 1_500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSection(
    providerId: String,
    selectedModelId: String?,
    onModelSelected: (String?) -> Unit
) {
    if (providerId == pl.victor.app.ai.AIProviderFactory.LOCAL_PROVIDER_ID) {
        // Model lokalny ma dziś jeden wpis w katalogu, nie listę wersji do
        // wyboru jak providerzy chmurowi - wybór modelu/pobieranie jest
        // niżej, w LocalModelSection.
        return
    }
    val models = remember(providerId) { pl.victor.app.data.ModelRegistry.forProvider(providerId) }
    val selectedInfo = selectedModelId?.let { pl.victor.app.data.ModelRegistry.findById(it) }
    val defaultInfo = pl.victor.app.data.ModelRegistry.defaultFor(providerId)
    val currentInfo = selectedInfo ?: defaultInfo

    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Model AI", style = MaterialTheme.typography.titleMedium)

        // Bieżący model z opisem
        currentInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (info.deprecated) {
                    androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                } else {
                    androidx.compose.material3.CardDefaults.cardColors()
                }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            info.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (info.deprecated) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "PRZESTARZAŁY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        info.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    info.releaseDate?.let {
                        Text(
                            "Wydany: $it" + (info.contextWindow?.let { cw -> " · $cw tokenów" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (info.deprecated && info.replacementId != null) {
                        val replacement = pl.victor.app.data.ModelRegistry.findById(info.replacementId)
                        Text(
                            "→ Następca: ${replacement?.displayName ?: info.replacementId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Dropdown
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentInfo?.displayName ?: "Domyślny",
                onValueChange = {},
                readOnly = true,
                label = { Text("Wybierz model") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // Opcja "Domyślny" na górze
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Domyślny", fontWeight = FontWeight.Bold)
                            Text(
                                "Użyj ${defaultInfo?.displayName ?: "—" }",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    onClick = {
                        onModelSelected(null)
                        expanded = false
                    }
                )
                HorizontalDivider()
                models.forEach { model ->
                    ModelDropdownItem(
                        model = model,
                        isSelected = (selectedModelId ?: defaultInfo?.id) == model.id,
                        onClick = {
                            onModelSelected(model.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        Text(
            "Model jest sprawdzany przy każdym użyciu. Jeśli provider wycofa wybrany model, " +
                    "apka automatycznie przejdzie na nowy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModelDropdownItem(
    model: ModelInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.displayName,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    if (model.deprecated) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "deprecated",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.releaseDate != null) {
                    Text(
                        model.releaseDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSection(
    currentProviderId: String,
    providers: List<ProviderInfo>,
    onProviderSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = providers.find { it.id == currentProviderId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Provider AI", style = MaterialTheme.typography.titleMedium)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = current?.displayName ?: "Wybierz providera",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = {
                    Text("▼", modifier = Modifier.padding(8.dp))
                }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                providers.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(provider.displayName)
                                Text(
                                    provider.description,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!provider.available) {
                                    Text(
                                        "W przygotowaniu",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        onClick = {
                            if (provider.available) {
                                onProviderSelected(provider.id)
                            }
                            expanded = false
                        }
                    )
                }
            }
        }

        current?.let { provider ->
            // LocalContext.current da się odczytać tylko w kontekście composable,
            // nie wewnątrz lambdy onClick.
            val browserContext = LocalContext.current
            TextButton(
                onClick = {
                    // Przycisk był pusty (TODO), więc nie prowadził nigdzie.
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(provider.keyUrl)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { browserContext.startActivity(intent) }
                        .onFailure {
                            // Urządzenie bez przeglądarki - lepiej nic niż wywrotka.
                            android.util.Log.w(
                                "SettingsActivity",
                                "Nie udało się otworzyć ${provider.keyUrl}",
                                it
                            )
                        }
                }
            ) {
                Text("Pobierz klucz API →")
            }
        }
    }
}

@Composable
private fun ProviderKeysSection(
    providers: List<ProviderInfo>,
    getKey: (String) -> String,
    onKeyChange: (String, String) -> Unit,
    isTestRunning: Boolean,
    onTestClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Klucze API", style = MaterialTheme.typography.titleMedium)
        Text(
            "Klucze są szyfrowane lokalnie. Aplikacja nigdy ich nie wysyła na zewnątrz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Model lokalny nie ma klucza API - jego karta jest niżej, w LocalModelSection.
        providers.filter { it.id != pl.victor.app.ai.AIProviderFactory.LOCAL_PROVIDER_ID }.forEach { provider ->
            var keyValue by remember(provider.id) {
                mutableStateOf(getKey(provider.id))
            }
            var saved by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = keyValue,
                onValueChange = {
                    keyValue = it
                    saved = false
                },
                label = { Text(provider.displayName) },
                placeholder = { Text("Wklej klucz API") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                enabled = provider.available,
                supportingText = {
                    if (!provider.available) {
                        Text("Ten provider nie jest jeszcze dostępny")
                    }
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onKeyChange(provider.id, keyValue)
                        saved = true
                    },
                    enabled = keyValue.isNotBlank()
                ) {
                    Text(if (saved) "Zapisano ✓" else "Zapisz")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onTestClick,
            enabled = !isTestRunning
        ) {
            if (isTestRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
            }
            Text("Testuj połączenie z aktywnym providerem")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIOptionsSection(
    webSearchEnabled: Boolean,
    onWebSearchChange: (Boolean) -> Unit,
    responseLanguage: String,
    onLanguageChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Opcje AI", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Wyszukiwanie w sieci")
                Text(
                    "AI może szukać aktualnych informacji (Gemini grounding)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = webSearchEnabled, onCheckedChange = onWebSearchChange)
        }

        // Język - uproszczone (tylko kilka opcji)
        var langExpanded by remember { mutableStateOf(false) }
        val languages = listOf(
            "pl" to "Polski",
            "en" to "English",
            "de" to "Deutsch",
            "fr" to "Français",
            "es" to "Español"
        )
        val current = languages.find { it.first == responseLanguage }?.second ?: "Polski"

        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = !langExpanded }
        ) {
            OutlinedTextField(
                value = current,
                onValueChange = {},
                readOnly = true,
                label = { Text("Język odpowiedzi") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                languages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onLanguageChange(code)
                        langExpanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun CaptureSection(
    count: Int,
    intervalMs: Long,
    onCountChange: (Int) -> Unit,
    onIntervalChange: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Przechwytywanie", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = count.toString(),
                onValueChange = { onCountChange(it.toIntOrNull() ?: count) },
                label = { Text("Liczba zdjęć") },
                modifier = Modifier.weight(1f),
                supportingText = { Text("Domyślnie: 5") }
            )

            OutlinedTextField(
                value = intervalMs.toString(),
                onValueChange = { onIntervalChange(it.toLongOrNull() ?: intervalMs) },
                label = { Text("Interwał (ms)") },
                modifier = Modifier.weight(1f),
                supportingText = { Text("Domyślnie: 2000") }
            )
        }

        Text(
            "5 zdjęć co 1s = 5s. HeyCyan nie ma live stream, więc to nie jest prawdziwe 'live view', ale AI widzi kontekst.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoiceInstallGuideSection() {
    val audio = remember { pl.victor.app.VictorApplication.get().audio }
    var expanded by remember { mutableStateOf(false) }
    val polishOffline = audio.getPolishOfflineVoicesCount()
    val polishTotal = audio.getPolishVoicesCount()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📚 Jak pobrać więcej głosów",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Ukryj" else "Pokaż")
                    }
                }

                // Status
                Text(
                    when {
                        polishOffline >= 2 -> "✅ Masz ${polishOffline} polskie głosy offline - świetnie!"
                        polishOffline == 1 -> "✓ Masz 1 polski głos. Możesz pobrać więcej (żeńskie, inne akcenty)."
                        polishTotal == 0 -> "⚠ Brak polskich głosów. Poniżej instrukcja."
                        else -> "⚠ Polskie głosy wymagają pobrania. Poniżej instrukcja."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                if (expanded) {
                    Spacer(Modifier.size(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.size(8.dp))

                    Text(
                        "Krok po kroku:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "1. Kliknij „Ustawienia TTS” poniżej",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "2. Wybierz „Google Text-to-speech Engine”",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "3. Kliknij ⚙ obok silnika → „Instaluj dane głosowe”",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "4. Wybierz język Polski (pl-PL)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "5. Pobierz wysokiej jakości głosy (WiFi zalecane)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "6. Wróć do tej apki - nowe głosy pojawią się w dropdown",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.size(12.dp))

                    Text(
                        "💡 Wskazówki:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "• Google TTS ma najlepszą jakość dla polskiego\n" +
                                "• Pobrane offline działają bez internetu\n" +
                                "• Jeden głos żeński + jeden męski to dobry start\n" +
                                "• Głosy „WaveNet\" są premium (wymagają konta Google)",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.size(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { audio.openTtsSettings() }) {
                            Text("⚙ Otwórz Ustawienia TTS")
                        }
                        TextButton(onClick = { audio.openGoogleTtsPlayStore() }) {
                            Text("📥 Google TTS (Play Store)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaSection(
    selectedPersonaId: String,
    customPrompt: String,
    onPersonaSelected: (String) -> Unit,
    onCustomPromptChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val personas = remember { pl.victor.app.persona.PersonaRegistry.all() }
    val currentPersona = personas.find { it.id == selectedPersonaId }
        ?: if (selectedPersonaId == "custom") pl.victor.app.persona.PersonaRegistry.customFromPrompt(customPrompt)
        else pl.victor.app.persona.PersonaRegistry.default()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Styl komunikacji AI", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(currentPersona.emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        currentPersona.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        currentPersona.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Dropdown z personami
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentPersona.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Persona") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                personas.forEach { persona ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    persona.emoji,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Column {
                                    Text(
                                        persona.name,
                                        fontWeight = if (persona.id == selectedPersonaId)
                                            androidx.compose.ui.text.font.FontWeight.Bold
                                        else androidx.compose.ui.text.font.FontWeight.Normal
                                    )
                                    Text(
                                        persona.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            onPersonaSelected(persona.id)
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
                // Custom
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "✏️",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column {
                                Text(
                                    "Własna persona",
                                    fontWeight = if (selectedPersonaId == "custom")
                                        androidx.compose.ui.text.font.FontWeight.Bold
                                    else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                                Text(
                                    "Wpisz własny system prompt",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onPersonaSelected("custom")
                        expanded = false
                    }
                )
            }
        }

        // Pole custom (widoczne tylko gdy custom)
        if (selectedPersonaId == "custom") {
            Text(
                "Własny system prompt - instrukcja dla AI jak ma się zachowywać:",
                style = MaterialTheme.typography.labelMedium
            )
            OutlinedTextField(
                value = customPrompt,
                onValueChange = onCustomPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                placeholder = { Text("Np. Jesteś moim osobistym kucharzem. Odpowiadaj krótko, w punktach, z proporcjami na 2 osoby. Na końcu dodaj żart o jedzeniu.") },
                supportingText = { Text("Zostaw puste żeby użyć domyślnej persony") }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSection(
    voices: List<pl.victor.app.audio.VoiceInfo>,
    currentVoice: pl.victor.app.audio.VoiceInfo?,
    speechRate: Float,
    pitch: Float,
    onVoiceSelected: (String) -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onTestClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val audio = remember { pl.victor.app.VictorApplication.get().audio }

    val polishTotal = voices.count { it.isPolish }
    val polishOffline = voices.count { it.isPolish && it.isInstalledOffline }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Głos syntezy mowy", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onTestClick) {
                Text("🔊 Odsłuchaj")
            }
        }

        // === Karta statusu: ile głosów polskich jest dostępnych ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = when {
                    polishOffline > 0 -> MaterialTheme.colorScheme.primaryContainer
                    polishTotal > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    when {
                        polishOffline > 0 -> "✓ $polishOffline polskich głosów offline"
                        polishTotal > 0 -> "⚠ Polskie głosy wymagają pobrania"
                        else -> "✗ Brak polskich głosów"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    "Łącznie: ${voices.size} głosów w systemie",
                    style = MaterialTheme.typography.bodySmall
                )

                if (polishOffline == 0) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Polski głos offline jest potrzebny żeby działać bez internetu. " +
                                "Google TTS oferuje najlepszą jakość - kliknij poniżej:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { audio.openTtsSettings() }) {
                            Text("⚙ Ustawienia TTS")
                        }
                        TextButton(onClick = { audio.openGoogleTtsPlayStore() }) {
                            Text("📥 Google TTS")
                        }
                    }
                } else {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "💡 Więcej głosów (żeńskie, inne akcenty) → Ustawienia TTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (voices.isEmpty()) {
            Text(
                "Inicjalizuję TTS... Jeśli to nie zniknie, zrestartuj aplikację.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            // Dropdown z głosami
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = currentVoice?.displayName ?: "Wybierz głos",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Głos") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            voice.displayName,
                                            fontWeight = if (voice.name == currentVoice?.name)
                                                androidx.compose.ui.text.font.FontWeight.Bold
                                            else androidx.compose.ui.text.font.FontWeight.Normal
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            voice.statusLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                voice.isInstalledOffline && voice.isPolish ->
                                                    MaterialTheme.colorScheme.primary
                                                voice.requiresNetwork ->
                                                    MaterialTheme.colorScheme.error
                                                else ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            "${voice.quality} · ${voice.locale}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onVoiceSelected(voice.name)
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Slider: prędkość
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Prędkość mówienia", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        String.format("%.1fx", speechRate),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Slider(
                    value = speechRate,
                    onValueChange = onRateChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,  // 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Slider: wysokość
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wysokość głosu", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        String.format("%.1fx", pitch),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Slider(
                    value = pitch,
                    onValueChange = onPitchChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                "Domyślne wartości: prędkość 1.0x, wysokość 1.0x. " +
                        "Polskie głosy Google TTS są najlepsze dla naszego języka.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProactiveAlertsSection() {
    val context = LocalContext.current
    val app = pl.victor.app.VictorApplication.get()
    var enabled by remember { mutableStateOf(app.settings.isProactiveAlertsEnabled()) }
    var owmKey by remember { mutableStateOf(app.settings.getOpenWeatherApiKey()) }
    var location by remember { mutableStateOf(app.settings.getWeatherLocation()) }
    var hasCalendarPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCalendarPermission = results[android.Manifest.permission.READ_CALENDAR] == true
        hasNotificationPermission = results[android.Manifest.permission.POST_NOTIFICATIONS] == true
    }

    LaunchedEffect(Unit) {
        hasCalendarPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Proaktywne alerty (pogoda + kalendarz)", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (enabled)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🌦️ Alerty przed wyjściem",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            app.settings.setProactiveAlertsEnabled(it)
                            if (it) {
                                pl.victor.app.proactive.ProactiveAlertsScheduler.enable(
                                    context,
                                    app.settings.getProactiveIntervalMinutes()
                                )
                            } else {
                                pl.victor.app.proactive.ProactiveAlertsScheduler.disable(context)
                            }
                        }
                    )
                }

                Spacer(Modifier.size(4.dp))
                Text(
                    "V.I.C.T.O.R. sprawdza kalendarz i pogodę co 15 min. " +
                            "Jak masz spotkanie za 30-60 min i ma padać - dostaniesz " +
                            "powiadomienie: \"Weź parasol\".",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (enabled) {
            // Uprawnienia
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Uprawnienia:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    PermissionRow(
                        "📅 READ_CALENDAR",
                        "Czytanie Twojego kalendarza",
                        hasCalendarPermission
                    )
                    PermissionRow(
                        "🔔 POST_NOTIFICATIONS",
                        "Wysyłanie powiadomień",
                        hasNotificationPermission
                    )

                    if (!hasCalendarPermission || !hasNotificationPermission) {
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(arrayOf(
                                    android.Manifest.permission.READ_CALENDAR,
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                ))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔓 Poproś o uprawnienia")
                        }
                    }
                }
            }

            // OpenWeatherMap key
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "OpenWeatherMap API Key",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Darmowy po rejestracji na https://openweathermap.org/api. " +
                                "Wklej API key - służy do pobierania prognozy pogody.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = owmKey,
                        onValueChange = {
                            owmKey = it
                            app.settings.setOpenWeatherApiKey(it)
                        },
                        label = { Text("API Key") },
                        placeholder = { Text("np. a1b2c3d4e5f6...") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Lokalizacja
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Lokalizacja",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Nazwa miasta lub współrzędne. Np. \"Warszawa,PL\", " +
                                "\"Kraków\", \"52.23,21.01\".",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = location,
                        onValueChange = {
                            location = it
                            app.settings.setWeatherLocation(it)
                        },
                        label = { Text("Miasto / współrzędne") },
                        placeholder = { Text("Warszawa,PL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Przykładowe alerty
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "💡 Przykłady alertów które dostaniesz:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("☂️ \"Będzie padać za 20 min, weź parasol\"", style = MaterialTheme.typography.bodySmall)
                    Text("⛈️ \"Ulewa za 10 min - weź taksówkę\"", style = MaterialTheme.typography.bodySmall)
                    Text("❄️ \"Śnieg, ubierz się ciepło\"", style = MaterialTheme.typography.bodySmall)
                    Text("💨 \"Silny wiatr, uważaj na parasol\"", style = MaterialTheme.typography.bodySmall)
                    Text("🥶 \"Mróz - czapka i rękawiczki obowiązkowe\"", style = MaterialTheme.typography.bodySmall)
                    Text("⏰ \"Spóźnisz się, wychodź natychmiast!\"", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Test - ręczne uruchomienie workera
            Spacer(Modifier.size(8.dp))
            TextButton(
                onClick = {
                    // Wymusza natychmiastowe sprawdzenie (test)
                    val request = androidx.work.OneTimeWorkRequestBuilder<pl.victor.app.proactive.ProactiveAlertsWorker>().build()
                    androidx.work.WorkManager.getInstance(context).enqueue(request)
                    android.widget.Toast.makeText(
                        context,
                        "Testuję alerty - sprawdź za chwilę",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🧪 Testuj teraz")
            }
        }
    }
}

@Composable
private fun ActionsSection() {
    val context = LocalContext.current
    val audio = remember { pl.victor.app.VictorApplication.get().audio }
    val executor = remember { pl.victor.app.actions.ActionExecutor(context) }
    val detector = remember { pl.victor.app.actions.SmartActionDetector() }
    var installedApps by remember { mutableStateOf<List<pl.victor.app.actions.AppInfo>>(emptyList()) }
    val directExecutor = remember { pl.victor.app.actions.DirectActionExecutor(context) }
    var hasSmsPermission by remember { mutableStateOf(false) }
    var hasCallPermission by remember { mutableStateOf(false) }
    var hasContactsPermission by remember { mutableStateOf(false) }
    var actionMode by remember {
        mutableStateOf(pl.victor.app.VictorApplication.get().settings.getActionMode())
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasSmsPermission = results[android.Manifest.permission.SEND_SMS] == true
        hasCallPermission = results[android.Manifest.permission.CALL_PHONE] == true
        hasContactsPermission = results[android.Manifest.permission.READ_CONTACTS] == true
    }

    LaunchedEffect(Unit) {
        installedApps = executor.getInstalledApps()
        hasSmsPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasContactsPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Akcje (komendy głosowe)", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "🎯 V.I.C.T.O.R. może wykonywać akcje w Twoim imieniu",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Powiedz np.: „Wyślij SMS do Ani: cześć\", „Zadzwoń do mamy\", " +
                            "„Włącz muzykę Queen”, „Nawiguj do domu”, „Ustaw alarm na 7 rano”.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Bezpieczeństwo: Apka NIGDY nie robi nic bezpośrednio - otwiera " +
                            "odpowiednią apkę (Spotify, Dialer, Gmail...) i user potwierdza.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // === Tryb akcji: SAFE vs DIRECT ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (actionMode == "DIRECT")
                    MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Tryb wykonywania akcji",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = actionMode == "SAFE",
                        onClick = {
                            actionMode = "SAFE"
                            pl.victor.app.VictorApplication.get().settings.setActionMode("SAFE")
                        }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column {
                        Text("🔒 Bezpieczny (Intent)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            "Otwiera SMS/Dialer/Gmail. Bez uprawnień.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = actionMode == "DIRECT",
                        onClick = {
                            actionMode = "DIRECT"
                            pl.victor.app.VictorApplication.get().settings.setActionMode("DIRECT")
                        }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column {
                        Text("⚡ Szybki (bezpośredni)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            "Wysyła SMS / dzwoni po potwierdzeniu. Wymaga uprawnień.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Uprawnienia dla DIRECT
                if (actionMode == "DIRECT") {
                    Spacer(Modifier.size(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.size(8.dp))

                    Text(
                        "Uprawnienia dla trybu szybkiego:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))

                    PermissionRow(
                        "📱 SEND_SMS",
                        "Wysyłanie SMS bezpośrednio",
                        hasSmsPermission
                    )
                    PermissionRow(
                        "📞 CALL_PHONE",
                        "Dzwonienie bezpośrednio",
                        hasCallPermission
                    )
                    PermissionRow(
                        "👥 READ_CONTACTS",
                        "Rozpoznawanie imion kontaktów",
                        hasContactsPermission
                    )

                    Spacer(Modifier.size(8.dp))

                    val allGranted = hasSmsPermission && hasCallPermission && hasContactsPermission
                    if (!allGranted) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    pl.victor.app.actions.DirectActionExecutor.REQUIRED_PERMISSIONS
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔓 Poproś o uprawnienia")
                        }
                    } else {
                        Text(
                            "✓ Wszystkie uprawnienia przyznane",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Po wykryciu akcji (np. \"zadzwoń do mamy\") pokaże się dialog: " +
                                "\"Czy zadzwonić?\". Wciśnij OK i dzwoni bezpośrednio.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Lista wspieranych akcji
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Wspierane akcje:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                ActionListItem("📱", "SMS", "Wyślij SMS do kontaktu")
                ActionListItem("📞", "Telefon", "Zadzwoń do kogoś")
                ActionListItem("📧", "Email", "Wyślij maila (Gmail/inny)")
                ActionListItem("🎵", "Muzyka", "Włącz muzykę w Spotify / YouTube")
                ActionListItem("⏯", "Odtwarzacz", "Pauza / następny / poprzedni")
                ActionListItem("🗺", "Nawigacja", "Jedź do X (Google Maps)")
                ActionListItem("⏰", "Alarm", "Ustaw alarm (Clock app)")
                ActionListItem("⏱", "Timer", "Odliczanie")
                ActionListItem("🔍", "Szukaj", "Google search")
                ActionListItem("🌐", "Tłumacz", "Przetłumacz tekst")
                ActionListItem("📲", "Otwórz apkę", "Spotify, YouTube, Gmail...")
                ActionListItem("💡", "Latarka", "Włącz/wyłącz")
            }
        }

        // Test akcji
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "🧪 Test - wpisz komendę:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(8.dp))
                var testCommand by remember { mutableStateOf("") }
                var testResult by remember { mutableStateOf<String?>(null) }

                OutlinedTextField(
                    value = testCommand,
                    onValueChange = { testCommand = it; testResult = null },
                    label = { Text("Np. \"włącz muzykę Queen\"") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val actions = detector.detect(testCommand)
                        testResult = if (actions.isEmpty()) {
                            "❌ Nie wykryto akcji w: \"$testCommand\""
                        } else {
                            "✓ Wykryto ${actions.size}: ${actions.joinToString { it.description }}"
                        }
                    }) {
                        Text("🔍 Sprawdź")
                    }
                    TextButton(
                        onClick = {
                            val actions = detector.detect(testCommand)
                            if (actions.isNotEmpty()) {
                                actions.forEach { action ->
                                    val result = executor.execute(action)
                                    testResult = "${action.description}\n${result}"
                                }
                            } else {
                                testResult = "❌ Nie wykryto akcji"
                            }
                        },
                        enabled = testCommand.isNotBlank()
                    ) {
                        Text("▶ Wykonaj")
                    }
                }
                testResult?.let { result ->
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Zainstalowane apki
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "📲 Zainstalowane popularne apki:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                if (installedApps.isEmpty()) {
                    Text("Ładowanie...", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        installedApps.forEach { app ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (app.installed) "✓" else "✗",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (app.installed)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    app.appName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (app.installed)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(name: String, description: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (granted) "✓" else "○",
            color = if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionListItem(emoji: String, name: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "$name - ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeWordSection(
    enabled: Boolean,
    selectedId: String,
    customPhrase: String,
    keywordPath: String = "",
    modelPath: String = "",
    picovoiceAccessKey: String = "",
    onEnabledChange: (Boolean) -> Unit,
    onWakeWordSelected: (String) -> Unit,
    onCustomPhraseChange: (String) -> Unit,
    onPicovoiceKeyChange: (String) -> Unit = {},
    onKeywordPathChange: (String) -> Unit = {},
    onModelPathChange: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val wakeWords = remember { pl.victor.app.data.WakeWordRegistry.all() }
    val current = wakeWords.find { it.id == selectedId } ?: pl.victor.app.data.WakeWordRegistry.default()
    val resolvedPhrase = if (selectedId == "custom" && customPhrase.isNotBlank()) customPhrase
        else if (selectedId == "custom") "Wpisz swoją"
        else current.phrase

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Komenda głosowa (v1.1)", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Aktywuj AI głosowo")
                Text(
                    if (picovoiceAccessKey.isBlank()) {
                        "Najpierw wklej klucz Picovoice poniżej - bez niego " +
                            "wykrywanie komendy nie ruszy."
                    } else {
                        "V.I.C.T.O.R. nasłuchuje komendy w tle. Zużywa baterię."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                // Przełącznik był zablokowany na sztywno komentarzem "gdy Picovoice
                // dodane" - biblioteka jest w projekcie od dawna, brakuje tylko
                // klucza dostępu, a ten wpisuje się w karcie poniżej.
                enabled = picovoiceAccessKey.isNotBlank()
            )
        }

        // Karta z aktualną komendą
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(current.emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "„${resolvedPhrase}" + if (selectedId != "custom") "”" else "”",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        current.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Dropdown
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = current.phrase.ifBlank { "Własna komenda" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Wybierz komendę") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { Text("▼", modifier = Modifier.padding(8.dp)) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // Podział przebiega tam, gdzie przebiega naprawdę: część fraz
                // Porcupine zna, reszta wymaga wytrenowanego modelu.
                Text(
                    "  Działają od razu (wymowa angielska)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
                wakeWords.filter { it.worksOutOfTheBox }.forEach { ww ->
                    WakeWordItem(ww, selectedId, onWakeWordSelected) { expanded = false }
                }
                HorizontalDivider()
                Text(
                    "  Wymaga własnego modelu .ppn",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
                wakeWords.filterNot { it.worksOutOfTheBox }.forEach { ww ->
                    WakeWordItem(ww, selectedId, onWakeWordSelected) { expanded = false }
                }
            }
        }

        // Pole custom (gdy wybrano custom)
        if (selectedId == "custom") {
            OutlinedTextField(
                value = customPhrase,
                onValueChange = onCustomPhraseChange,
                label = { Text("Twoja komenda") },
                placeholder = { Text("Np. Hej V.I.C.T.O.R., Panie Asystencie") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        "Sama fraza nie wystarczy - Porcupine potrzebuje pliku .ppn " +
                            "wytrenowanego na console.picovoice.ai. Dla frazy polskiej " +
                            "dodatkowo modelu .pv dla języka polskiego."
                    )
                }
            )

            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = keywordPath,
                onValueChange = onKeywordPathChange,
                label = { Text("Ścieżka do pliku .ppn") },
                placeholder = { Text("/sdcard/Download/hey-victor_pl.ppn") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = modelPath,
                onValueChange = onModelPathChange,
                label = { Text("Model .pv (tylko język inny niż angielski)") },
                placeholder = { Text("/sdcard/Download/porcupine_params_pl.pv") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // Info o domyślnej
        Text(
            "Domyślna: „${pl.victor.app.data.WakeWordRegistry.default().phrase}” - " +
                "wbudowana w Porcupine, działa bez dodatkowych plików. " +
                "Wymowa angielska: „dżarwis”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Picovoice access key (v1.1)
        if (picovoiceAccessKey.isBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🔑 Aby włączyć wake word, wpisz Picovoice AccessKey",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "1. Zarejestruj się na https://console.picovoice.ai/\n" +
                                "2. Utwórz projekt (darmowy tier: 3 keywords)\n" +
                                "3. Skopiuj AccessKey i wklej poniżej",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = picovoiceAccessKey,
                        onValueChange = onPicovoiceKeyChange,
                        label = { Text("Picovoice AccessKey") },
                        placeholder = { Text("np. /5jV.../kE=") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✓ Picovoice skonfigurowany",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onPicovoiceKeyChange("") }) {
                    Text("Zmień klucz")
                }
            }
        }
    }
}

@Composable
private fun WakeWordItem(
    ww: pl.victor.app.data.WakeWord,
    selectedId: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ww.emoji,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(
                        ww.phrase.ifBlank { "(własna)" },
                        fontWeight = if (ww.id == selectedId)
                            androidx.compose.ui.text.font.FontWeight.Bold
                        else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                    Text(
                        ww.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        onClick = {
            onSelected(ww.id)
            onDismiss()
        }
    )
}

// === Sekcja: Inteligentne funkcje (v1.2) ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntelligenceSection(
    onManageGoogleAccount: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }

    var conversationalOn by remember { mutableStateOf(settings.isConversationalModeEnabled()) }
    var longTermOn by remember { mutableStateOf(settings.isLongTermMemoryEnabled()) }
    var translationTarget by remember { mutableStateOf(settings.getTranslationTarget()) }
    var googleConnected by remember { mutableStateOf(settings.isGoogleAccountConnected()) }

    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "🧠 Inteligentne funkcje",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))

            // Tryb konwersacyjny
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("💬 Tryb konwersacyjny", fontWeight = FontWeight.Medium)
                    Text(
                        "Po odpowiedzi automatycznie słucha kolejnego pytania",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = conversationalOn,
                    onCheckedChange = { newVal ->
                        conversationalOn = newVal
                        settings.setConversationalModeEnabled(newVal)
                        pl.victor.app.VictorApplication.get()
                            .orchestrator
                            .let { orch ->
                                if (newVal) orch.enableConversationalMode()
                                else orch.disableConversationalMode()
                            }
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            // Pamięć długoterminowa
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🧠 Pamięć długoterminowa", fontWeight = FontWeight.Medium)
                    Text(
                        "Pamięta rozmowy i używa ich jako kontekst",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = longTermOn,
                    onCheckedChange = { newVal ->
                        longTermOn = newVal
                        settings.setLongTermMemoryEnabled(newVal)
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            // Tłumacz - wybór języka docelowego
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🌍 Tłumacz symultaniczny", fontWeight = FontWeight.Medium)
                    Text(
                        "Docelowy język: ${pl.victor.app.translation.SimultaneousTranslator.languageName(translationTarget)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Dropdown z językami
            var translationExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = translationExpanded,
                onExpandedChange = { translationExpanded = !translationExpanded }
            ) {
                OutlinedTextField(
                    value = pl.victor.app.translation.SimultaneousTranslator.languageName(translationTarget),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Język docelowy") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = translationExpanded,
                    onDismissRequest = { translationExpanded = false }
                ) {
                    pl.victor.app.translation.SimultaneousTranslator.SUPPORTED_LANGUAGES.forEach { (code, _) ->
                        DropdownMenuItem(
                            text = { Text(pl.victor.app.translation.SimultaneousTranslator.languageName(code)) },
                            onClick = {
                                translationTarget = code
                                settings.setTranslationTarget(code)
                                translationExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))

            // Konto Google - jedno logowanie, dostęp do Calendar i Gmaila naraz
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (googleConnected)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (googleConnected) "🔗 Konto Google: połączono" else "🔗 Konto Google: nie połączono",
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Jedno logowanie odblokowuje: 📅 Kalendarz (czyta i tworzy wydarzenia, " +
                            "\"dodaj spotkanie jutro o 10\") i 📧 Gmail (czyta i wysyła maile, " +
                            "\"wyślij maila do... o temacie...\").",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    Row {
                        if (googleConnected) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            pl.victor.app.google.GoogleAccountManager(context).signOut()
                                            settings.setGoogleAccountConnected(false)
                                            googleConnected = false
                                        } catch (e: Exception) { }
                                    }
                                }
                            ) { Text("Wyloguj") }
                        } else {
                            Button(onClick = onManageGoogleAccount) {
                                Text("🔑 Połącz konto Google")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessibilitySection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }

    var highContrast by remember { mutableStateOf(settings.isHighContrastEnabled()) }
    var largeText by remember { mutableStateOf(settings.isLargeTextEnabled()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "\u267F Dost\u0119pno\u015b\u0107",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wysoki kontrast", fontWeight = FontWeight.Medium)
                    Text(
                        "Czer\u0144 i biel zamiast kolor\u00f3w systemowych",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = highContrast,
                    onCheckedChange = { newVal ->
                        highContrast = newVal
                        settings.setHighContrastEnabled(newVal)
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Du\u017ce litery", fontWeight = FontWeight.Medium)
                    Text(
                        "Powi\u0119ksza tekst w ca\u0142ej aplikacji o 30%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = largeText,
                    onCheckedChange = { newVal ->
                        largeText = newVal
                        settings.setLargeTextEnabled(newVal)
                    }
                )
            }
            Spacer(Modifier.size(8.dp))

            Text(
                "Zmiany wida\u0107 po ponownym otwarciu ekranu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CaptureModeSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }
    var preferredMode by remember { mutableStateOf(pl.victor.app.ai.CaptureMode.valueOf(settings.getPreferredCaptureMode())) }
    var autoDegrade by remember { mutableStateOf(settings.isAutoDegradeCaptureEnabled()) }
    var providerCaps by remember { mutableStateOf<pl.victor.app.ai.ProviderCapabilities?>(null) }

    LaunchedEffect(Unit) {
        try {
            val app = context.applicationContext as pl.victor.app.VictorApplication
            val providerId = app.settings.getActiveProvider()
            providerCaps = pl.victor.app.ai.AIProviderFactory.getCapabilitiesFor(providerId)
        } catch (e: Exception) { }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📸 Tryb przechwytywania", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text("Jak okulary mają przechwytywać obraz?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))

            pl.victor.app.ai.CaptureMode.values().forEach { mode ->
                val caps = providerCaps
                val supported = caps?.supportsMode(mode) ?: true
                val isSelected = preferredMode == mode
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    RadioButton(selected = isSelected, onClick = { preferredMode = mode; settings.setPreferredCaptureMode(mode.name) }, enabled = supported)
                    Spacer(Modifier.size(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(4.dp))
                            Text(mode.displayName, fontWeight = FontWeight.Medium, color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!supported && caps != null) {
                            Text("❌ Provider nie obsługuje", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🔄 Auto-degradacja", fontWeight = FontWeight.Medium)
                    Text("Jeśli provider nie obsługuje, użyj prostszego (wideo → zdjęcia).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoDegrade, onCheckedChange = { autoDegrade = it; settings.setAutoDegradeCaptureEnabled(it) })
            }
        }
    }
}

@Composable
private fun PowerModeSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as pl.victor.app.VictorApplication).settings }
    val app = context.applicationContext as pl.victor.app.VictorApplication
    val scope = rememberCoroutineScope()

    val powerManager = remember { pl.victor.app.power.PowerManager(context, settings) }
    val currentMode by powerManager.currentMode.collectAsState()
    val batteryState by powerManager.batteryState.collectAsState()
    val autoMode by powerManager.autoModeEnabled.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔋", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Text("Zasilanie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.size(8.dp))

            // Aktualna bateria
            Row(verticalAlignment = Alignment.CenterVertically) {
                val charging = if (batteryState.charging) "⚡" else "🔋"
                Text("$charging ${batteryState.percent}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                if (batteryState.charging) {
                    Text("ładowanie", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            val remainHours = powerManager.estimateRemainingHours()
            if (remainHours.isFinite() && remainHours > 0) {
                Text(
                    "Pozostało: ${"%.1f".format(remainHours)}h w trybie ${currentMode.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.size(12.dp))

            // Tryby
            Text("Tryb zasilania:", fontWeight = FontWeight.Medium)
            pl.victor.app.power.PowerMode.values().forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = currentMode == mode,
                        onClick = { powerManager.setMode(mode) }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(4.dp))
                            Text(mode.displayName, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.size(4.dp))
                            Text("~${mode.batteryPerHourPercent}%/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.size(8.dp))

            // Auto-mode
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🤖 Tryb automatyczny", fontWeight = FontWeight.Medium)
                    Text("Sam dostosowuje tryb do stanu baterii (ECO przy <15%, NORMAL przy 20-50%)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoMode, onCheckedChange = { powerManager.setAutoMode(it) })
            }

            // Wskazówki oszczędzania
            Spacer(Modifier.size(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("💡 Co zużywa baterię:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("• Wake word (nasłuch mikrofonu w tle) - ~3%/h", style = MaterialTheme.typography.labelSmall)
                    Text("• AI inference - krótkie, intensywne (kilka%)", style = MaterialTheme.typography.labelSmall)
                    Text("• TTS mówienie - ~1-2%/h mówienia", style = MaterialTheme.typography.labelSmall)
                    Text("• HeyCyan wideo 1080p - ~5-10%/h", style = MaterialTheme.typography.labelSmall)
                    Text("• WorkManager proaktywne - minimalnie", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
