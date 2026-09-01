package pl.victor.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch
import pl.victor.app.audio.AudioManager
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.VictorForegroundService
import pl.victor.app.ble.VictorManager
import pl.victor.app.data.AppDatabase
import pl.victor.app.data.ModelDiscoveryService
import pl.victor.app.data.SettingsRepository
import pl.victor.app.proactive.ProactiveAlertsScheduler
import pl.victor.app.storage.PhotoStorage
import pl.victor.app.wakeword.WakeWordDetector

/**
 * Application class - inicjalizuje globalne zależności.
 */
class VictorApplication : Application() {

    /** Zakres dla zadań startowych, które muszą być korutynami. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    lateinit var settings: SettingsRepository
        private set

    lateinit var glassesManager: VictorManager
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
        pl.victor.app.utils.CrashReporter.install(this)

        settings = SettingsRepository(this)
        glassesManager = VictorManager.getInstance(this).also { manager ->
            // Tryb symulacji trzeba ustawić PRZED initialize() - decyduje o tym,
            // czy w ogóle ruszamy vendor SDK.
            if (settings.isGlassesSimulationEnabled()) {
                manager.setSimulationEnabled(true)
            }
            manager.initialize()
        }
        database = AppDatabase.getInstance(this)
        modelDiscovery = ModelDiscoveryService(settings)
        wakeWordDetector = WakeWordDetector(this)
        photoStorage = PhotoStorage(this)
        audio = AudioManager.getInstance(this)
        orchestrator = AIOrchestrator(
            context = this,
            settings = settings,
            history = pl.victor.app.data.HistoryRepository(database.conversationDao()),
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
                // initialize() jest suspend (ładuje model Porcupine), a onCreate nie jest
                // korutyną - uruchamiamy w tle, żeby nie blokować startu aplikacji.
                appScope.launch {
                    val entry = settings.getSelectedWakeWordEntry()
                    val result = wakeWordDetector.initialize(
                        accessKey = accessKey,
                        keyword = entry.porcupineKeyword ?: settings.getSelectedWakeWord(),
                        keywordPath = settings.getCustomKeywordPath(),
                        modelPath = settings.getCustomModelPath()
                    )
                    if (result.isSuccess) {
                        wakeWordDetector.startListening()
                        Log.i(TAG, "Wake word detector started (fraza: ${entry.phrase.ifBlank { entry.id }})")
                    } else {
                        // Świadomie NIE podmieniamy na inną frazę po cichu - jeśli
                        // "Hey Victor" wymaga jeszcze własnego modelu, aplikacja ma
                        // milczeć, a nie nasłuchiwać czegoś, czego użytkownik nie wybrał.
                        Log.w(TAG, "Wake word start przy uruchomieniu nieudany: ${result.message()}")
                    }
                }
            }
        }

        // Usługa pierwszoplanowa musi żyć dokładnie wtedy, gdy okulary są połączone
        // i/lub wake word nasłuchuje - inaczej Doze zabija BLE i AudioRecord kilka
        // minut po zgaszeniu ekranu. Nasłuch obu StateFlow zamiast wywołań rozsianych
        // po ViewModelach - każde miejsce, które zmienia jeden z tych dwóch stanów
        // (ustawienia, onboarding, automatyczny PowerManager, sam BLE), trafia tu
        // automatycznie, więc nie da się o tym zapomnieć w nowym miejscu w kodzie.
        appScope.launch {
            glassesManager.connectionState.collect { refreshBackgroundService() }
        }
        appScope.launch {
            settings.wakeWordEnabledFlow.collect { refreshBackgroundService() }
        }

        Log.d(TAG, "VictorApplication initialized (HeyCyan SDK + DB + Discovery ready)")
    }

    private fun refreshBackgroundService() {
        val connectionState = glassesManager.connectionState.value
        val wakeWordOn = settings.wakeWordEnabledFlow.value
        val glassesActive = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.READY ||
            connectionState == ConnectionState.CONNECTING
        if (!glassesActive && !wakeWordOn) {
            VictorForegroundService.stop(this)
            return
        }
        // Prawdziwa aktywna fraza, nie nazwa apki - dopóki "Hey Victor" wymaga
        // własnego modelu, nasłuch faktycznie idzie na inne słowo (domyślnie
        // "Computer"), i powiadomienie ma o tym mówić prawdę.
        val phrase = settings.getSelectedWakeWordEntry().phrase.ifBlank { "wybraną frazę" }
        val reason = when {
            glassesActive && wakeWordOn -> "Połączony z okularami · nasłuchuję „$phrase”"
            glassesActive -> "Połączony z okularami"
            else -> "Nasłuchuję słowa „$phrase”"
        }
        VictorForegroundService.start(this, reason)
    }

    companion object {
        private const val TAG = "VictorApp"

        private var instance: VictorApplication? = null

        fun get(): VictorApplication = instance
            ?: throw IllegalStateException("Application not initialized")
    }
}
