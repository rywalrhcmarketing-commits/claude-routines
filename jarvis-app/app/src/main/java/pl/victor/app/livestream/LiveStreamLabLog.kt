package pl.victor.app.livestream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trwały, eksportowalny log jednej sesji Live Stream Lab.
 *
 * W przeciwieństwie do [pl.victor.app.ble.VictorManager]'s `notifyLog` (w pamięci,
 * ograniczony, dzielony z resztą apki), to jest dedykowany plik per sesja,
 * dopisywany na bieżąco - przeżywa crash apki w trakcie eksperymentu i da się go
 * wyeksportować (Android share sheet) i przesłać do dalszej diagnozy.
 *
 * Zapisy idą przez pojedynczy wątek IO ([ioDispatcher]), więc kolejność wpisów
 * w pliku odpowiada kolejności wywołań [append] - ważne, bo to jedyny ślad po
 * tym, co się faktycznie stało podczas eksperymentu ze sprzętem.
 */
class LiveStreamLabLog(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME)

    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var sessionFile: File? = null

    /** Zaczyna nowy plik sesji. Wołaj przy każdym otwarciu ekranu Live Stream Lab. */
    fun startSession(): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "session_$stamp.txt")
        sessionFile = file
        scope.launch {
            try {
                file.writeText("=== Live Stream Lab - sesja $stamp ===\n\n")
                trimOldFiles()
            } catch (e: Exception) {
                Log.e(TAG, "Nie udało się utworzyć pliku sesji", e)
            }
        }
        return file
    }

    /**
     * Dopisuje jedną linię do aktualnej sesji (i zaczyna sesję, jeśli jeszcze
     * jej nie było). Bezpieczne do wołania z dowolnego wątku, w tym z callbacków
     * BLE na wątku głównym - sam zapis do pliku idzie w tle.
     */
    fun append(category: String, message: String) {
        val file = sessionFile ?: startSession()
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$stamp] $category: $message\n"
        Log.d(TAG, line.trim())
        scope.launch {
            try {
                file.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "Nie udało się dopisać do logu Live Stream Lab", e)
            }
        }
    }

    /** Plik aktualnej sesji, albo `null` gdy jeszcze się nie zaczęła. */
    fun currentSessionFile(): File? = sessionFile

    /** Zapisane sesje, od najnowszej. */
    fun listSessions(): List<File> =
        dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Zostawia tylko [MAX_SESSIONS] najnowszych plików. */
    private fun trimOldFiles() {
        listSessions().drop(MAX_SESSIONS).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "LiveStreamLabLog"
        private const val DIR_NAME = "livestream_lab"
        private const val MAX_SESSIONS = 20
    }
}
