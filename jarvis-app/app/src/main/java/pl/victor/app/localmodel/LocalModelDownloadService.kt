package pl.victor.app.localmodel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.R
import pl.victor.app.ui.MainActivity

/**
 * Pobiera model lokalny w tle jako usługa pierwszoplanowa - plik ma ~560MB,
 * więc pobieranie trwa realnie kilka minut i musi przetrwać zgaszenie ekranu.
 * Postęp wystawiony przez [state] (StateFlow) - dowolny ekran może go obserwować,
 * nie tylko ten, który start uruchomił.
 */
class LocalModelDownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val entry = LocalModelCatalog.findById(modelId)
        if (entry == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification(0))
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się wystartować usługi pobierania: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (downloadJob?.isActive == true) return START_STICKY

        downloadJob = scope.launch {
            _state.value = DownloadState.InProgress(0)
            val result = LocalModelDownloader(applicationContext).download(entry) { progress ->
                _state.value = DownloadState.InProgress(progress.percent)
                updateNotification(progress.percent)
            }
            _state.value = result.fold(
                onSuccess = { DownloadState.Done },
                onFailure = { e -> DownloadState.Failed(e.message ?: "Nieznany błąd") }
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(percent: Int): android.app.Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pobieranie modelu lokalnego")
            .setContentText("$percent%")
            .setSmallIcon(R.drawable.ic_stat_victor)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(percent: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(percent))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pobieranie modelu lokalnego", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Postęp pobierania offline'owego modelu AI."
                setShowBadge(false)
            }
        )
    }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class InProgress(val percent: Int) : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    companion object {
        private const val TAG = "LocalModelDownloadSvc"
        private const val CHANNEL_ID = "local_model_download"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_MODEL_ID = "model_id"

        private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state = _state.asStateFlow()

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, LocalModelDownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
