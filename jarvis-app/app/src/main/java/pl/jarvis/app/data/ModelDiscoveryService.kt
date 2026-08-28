package pl.jarvis.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.jarvis.app.ai.AIProviderFactory

/**
 * Serwis wykrywania nowych modeli - uruchamiany przy starcie aplikacji.
 *
 * Porównuje listę modeli od providera (przez RemoteModelValidator) z naszym
 * ModelRegistry. Jeśli provider ma model którego my nie znamy → traktujemy
 * jako "nowy model do sprawdzenia".
 *
 * Wynik dostępny jako StateFlow - UI może go obserwować i pokazać notyfikację.
 */
class ModelDiscoveryService(
    private val settings: SettingsRepository
) {
    private val tag = "ModelDiscovery"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Nowe modele które provider ma, ale my nie znamy
    private val _newModels = MutableStateFlow<List<NewModelInfo>>(emptyList())
    val newModels: StateFlow<List<NewModelInfo>> = _newModels.asStateFlow()

    // Status ostatniego sprawdzenia
    private val _lastCheck = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    val lastCheck: StateFlow<DiscoveryStatus> = _lastCheck.asStateFlow()

    /**
     * Uruchamia sprawdzenie dla wszystkich providerów z kluczami API.
     * Bezpieczne do wywołania wielokrotnie - wynik jest cache'owany.
     */
    fun checkAll() {
        scope.launch {
            _lastCheck.value = DiscoveryStatus.Running

            val newModelsFound = mutableListOf<NewModelInfo>()

            // Sprawdź każdy provider
            val providers = AIProviderFactory.supportedProviders()
            providers.forEach { provider ->
                val apiKey = settings.getApiKey(provider.id)
                if (apiKey.isNullOrBlank()) return@forEach

                try {
                    val validator = RemoteModelValidator(apiKey, provider.id)
                    val remoteIds = validator.fetchAvailableModels()

                    if (remoteIds.isNotEmpty()) {
                        // Sprawdź czy są modele których nie znamy
                        val unknown = remoteIds.filter { !ModelRegistry.isKnown(it) }
                        if (unknown.isNotEmpty()) {
                            Log.i(tag, "Found ${unknown.size} new models for ${provider.id}: $unknown")
                            unknown.forEach { modelId ->
                                newModelsFound.add(
                                    NewModelInfo(
                                        modelId = modelId,
                                        providerId = provider.id,
                                        providerName = provider.displayName
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to check ${provider.id}: ${e.message}")
                }
            }

            _newModels.value = newModelsFound
            _lastCheck.value = if (newModelsFound.isEmpty()) {
                DiscoveryStatus.UpToDate
            } else {
                DiscoveryStatus.NewModelsFound(newModelsFound.size)
            }
            Log.i(tag, "Discovery complete: ${newModelsFound.size} new models")
        }
    }

    /**
     * Sprawdza tylko aktywny provider (szybciej niż wszystkie).
     * Wywoływane np. przy starcie apki.
     */
    fun checkActive() {
        val activeProvider = settings.getActiveProvider()
        val apiKey = settings.getApiKey(activeProvider)
        if (apiKey.isNullOrBlank()) return

        scope.launch {
            try {
                val validator = RemoteModelValidator(apiKey, activeProvider)
                val remoteIds = validator.fetchAvailableModels()

                val unknown = remoteIds.filter { !ModelRegistry.isKnown(it) }
                _newModels.value = unknown.map { modelId ->
                    NewModelInfo(
                        modelId = modelId,
                        providerId = activeProvider,
                        providerName = AIProviderFactory.supportedProviders()
                            .find { it.id == activeProvider }?.displayName ?: activeProvider
                    )
                }
                _lastCheck.value = if (unknown.isEmpty()) {
                    DiscoveryStatus.UpToDate
                } else {
                    DiscoveryStatus.NewModelsFound(unknown.size)
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to check active provider: ${e.message}")
                _lastCheck.value = DiscoveryStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Czyści listę nowych modeli (po wyświetleniu userowi).
     */
    fun clearNewModels() {
        _newModels.value = emptyList()
    }

    /**
     * Dodaje nowy model do lokalnego katalogu (runtime only).
     * Użyteczne gdy user akceptuje nowy model - apka go "uczy się".
     *
     * UWAGA: To NIE modyfikuje ModelRegistry na dysku. Tylko w pamięci.
     * Docelowo: przy akceptacji przez usera, model powinien być dodany
     * do nowej wersji ModelRegistry w kolejnej aktualizacji apki.
     */
    fun acceptNewModel(modelInfo: NewModelInfo) {
        // TODO: przyszłość - persystencja w Room
        Log.i(tag, "User accepted new model: ${modelInfo.modelId}")
        _newModels.value = _newModels.value.filter { it.modelId != modelInfo.modelId }
    }
}

/**
 * Informacja o nowym modelu wykrytym u providera.
 */
data class NewModelInfo(
    val modelId: String,
    val providerId: String,
    val providerName: String
)

/**
 * Status ostatniego sprawdzenia.
 */
sealed class DiscoveryStatus {
    object Idle : DiscoveryStatus()
    object Running : DiscoveryStatus()
    object UpToDate : DiscoveryStatus()
    data class NewModelsFound(val count: Int) : DiscoveryStatus()
    data class Error(val message: String) : DiscoveryStatus()
}
