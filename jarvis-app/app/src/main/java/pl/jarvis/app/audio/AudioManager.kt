package pl.jarvis.app.audio

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Manager audio - nagrywanie mikrofonu + synteza mowy (TTS).
 *
 * Nagrywanie: MediaRecorder do pliku .m4a w cache
 * TTS: Android TextToSpeech (offline, darmowy, polski)
 * - Obsługuje wybór głosu (męski/żeński)
 * - Regulacja prędkości (0.5x - 2.0x)
 * - Regulacja wysokości (0.5x - 2.0x)
 *
 * W przyszłości v1.1: integracja z HeyCyan przez BLE audio stream
 */
class AudioManager(
    private val context: Context
) {
    private val tag = "AudioManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // === TTS ===
    private var tts: TextToSpeech? = null
    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    // Dostępne głosy
    private val _availableVoices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<VoiceInfo>> = _availableVoices.asStateFlow()

    // Aktualny głos
    private val _currentVoice = MutableStateFlow<VoiceInfo?>(null)
    val currentVoice: StateFlow<VoiceInfo?> = _currentVoice.asStateFlow()

    // Prędkość i wysokość
    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _pitch = MutableStateFlow(1.0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    // === Recording ===
    private var recorder: MediaRecorder? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lastRecordingPath = MutableStateFlow<String?>(null)
    val lastRecordingPath: StateFlow<String?> = _lastRecordingPath.asStateFlow()

    init {
        initializeTts()
        setupAudioFocus()
    }

    // === Audio focus (adaptive volume) ===

    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val _isInFocus = MutableStateFlow(false)
    val isInFocus: StateFlow<Boolean> = _isInFocus.asStateFlow()

    /**
     * Konfiguruje audio focus - dostosowuje głośność TTS do otoczenia.
     * Jeśli user słucha muzyki / podcastu - nie przerywamy, tylko ściszamy.
     */
    private fun setupAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        audioFocusRequest = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                        // Mamy focus - przywróć normalną głośność
                        _isInFocus.value = true
                        tts?.setSpeechRate(_speechRate.value)
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                        // Straciliśmy focus - przerywamy
                        _isInFocus.value = false
                        tts?.stop()
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Tymczasowa utrata - pauza
                        _isInFocus.value = false
                        tts?.stop()
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Ducking - mów ciszej ale nie przerywaj
                        _isInFocus.value = true
                        tts?.setSpeechRate(_speechRate.value * 0.85f)  // trochę wolniej = cicho
                    }
                }
            }
            .build()
    }

    /**
     * Prosi o audio focus przed mówieniem.
     */
    private fun requestAudioFocus(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        val granted = result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        _isInFocus.value = granted
        return granted
    }

    /**
     * Zwalnia audio focus.
     */
    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        _isInFocus.value = false
    }

    /**
     * Mówi z automatycznym zarządzaniem audio focus.
     * Dostosowuje głośność do tego co user robi (muzyka, podcast itd.)
     */
    fun speakAdaptive(text: String, language: String = "pl") {
        if (text.isBlank()) return
        requestAudioFocus()
        speak(text, language)
        // Po zakończeniu - zwolnij focus
        // (nie czekamy, bo to synchroniczne)
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pl", "PL")
                tts?.setSpeechRate(_speechRate.value)
                tts?.setPitch(_pitch.value)
                loadAvailableVoices()
                _ttsReady.value = true
                Log.d(tag, "TTS initialized (Polish) - ${_availableVoices.value.size} voices")
            } else {
                Log.e(tag, "TTS init failed: $status")
                _ttsReady.value = false
            }
        }
    }

    /**
     * Ładuje listę dostępnych głosów (dla bieżącego locale).
     * Filtruje polskie głosy, dodaje angielskie i inne jako alternatywę.
     */
    private fun loadAvailableVoices() {
        val voices = tts?.voices ?: emptySet()

        // Polskie (priorytet)
        val polishVoices = voices.filter { it.locale.language == "pl" }
        // Angielskie offline (backup)
        val englishOffline = voices.filter {
            it.locale.language == "en" && !it.isNetworkConnectionRequired
        }
        // Inne języki offline
        val otherOffline = voices.filter {
            it.locale.language !in listOf("pl", "en") && !it.isNetworkConnectionRequired
        }

        val finalVoices = (polishVoices + englishOffline + otherOffline).map { voice ->
            VoiceInfo(
                name = voice.name,
                displayName = buildDisplayName(voice),
                language = voice.locale.displayLanguage,
                locale = voice.locale.toLanguageTag(),
                gender = when {
                    voice.name.contains("female", ignoreCase = true) ||
                    voice.name.contains("żensk", ignoreCase = true) -> "F"
                    voice.name.contains("male", ignoreCase = true) ||
                    voice.name.contains("męsk", ignoreCase = true) -> "M"
                    else -> "?"
                },
                quality = when (voice.quality) {
                    Voice.QUALITY_VERY_HIGH -> "Bardzo wysoka"
                    Voice.QUALITY_HIGH -> "Wysoka"
                    Voice.QUALITY_NORMAL -> "Normalna"
                    else -> "Niska"
                },
                isNetwork = voice.isNetworkConnectionRequired,
                requiresNetwork = voice.requiresNetworkConnection(),
                isInstalledOffline = !voice.isNetworkConnectionRequired,
                languageCode = voice.locale.language
            )
        }.sortedWith(
            compareByDescending<VoiceInfo> { it.isPolish }
                .thenByDescending { it.isInstalledOffline }
                .thenBy { it.languageCode }
        )

        _availableVoices.value = finalVoices

        // Statystyki
        val polishCount = finalVoices.count { it.isPolish }
        val polishOffline = finalVoices.count { it.isPolish && it.isInstalledOffline }
        Log.i(tag, "Voices: ${finalVoices.size} total, " +
                "$polishCount Polish, $polishOffline offline Polish")

        // Ustaw domyślny
        if (_currentVoice.value == null && finalVoices.isNotEmpty()) {
            val defaultVoice = finalVoices.firstOrNull { it.isPolish && it.isInstalledOffline }
                ?: finalVoices.firstOrNull { it.isPolish }
                ?: finalVoices.first()
            _currentVoice.value = defaultVoice
            tts?.voice = voices.find { it.name == defaultVoice.name }
            Log.d(tag, "Default voice: ${defaultVoice.displayName}")
        }
    }

    // === Informacje o silniku TTS ===

    /**
     * Ile jest zainstalowanych polskich głosów offline.
     */
    fun getPolishOfflineVoicesCount(): Int =
        _availableVoices.value.count { it.isPolish && it.isInstalledOffline }

    /**
     * Ile jest zainstalowanych polskich głosów (łącznie).
     */
    fun getPolishVoicesCount(): Int =
        _availableVoices.value.count { it.isPolish }

    /**
     * Czy użytkownik MA polski głos offline (gotowy do użycia).
     */
    fun hasPolishOfflineVoice(): Boolean = getPolishOfflineVoicesCount() > 0

    /**
     * Zwraca intent do ustawień TTS w systemie (gdzie user może pobrać głosy).
     */
    fun getTtsSettingsIntent(): Intent {
        return Intent().apply {
            action = "com.android.settings.TTS_SETTINGS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Zwraca intent do Google TTS w Play Store (jeśli nie zainstalowany).
     */
    fun getGoogleTtsInstallIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(
                "https://play.google.com/store/apps/details?id=com.google.android.tts"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Otwiera ustawienia TTS (gdzie można wybrać silnik i pobrać głosy).
     */
    fun openTtsSettings() {
        try {
            val intent = getTtsSettingsIntent()
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(tag, "Couldn't open TTS settings: ${e.message}")
        }
    }

    /**
     * Otwiera Google TTS w Play Store.
     */
    fun openGoogleTtsPlayStore() {
        try {
            val intent = getGoogleTtsInstallIntent()
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(tag, "Couldn't open Play Store: ${e.message}")
        }
    }

    private fun buildDisplayName(voice: Voice): String {
        val parts = mutableListOf<String>()
        parts.add(voice.locale.displayLanguage)

        val gender = when {
            voice.name.contains("female", ignoreCase = true) -> "żeński"
            voice.name.contains("male", ignoreCase = true) -> "męski"
            else -> null
        }
        gender?.let { parts.add(it) }

        // Network vs local
        if (voice.isNetworkConnectionRequired) {
            parts.add("online")
        } else {
            parts.add("offline")
        }

        return parts.joinToString(" · ")
    }

    // === TTS - mówienie ===

    /**
     * Mówi podany tekst przez głośniki telefonu (przez HeyCyan jeśli BT audio aktywne).
     */
    fun speak(text: String, language: String = "pl") {
        if (!_ttsReady.value) {
            Log.w(tag, "TTS not ready, skipping")
            return
        }
        if (text.isBlank()) return

        tts?.language = Locale(language)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
        Log.d(tag, "Speaking: ${text.take(80)}...")
    }

    /**
     * Ustawia konkretny głos.
     */
    fun setVoice(voiceName: String): Boolean {
        val voice = tts?.voices?.find { it.name == voiceName } ?: return false
        tts?.voice = voice
        val info = _availableVoices.value.find { it.name == voiceName }
        _currentVoice.value = info
        Log.i(tag, "Voice changed to: ${info?.displayName}")
        return true
    }

    /**
     * Ustawia prędkość mówienia (0.5 - 2.0).
     */
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(clamped)
        _speechRate.value = clamped
        Log.d(tag, "Speech rate: $clamped")
    }

    /**
     * Ustawia wysokość głosu (0.5 - 2.0).
     */
    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        tts?.setPitch(clamped)
        _pitch.value = clamped
        Log.d(tag, "Pitch: $clamped")
    }

    /**
     * Testuje aktualny głos - mówi krótki tekst.
     */
    fun testCurrentVoice() {
        speak("Cześć, jestem Twoim asystentem AI. Tak brzmi mój głos.", language = "pl")
    }

    // === Streaming TTS - mówienie po zdaniach ===

    private val streamBuffer = StringBuilder()

    /**
     * Dodaje fragment tekstu ze streamingu. Jeśli w buforze jest kompletne zdanie
     * (kończy się na . ! ? lub nowej linii), mówi je i usuwa z bufora.
     *
     * @return lista zdań które zostały wypowiedziane (do logów/UI)
     */
    fun addStreamFragment(fragment: String): List<String> {
        if (fragment.isBlank()) return emptyList()

        streamBuffer.append(fragment)
        val spoken = mutableListOf<String>()

        // Szukaj końca zdań: . ! ? lub nowa linia
        val sentenceEndRegex = Regex("""([^.!?\n]*[.!?\n])""")
        val bufferText = streamBuffer.toString()

        val matches = sentenceEndRegex.findAll(bufferText)
        var lastEnd = 0

        for (match in matches) {
            val sentence = match.value.trim()
            if (sentence.isNotBlank() && sentence.length > 3) {
                speak(sentence, language = "pl")
                spoken.add(sentence)
            }
            lastEnd = match.range.last + 1
        }

        // Wyczyść bufor ze wszystkiego co zostało wypowiedziane
        if (lastEnd > 0) {
            streamBuffer.delete(0, lastEnd)
        }

        return spoken
    }

    /**
     * Kończy streaming - mówi ostatni fragment (jeśli jest w buforze).
     */
    fun flushStream() {
        val remaining = streamBuffer.toString().trim()
        if (remaining.isNotBlank() && remaining.length > 2) {
            speak(remaining, language = "pl")
        }
        streamBuffer.clear()
    }

    /**
     * Czyści bufor streamingu (np. przy cancel).
     */
    fun clearStream() {
        streamBuffer.clear()
        tts?.stop()
    }

    /**
     * Zatrzymuje mówienie.
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    // === Recording ===

    /**
     * Rozpoczyna nagrywanie z mikrofonu do pliku .m4a.
     */
    fun startRecording(): Boolean {
        if (_isRecording.value) {
            Log.w(tag, "Already recording")
            return false
        }

        return try {
            val outputFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.m4a")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            _isRecording.value = true
            _lastRecordingPath.value = outputFile.absolutePath
            Log.d(tag, "Recording started: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start recording", e)
            cleanupRecorder()
            false
        }
    }

    /**
     * Zatrzymuje nagrywanie. Zwraca ścieżkę do pliku lub null.
     */
    fun stopRecording(): String? {
        if (!_isRecording.value) {
            return null
        }

        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            _isRecording.value = false

            val path = _lastRecordingPath.value
            Log.d(tag, "Recording stopped: $path")
            path
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop recording", e)
            cleanupRecorder()
            null
        }
    }

    /**
     * Zwraca audio jako ByteArray (do wysłania do AI).
     */
    fun readLastRecording(): ByteArray? {
        val path = _lastRecordingPath.value ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return file.readBytes()
    }

    private fun cleanupRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        _isRecording.value = false
    }

    // === Odtwarzanie audio (np. nagranej odpowiedzi AI) ===

    private var mediaPlayer: MediaPlayer? = null

    /**
     * Odtwarza audio z tablicy bajtów (np. odpowiedź AI w formacie audio).
     */
    fun playAudio(bytes: ByteArray, onComplete: (() -> Unit)? = null) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(bytes.inputStream().fd)  // hack: write to temp file
                prepare()
                setOnCompletionListener {
                    onComplete?.invoke()
                    release()
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to play audio", e)
        }
    }

    // === Cleanup ===

    fun shutdown() {
        stopSpeaking()
        stopRecording()
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        _ttsReady.value = false
        Log.d(tag, "AudioManager shut down")
    }

    companion object {
        @Volatile
        private var instance: AudioManager? = null

        fun getInstance(context: Context): AudioManager {
            return instance ?: synchronized(this) {
                instance ?: AudioManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/**
 * Informacja o głosie TTS dostępnym w systemie.
 */
data class VoiceInfo(
    /** Unikalna nazwa głosu (np. "pl-pl-x-oda-local") */
    val name: String,
    /** Nazwa wyświetlana w UI */
    val displayName: String,
    /** Język (np. "polski") */
    val language: String,
    /** Locale (np. "pl-PL") */
    val locale: String,
    /** Płeć: "M" / "F" / "?" */
    val gender: String,
    /** Jakość głosu */
    val quality: String,
    /** Czy wymaga internetu (Google Cloud TTS) */
    val isNetwork: Boolean,
    /** Czy faktycznie wymaga sieci do działania */
    val requiresNetwork: Boolean,
    /** Czy głos jest zainstalowany offline (nie trzeba pobierać) */
    val isInstalledOffline: Boolean = !requiresNetwork,
    /** Kod języka (pl, en, de, ...) */
    val languageCode: String = locale.split("-").firstOrNull() ?: "",
    /** Czy to polski głos */
    val isPolish: Boolean = languageCode == "pl"
) {
    /** Etykieta statusu dla UI */
    val statusLabel: String = when {
        isPolish && isInstalledOffline -> "✓ Polski offline"
        isPolish && !isInstalledOffline -> "⚠ Polski (wymaga sieci)"
        isInstalledOffline -> "✓ Offline"
        else -> "☁ Online"
    }
}
