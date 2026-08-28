package pl.jarvis.app.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Scheduler dla ProactiveAlertsWorker.
 * Uruchamia worker co 15 minut (minimum WorkManager).
 */
object ProactiveAlertsScheduler {

    /**
     * Włącza cykliczne sprawdzanie alertów.
     */
    fun enable(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ProactiveAlertsWorker>(
            15, TimeUnit.MINUTES,  // minimum WorkManager
            5, TimeUnit.MINUTES    // flex window
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ProactiveAlertsWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Wyłącza sprawdzanie.
     */
    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ProactiveAlertsWorker.WORK_NAME)
    }
}
