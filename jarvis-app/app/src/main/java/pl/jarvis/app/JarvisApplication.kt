package pl.jarvis.app

import android.app.Application
import android.util.Log
import pl.jarvis.app.audio.AudioManager
import pl.jarvis.app.ble.JarvisManager
import pl.jarvis.app.data.AppDatabase
import pl.jarvis.app.data.ModelDiscoveryService
import pl.jarvis.app.data.SettingsRepository
import pl.jarvis.app.proactive.ProactiveAlertsScheduler
import pl.jarvis.app.storage.PhotoStorage
import pl.jarvis.app.wakeword.WakeWordDetector

/**
 * Application class - inicjalizuje globalne zależności.
 */
class JarvisApplication : Application() {

    lateinit var settings: SettingsRepository
        private set

    lateinit var heyCyanManager: JarvisManager
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var modelDiscovery: ModelDiscoveryService
        private set

    lateinit var wakeWordDetector: WakeWordDetector
        private set

    lateinit var photoStorage: PhotoStorage

    lateinit var audio: AudioManager
        private set

    lateinit var orchestrator: AIOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        settings = SettingsRepository(this)
        heyCyanManager = JarvisManager.getInstance(this).also { it.initialize() }
        database = AppDatabase.getInstance(this)
        modelDiscovery = ModelDiscoveryService(settings)
        wakeWordDetector = WakeWordDetector(this)
        photoStorage = PhotoStorage(this)
        audio = AudioManager.getInstance(this)
        orchestrator = AIOrchestrator(
            context = this,
            settings = settings,
            history = pl.jarvis.app.data.HistoryRepository(database.conversationDao()),
            wakeWord = wakeWordDetector
        )

        // Cleanup starych zdjęć (>30 dni)
        photoStorage.cleanupOldPhotos(30)

        // Włącz proaktywne alerty (pogoda + kalendarz) co 15 min
        if (settings.isProactiveAlertsEnabled()) {
            ProactiveAlertsScheduler.enable(this)
            Log.i(TAG, "Proactive alerts enabled (every 15 min)")
        }

        // Przy starcie - sprawdź nowe modele u aktywnego providera
        // (async, nie blokuje UI)
        modelDiscovery.checkActive()

        // Jeśli wake word jest włączony - uruchom
        if (settings.isWakeWordEnabled()) {
            val accessKey = settings.getPicovoiceAccessKey()
            if (accessKey.isNotBlank()) {
                val keyword = resolvePicovoiceKeyword()
                wakeWordDetector.initialize(accessKey, keyword)
                wakeWordDetector.startListening()
                Log.i(TAG, "Wake word detector started (keyword: $keyword)")
            }
        }

        Log.d(TAG, "JarvisApplication initialized (HeyCyan SDK + DB + Discovery ready)")
    }

    /**
     * Mapuje nasze ID persony na wbudowane keyword Porcupine.
     * Dla polskiego lub custom - używamy "jarvis" (angielski fallback).
     * User może wytrenować custom w konsoli Picovoice.
     */
    private fun resolvePicovoiceKeyword(): String {
        return when (settings.getSelectedWakeWordId()) {
            "jarvis_start", "jarvis" -> "jarvis"
            "computer" -> "computer"
            "ok_glass" -> "jarvis"  // brak "ok glass" w Porcupine - fallback
            "alexa" -> "alexa"
            "hey_siri" -> "hey siri"
            "ok_google" -> "ok google"
            "neo" -> "jarvis"  // brak "neo" - fallback
            "glados" -> "jarvis"  // brak "glados" - fallback
            "hej_cyan" -> "jarvis"  // polskie nie ma w Porcupine - fallback
            "cześć", "witaj", "halo", "słuchaj", "asystencie", "custom" -> "jarvis"
            else -> "jarvis"
        }
    }

    companion object {
        private const val TAG = "HeyCyanApp"

        private var instance: JarvisApplication? = null

        fun get(): JarvisApplication = instance
            ?: throw IllegalStateException("Application not initialized")
    }
}
