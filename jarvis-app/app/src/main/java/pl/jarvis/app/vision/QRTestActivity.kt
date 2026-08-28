package pl.jarvis.app.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jarvis.app.ui.theme.HeiCyanTheme

/**
 * Activity do testów skanera QR.
 *
 * Pozwala wybrać zdjęcie z galerii telefonu i sprawdzić czy ML Kit
 * poprawnie wykrywa kody QR/barcode.
 *
 * Użyteczne do weryfikacji że biblioteka ML Kit działa zanim
 * zaczniemy ją używać ze zdjęciami z okularów HeyCyan.
 */
class QRTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeiCyanTheme {
                QRTestScreen(onBack = { finish() })
            }
        }
    }
}

sealed class QRTestState {
    object Idle : QRTestState()
    data class Loading(val message: String) : QRTestState()
    data class Scanned(
        val codes: List<ScannedCode>,
        val imageWidth: Int,
        val imageHeight: Int
    ) : QRTestState()
    data class Error(val message: String) : QRTestState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRTestScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val qrScanner = remember { QRScanner() }

    var state by remember { mutableStateOf<QRTestState>(QRTestState.Idle) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Launcher do wyboru zdjęcia z galerii
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        state = QRTestState.Loading("Wczytuję obraz...")
        Log.d("QRTest", "Selected URI: $uri")

        // Wczytaj bitmap
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e("QRTest", "Failed to decode", e)
            null
        }

        if (bitmap == null) {
            state = QRTestState.Error("Nie udało się wczytać obrazu")
            return@rememberLauncherForActivityResult
        }

        selectedBitmap = bitmap
        state = QRTestState.Loading("Skanuję kody QR/barcode...")

        // Skanuj w tle (nie blokuje UI)
        // Uwaga: QRScanner.scan() jest async w prawdziwym użyciu,
        // ale scanSync() blokuje - używamy tego dla testu
        Thread {
            try {
                val codes = qrScanner.scanSync(bitmap)
                Log.d("QRTest", "Found ${codes.size} codes")
                androidx.compose.runtime.snapshotFlow { 0 }.let {}  // touch
                // Update UI
                (context as? Activity)?.runOnUiThread {
                    if (codes.isEmpty()) {
                        state = QRTestState.Error("Nie znaleziono kodów QR/barcode na zdjęciu.\n\nUpewnij się, że:\n• QR jest wyraźny\n• Nie jest rozmazany\n• Oświetlenie jest dobre")
                    } else {
                        state = QRTestState.Scanned(
                            codes = codes,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("QRTest", "Scan failed", e)
                (context as? Activity)?.runOnUiThread {
                    state = QRTestState.Error("Błąd skanowania: ${e.message}")
                }
            }
        }.start()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Test skanera QR") },
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
            // Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🧪 Test ML Kit Barcode Scanner",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Wybierz zdjęcie z galerii z kodem QR lub barcode. " +
                                "Sprawdzimy czy ML Kit go wykryje i jakie dane odczyta. " +
                                "Obsługuje: QR, EAN-13, EAN-8, Code-128, DataMatrix, PDF417, Aztec.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Przycisk wyboru
            Button(
                onClick = { imagePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Wybierz zdjęcie z galerii")
            }

            // Podgląd wybranego obrazu
            selectedBitmap?.let { bitmap ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "📷 Wybrane zdjęcie: ${bitmap.width}x${bitmap.height}px",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.size(4.dp))
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Wybrane zdjęcie",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            // Stan
            when (val s = state) {
                is QRTestState.Idle -> {
                    Text(
                        "Kliknij przycisk powyżej żeby zacząć test.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is QRTestState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(s.message)
                    }
                }
                is QRTestState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("✗ ${s.message}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                is QRTestState.Scanned -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "✓ Wykryto ${s.codes.size} kod(ów):",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        s.codes.forEachIndexed { index, code ->
                            CodeResultCard(index + 1, code)
                        }

                        // Porównanie z oczekiwaniami
                        if (s.codes.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Text(
                                    "✅ ML Kit działa poprawnie! To samo będzie robione ze zdjęciami z okularów HeyCyan.",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
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
private fun CodeResultCard(index: Int, code: ScannedCode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Kod #${index}: ${code.format}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(4.dp))
            Row {
                Text("Typ: ", style = MaterialTheme.typography.labelSmall)
                Text(
                    code.type,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(4.dp))
            Text("Treść:", style = MaterialTheme.typography.labelSmall)
            Text(
                code.rawValue,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            code.url?.let { url ->
                Spacer(Modifier.size(4.dp))
                Text("URL: $url", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
