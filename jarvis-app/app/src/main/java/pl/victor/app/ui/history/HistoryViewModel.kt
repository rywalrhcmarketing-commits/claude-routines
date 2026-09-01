package pl.victor.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.victor.app.VictorApplication
import pl.victor.app.data.HistoryRepository

/**
 * ViewModel dla ekranu historii.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication
    private val repo = HistoryRepository(app.database.conversationDao())

    val entries: StateFlow<List<pl.victor.app.data.ConversationEntry>> =
        repo.observeRecent(limit = 20)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    fun delete(id: Long) {
        viewModelScope.launch {
            // Najpierw pobierz wpis (potrzebujemy ścieżki do zdjęcia)
            val entry = repo.getById(id)
            entry?.firstPhotoPath?.let { path ->
                app.photoStorage.deletePhoto(path)
            }
            repo.delete(id)
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            // Pobierz wszystkie wpisy żeby usunąć ich zdjęcia
            entries.value.forEach { entry ->
                entry.firstPhotoPath?.let { path ->
                    app.photoStorage.deletePhoto(path)
                }
            }
            repo.deleteAll()
        }
    }
}
