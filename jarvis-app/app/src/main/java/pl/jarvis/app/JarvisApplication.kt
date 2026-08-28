package pl.jarvis.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch
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

    /** Zakres dla zadań startowych, które muszą być korutynami. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

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

        // Zapisuj nieobsłużone wyjątki do pliku - inaczej crash znika z procesem.
        pl.jarvis.app.utils.CrashReporter.install(this)

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
            val interval = settings.getProactiveIntervalMinutes()
            ProactiveAlertsScheduler.enable(this, interval)
            Log.i(TAG, "Proaktywne alerty włączone (co $interval min)")
        }

        // Przy starcie - sprawdź nowe modele u aktywnego providera
        // (async, nie blokuje UI)
        modelDiscovery.checkActive()

        // Jeśli wake word jest włączony - uruchom
        if (settings.isWakeWordEnabled()) {
            val accessKey = settings.getPicovoiceAccessKey()
            if (accessKey.isNotBlank()) {
                val keyword = resolvePicovoiceKeyword()
                // initialize() jest suspend (ładuje model Porcupine), a onCreate nie jest
                // korutyną - uruchamiamy w tle, żeby nie blokować startu aplikacji.
                appScope.launch {
                    wakeWordDetector.initialize(accessKey, keyword)
                    wakeWordDetector.startListening()
                    Log.i(TAG, "Wake word detector started (keyword: $keyword)")
                }
            }
        }

        Log.d(TAG, "JarvisApplication initialized (HeyCyan SDK + DB + Discovery ready)")
    }

    /**
     * Rozwiązuje wybraną komendę na keyword akceptowany przez Porcupine.
     *
     * Porcupine przyjmuje przez `setKeyword()` wyłącznie wbudowane, angielskie słowa.
     * Własna fraza (również polska) wymaga modelu `.ppn` wytrenowanego w konsoli
     * Picovoice - dopóki go nie ma, schodzimy na "jarvis" i mówimy o tym w logu,
     * zamiast udawać, że polska komenda działa.
     */
    private fun resolvePicovoiceKeyword(): String {
        val selected = settings.getSelectedWakeWord().trim().lowercase()
        if (selected.isEmpty()) return DEFAULT_KEYWORD

        val builtIn = WakeWordDetector.BUILT_IN_KEYWORDS
        // Dokładne trafienie w keyword wbudowany.
        builtIn.firstOrNull { it == selected }?.let { return it }
        // Fraza typu "hey siri proszę" - dopasuj po prefiksie.
        builtIn.firstOrNull { selected.startsWith(it) }?.let { return it }

        Log.w(
            TAG,
            "Komenda \"$selected\" nie jest wbudowanym keywordem Porcupine. " +
                "Używam \"$DEFAULT_KEYWORD\". Aby mówić własną frazą, wytrenuj model .ppn " +
                "w konsoli Picovoice i wgraj go do aplikacji."
        )
        return DEFAULT_KEYWORD
    }

    companion object {
        private const val TAG = "JarvisApp"

        /** Fallback gdy wybrana fraza nie jest wbudowanym keywordem Porcupine. */
        private const val DEFAULT_KEYWORD = "jarvis"

        private var instance: JarvisApplication? = null

        fun get(): JarvisApplication = instance
            ?: throw IllegalStateException("Application not initialized")
    }
}
