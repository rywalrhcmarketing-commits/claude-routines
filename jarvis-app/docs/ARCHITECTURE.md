# Architektura techniczna – HeyCyan AI Glasses

> Wersja: 0.1.0-alpha  
> Data: 2026-08-28

## Przegląd

Aplikacja Android (Kotlin) działająca jako "mózg" między okularami HeyCyan (hardware) a zewnętrznym API AI (Gemini / OpenAI / Claude / MiniMax). Architektura oparta na **czystej separacji warstw** i **abstrakcji providera AI** dla łatwej wymienności.

## Stack technologiczny

| Warstwa | Technologia |
|---|---|
| Język | Kotlin 1.9+ |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| Build | Gradle 8.x + Kotlin DSL |
| UI | Jetpack Compose (Material 3) |
| Async | Coroutines + Flow |
| HTTP | OkHttp 4.x (lekki, niezawodny) |
| JSON | kotlinx.serialization |
| BLE | Android BluetoothLeScanner + BluetoothGatt |
| Database | Room 2.6+ |
| Secure storage | EncryptedSharedPreferences (androidx.security) |
| Camera | CameraX 1.3+ |
| Audio | Android MediaRecorder + MediaPlayer + TextToSpeech |

## Architektura wysokopoziomowa

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer (Compose)                      │
│  MainScreen │ SettingsScreen │ HistoryScreen │ CaptureFlow  │
└──────────────────────┬──────────────────────────────────────┘
                       │ ViewModel
┌──────────────────────┴──────────────────────────────────────┐
│                     Domain Layer                             │
│   GlassesController │ AIOrchestrator │ HistoryRepository    │
└────┬─────────────┬────────────────┬──────────────┬──────────┘
     │             │                │              │
┌────┴──────┐ ┌────┴──────┐  ┌──────┴────┐  ┌──────┴──────┐
│  BLE Mgr  │ │ Camera Mgr│  │  AI Mgr   │  │ Audio Mgr   │
│  (HeyCyan)│ │ (HeyCyan)│  │  (Provider│  │  (TTS +     │
│           │ │           │  │   API)    │  │  Capture)   │
└───────────┘ └───────────┘  └───────────┘  └─────────────┘
```

## Moduły

### 1. `ai/` – Abstrakcja providera AI

**Cel:** Jednolity interfejs do komunikacji z różnymi modelami AI.

```kotlin
// ai/AIProvider.kt
interface AIProvider {
    val id: String              // "gemini", "openai", "claude", "minimax"
    val displayName: String
    val requiresExternalAudio: Boolean
    val supportsWebSearch: Boolean

    suspend fun analyze(
        textQuestion: String,
        images: List<ByteArray>,         // JPEG/PNG
        audioBytes: ByteArray? = null,
        enableWebSearch: Boolean = false
    ): AIResponse
}

data class AIResponse(
    val text: String,
    val audioBase64: String? = null,  // null jeśli provider nie daje audio
    val sources: List<Source> = emptyList(),  // cytaty z web search
    val tokensUsed: Int = 0
)

data class Source(
    val title: String,
    val url: String,
    val snippet: String
)
```

**Implementacje:**
- `GeminiProvider` – multimodal in, audio out (TTS via Gemini)
- `OpenAIProvider` – multimodal in, text out (TTS via Android)
- `ClaudeProvider` – multimodal in, text out (TTS via Android)
- `MiniMaxProvider` – multimodal in, text out (TTS via Android)

### 2. `ble/` – Komunikacja z okularami

**Cel:** Obsługa protokołu BLE HeyCyan.

```kotlin
// ble/VictorManager.kt
class VictorManager(context: Context) {
    private val bluetoothAdapter: BluetoothAdapter
    private var gatt: BluetoothGatt? = null

    // Wymagane UUID (z dokumentacji FerSaiyan)
    companion object {
        val HEYCYAN_SERVICE_UUID_PRIMARY = UUID.fromString("7905FFF0-B5CE-4E99-A40F-4B1E122D00D0")
        val HEYCYAN_SERVICE_UUID_SECONDARY = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
    }

    suspend fun scanForDevices(): List<HeyCyanDevice>
    suspend fun connect(device: HeyCyanDevice): Boolean
    suspend fun takePhoto(): ByteArray?            // BLE shutter command
    suspend fun startAudioRecording(): Boolean    // BLE audio stream
    suspend fun stopAudioRecording(): ByteArray?
    suspend fun playAudio(bytes: ByteArray): Boolean // BLE audio out
    fun observeButtonEvents(): Flow<ButtonEvent>
    fun disconnect()

