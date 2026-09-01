package pl.victor.app.ble

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
import pl.victor.app.R
import pl.victor.app.ui.MainActivity

/**
 * Usługa pierwszoplanowa, która trzyma proces przy życiu, gdy V.I.C.T.O.R. nasłuchuje
 * wake worda i/lub jest połączony z okularami przez BLE.
 *
 * Bez tego Android usypia proces kilka minut po zgaszeniu ekranu (Doze/App Standby)
 * i połączenie BLE oraz `AudioRecord` używany przez Porcupine przestają działać po
 * cichu - funkcje "wyglądają" jak włączone w Ustawieniach, ale nic nie reaguje.
 * Start/stop jest sterowany reaktywnie z [pl.victor.app.VictorApplication] na
 * podstawie stanu połączenia i przełącznika wake worda, więc nic innego nie musi
 * pamiętać o wywołaniu tej usługi.
 */
class VictorForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: DEFAULT_REASON
        try {
            startForeground(NOTIFICATION_ID, buildNotification(reason))
        } catch (e: Exception) {
            // Android 12+ może odmówić startu usługi pierwszoplanowej, jeśli proces
            // nie ma w danym momencie wyjątku "start w tle" (ForegroundServiceStartNotAllowedException
            // i pokrewne). To ograniczenie systemowe, nie błąd aplikacji - logujemy i kończymy,
            // zamiast wywalać cały proces.
            Log.w(TAG, "Nie udało się wystartować usługi pierwszoplanowej: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun buildNotification(reason: String): android.app.Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V.I.C.T.O.R.")
            .setContentText(reason)
            .setSmallIcon(R.mipmap.ic_launcher) // TODO: dedykowana ikona statusowa
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "V.I.C.T.O.R. w tle", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Utrzymuje połączenie z okularami i nasłuch wake worda w tle."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "VictorFgService"
        private const val CHANNEL_ID = "victor_background"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_REASON = "reason"
        private const val DEFAULT_REASON = "Aktywny w tle"

        fun start(context: Context, reason: String) {
            try {
                val intent = Intent(context, VictorForegroundService::class.java)
                    .putExtra(EXTRA_REASON, reason)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Nie udało się uruchomić usługi w tle: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VictorForegroundService::class.java))
        }
    }
}
