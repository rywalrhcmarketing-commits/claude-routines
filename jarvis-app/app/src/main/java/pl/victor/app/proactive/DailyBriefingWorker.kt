package pl.victor.app.proactive

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.victor.app.VictorApplication
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Wygłasza codzienny briefing o wybranej porze.
 *
 * ## Dlaczego to nie jest zwykły alarm
 * Bo briefing ma sens tylko wtedy, gdy jest kogo obudzić: przy zgaszonym
 * telefonie w szufladzie mówienie do nikogo zużywa baterię i nic nie daje.
 * Worker sprawdza więc warunki i po cichu odpuszcza, zamiast mówić w próżnię.
 */
class DailyBriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val tag = "DailyBriefingWorker"

    override suspend fun doWork(): Result {
        val app = applicationContext as? VictorApplication ?: return Result.success()

        if (!app.settings.isBriefingEnabled()) {
            Log.d(tag, "Briefing wyłączony w ustawieniach")
            return Result.success()
        }

        // WorkManager budzi z tolerancją nawet kilkudziesięciu minut, a briefing
        // o 9:40 zamiast o 7:00 jest bezużyteczny. Sprawdzamy więc, czy jesteśmy
        // w oknie wokół wybranej godziny - poza nim odpuszczamy do jutra.
        if (!isWithinWindow(app.settings.getBriefingHour(), app.settings.getBriefingMinute())) {
            Log.d(tag, "Poza oknem briefingu - pomijam")
            return Result.success()
        }

        // Raz dziennie. Bez tego każde wybudzenie w oknie powtarzałoby briefing.
        val today = todayKey()
        if (app.settings.isAlertAlreadyShown(today)) {
            Log.d(tag, "Briefing na dziś już był")
            return Result.success()
        }
        app.settings.markAlertShown(today)

        Log.i(tag, "Wygłaszam briefing")
        app.orchestrator.runBriefing()
        return Result.success()
    }

    private fun isWithinWindow(hour: Int, minute: Int): Boolean {
        val now = Calendar.getInstance()
        val target = (hour * 60 + minute).toLong()
        val current = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)).toLong()
        return current >= target && current - target <= WINDOW_MINUTES
    }

    private fun todayKey(): String {
        val now = Calendar.getInstance()
        return "briefing-%d-%d".format(
            now.get(Calendar.YEAR),
            now.get(Calendar.DAY_OF_YEAR)
        )
    }

    companion object {
        const val WORK_NAME = "victor_daily_briefing"

        /**
         * Ile minut po wybranej godzinie briefing jest jeszcze na czasie.
         *
         * WorkManager nie gwarantuje punktualności, więc bez tego okna briefing
         * potrafiłby paść w środku dnia. Godzina to kompromis: dłużej znaczy
         * "spóźniony i bez sensu", krócej - "pominięty, bo telefon spał".
         */
        private const val WINDOW_MINUTES = 60L
    }
}

/** Włącza i wyłącza codzienny briefing. */
object DailyBriefingScheduler {

    /**
     * Sprawdzanie idzie co 30 minut, a nie raz na dobę.
     *
     * WorkManager nie uruchamia zadań o konkretnej godzinie - dostaje okres i
     * budzi "kiedyś w nim". Częste sprawdzanie z oknem po stronie workera trafia
     * w wybraną porę znacznie lepiej niż jedno zadanie dobowe, a kosztuje tyle,
     * co odczytanie zegara.
     */
    private const val CHECK_INTERVAL_MINUTES = 30L

    fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyBriefingWorker>(
            CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyBriefingWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailyBriefingWorker.WORK_NAME)
    }
}