    sealed class ButtonEvent {
        object Pressed : ButtonEvent()
        object Released : ButtonEvent()
    }
}
```

**TODO:** Skopiować protokół z FerSaiyan po jego sforkowaniu.

### 3. `camera/` – Zarządzanie zdjęciami

**Cel:** Koordynacja 5 zdjęć co 2 sekundy.

```kotlin
// camera/BurstCaptureManager.kt
class BurstCaptureManager(private val glassesManager: VictorManager) {
    suspend fun captureBurst(
        count: Int = 5,
        intervalMs: Long = 2000,
        onProgress: (Int) -> Unit = {}
    ): List<ByteArray> {
        val photos = mutableListOf<ByteArray>()
        repeat(count) { index ->
            val photo = glassesManager.takePhoto()
            if (photo != null) photos.add(photo)
            onProgress(index + 1)
            if (index < count - 1) delay(intervalMs)
        }
        return photos
    }
}
```

### 4. `audio/` – Audio in/out

**Cel:** Nagrywanie pytania, synteza i odtwarzanie odpowiedzi.

```kotlin
// audio/AudioManager.kt
class AudioManager(private val context: Context) {
    private val tts: TextToSpeech
    private var recorder: MediaRecorder? = null

    fun startRecording(): Boolean
    fun stopRecording(): ByteArray?
    fun playText(text: String, language: String = "pl")
    fun playAudioBytes(bytes: ByteArray)
    fun shutdown()
}
```

**Strategia TTS:**
- **Domyślnie:** Android TextToSpeech (offline, darmowy, PL obsługiwany)
- **Opcjonalnie v1.1:** OpenAI TTS API (lepszy głos, ale płatny)

### 5. `ui/` – Interfejs użytkownika (Jetpack Compose)

**Główne ekrany:**
- `MainScreen` – domyślny ekran (capture button, text input, status)
- `SettingsScreen` – wybór provider-a, klucze API, język, opcje
- `HistoryScreen` – lista 20 ostatnich rozmów
- `CaptureOverlay` – overlay pokazywany podczas przechwytywania (progress 1/5, 2/5, ...)

### 6. `data/` – Persystencja

```kotlin
// data/ConversationEntry.kt
@Entity
data class ConversationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val photoPath: String?,           // path do pierwszego zdjęcia (cache)
    val userQuestion: String,
    val aiResponse: String,
    val provider: String              // który AI odpowiedział
)

// data/SettingsRepository.kt
class SettingsRepository(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(...)

    fun getApiKey(provider: String): String?
    fun setApiKey(provider: String, key: String)
    fun getActiveProvider(): String
    fun setActiveProvider(provider: String)
    fun isWakeWordEnabled(): Boolean
    fun setWakeWordEnabled(enabled: Boolean)
    fun getSelectedWakeWord(): String
    fun setSelectedWakeWord(word: String)
    fun getResponseLanguage(): String  // "pl" default
}
```

## Główny flow (sekwencja)

```
User wciska przycisk HeyCyan
  ↓ (BLE event)
VictorManager.onButtonPressed
  ↓
AIOrchestrator.handleTrigger(TriggerSource.BUTTON)
  ↓
  1. AudioManager.startRecording()
  2. BurstCaptureManager.captureBurst(5, 2000) → List<ByteArray>
  3. AudioManager.stopRecording() → ByteArray
  ↓ (po puszczeniu przycisku)
  4. Wyślij do AI: 5 zdjęć + audio pytania
  5. AI Provider: transcribe audio (jeśli trzeba) + analyze multimodal
  6. Otrzymaj odpowiedź (text lub audio)
  7. AudioManager.playText() lub playAudioBytes()
  8. Zapisz do historii: ConversationEntry
  ↓
User słyszy odpowiedź w głośnikach okularów
```

## Bezpieczeństwo

- **Klucze API:** `EncryptedSharedPreferences` z `MasterKey.KeyScheme.AES256_GCM`
- **Komunikacja:** HTTPS do API (cert pinning w v2)
- **Logi:** nigdy nie loguj kluczy, nigdy nie loguj treści audio (privacy)
- **Uprawnienia:** tylko niezbędne (Bluetooth, Camera, Microphone, Internet)

## Wydajność

- **5 zdjęć JPEG** ≈ 500KB każdy = 2.5MB na request
- **Audio 10s** ≈ 160KB (16kbps Opus)
- **Request do Gemini** ≈ 3MB upload
- **Latency target:** < 5s end-to-end (bez AI compute time)
- **AI compute time:** typowo 2-4s dla Gemini 2.5 Flash

## Testowanie

- **Unit testy:** ViewModel-e, AIProvider mock, parsery
- **Integration testy:** z mockowanym HeyCyanBLE
- **Manual testy:** z prawdziwymi okularami po dostawie

## Zależności zewnętrzne (do skopiowania z FerSaiyan)

Po sforkowaniu `FerSaiyan/Alternative-HeyCyan-App-and-SDK`:
- Kod BLE protokołu HeyCyan
- Klasa do dekodowania zdjęć z kamery
- Ewentualne helpery do audio streaming

## Roadmap technologiczny

| Wersja | Co |
|---|---|
| 0.1.0-alpha | Core flow (button → 5 zdjęć → AI → TTS) z Gemini |
| 0.2.0-alpha | + Text mode, historia, wskaźnik "AI myśli" |
| 0.3.0-alpha | + Multi-provider (OpenAI, Claude, MiniMax) |
| 1.0.0-stable | + Web search, QR scanning, polish UI |
| 1.1.0 | + Wake word (opcjonalny), persona selector |
| 2.0.0 | + Calendar, Notes (jeśli będzie potrzeba) |
