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
 * Interwał pochodzi z ustawień; WorkManager nie schodzi poniżej 15 minut.
 */
object ProactiveAlertsScheduler {

    /** Minimalny interwał dopuszczany przez WorkManager. */
    private const val MIN_INTERVAL_MINUTES = 15L

    /**
     * Włącza cykliczne sprawdzanie alertów z interwałem z ustawień.
     *
     * @param intervalMinutes żądany interwał; wartości poniżej 15 min są podnoszone
     *                        do 15, bo WorkManager i tak nie uruchomi zadania częściej
     */
    fun enable(context: Context, intervalMinutes: Int = MIN_INTERVAL_MINUTES.toInt()) {
        val interval = intervalMinutes.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES)
        // Okno elastyczne: 1/3 interwału, ale nie mniej niż 5 minut.
        val flex = (interval / 3).coerceAtLeast(5L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ProactiveAlertsWorker>(
            interval, TimeUnit.MINUTES,
            flex, TimeUnit.MINUTES
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
