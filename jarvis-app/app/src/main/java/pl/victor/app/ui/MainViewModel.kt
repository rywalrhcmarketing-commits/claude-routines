package pl.victor.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.victor.app.AIOrchestrator
import pl.victor.app.VictorApplication
import pl.victor.app.OrchestratorState
import pl.victor.app.PendingActionConfirmation
import pl.victor.app.TriggerSource
import pl.victor.app.data.HistoryRepository
import pl.victor.app.data.NewModelInfo

/**
 * ViewModel dla głównego ekranu.
 * Pośredniczy między UI a AIOrchestrator.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication
    private val orchestrator = app.orchestrator

    val state: StateFlow<OrchestratorState> = orchestrator.state
    val modelWarning: StateFlow<String?> = orchestrator.modelWarning
    val currentModelId: StateFlow<String?> = orchestrator.currentModelId
    val newModels: StateFlow<List<NewModelInfo>> = app.modelDiscovery.newModels
    val pendingActionConfirmation: StateFlow<PendingActionConfirmation?> =
        orchestrator.pendingActionConfirmation

    fun onCaptureButtonPressed() {
        orchestrator.handleUserTrigger(TriggerSource.BUTTON)
    }

    fun onTextSubmit(text: String) {
        if (text.isNotBlank()) {
            orchestrator.handleUserTrigger(TriggerSource.TEXT_INPUT, text)
        }
    }

    fun resetState() {
        orchestrator.reset()
    }

    fun clearModelWarning() {
        orchestrator.clearModelWarning()
    }

    fun clearNewModels() {
        app.modelDiscovery.clearNewModels()
    }

    fun refreshModels() {
        app.modelDiscovery.checkAll()
    }

    fun confirmAction() {
        orchestrator.confirmAction()
    }

    fun cancelAction() {
        orchestrator.cancelAction()
    }
}
