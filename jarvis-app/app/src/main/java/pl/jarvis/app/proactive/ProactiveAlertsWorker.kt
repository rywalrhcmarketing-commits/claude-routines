package pl.jarvis.app.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pl.jarvis.app.JarvisApplication
import pl.jarvis.app.R
import pl.jarvis.app.ui.MainActivity

/**
 * Worker który sprawdza alerty proaktywne w tle.
 * Uruchamiany co 15 minut przez WorkManager.
 *
 * Sprawdza:
 * 1. Kalendarz - następne spotkanie
 * 2. Pogodę - czy będzie padać w oknie (teraz → wyjście)
 * 3. Generuje alerty jeśli warunki są spełnione
 * 4. Wyświetla notyfikację z rekomendacją
 */
class ProactiveAlertsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val tag = "ProactiveAlertsWorker"

    override suspend fun doWork(): Result {
        Log.d(tag, "Running proactive alerts check")

        val app = applicationContext as? JarvisApplication
            ?: return Result.success()

        // 1. Sprawdź czy worker jest włączony
        if (!app.settings.isProactiveAlertsEnabled()) {
            Log.d(tag, "Proactive alerts disabled in settings")
            return Result.success()
        }

        // 2. Sprawdź kalendarz
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            Log.d(tag, "Brak READ_CALENDAR - skip")
            return Result.success()
        }

        val calendarService = CalendarService(applicationContext)
        val nextEvent = calendarService.getNextEventToLeaveFor()
        if (nextEvent == null) {
            Log.d(tag, "Brak nadchodzących spotkań")
            return Result.success()
        }

        // 3. Sprawdź pogodę
        val apiKey = app.settings.getOpenWeatherApiKey()
        if (apiKey.isBlank()) {
            Log.d(tag, "Brak klucza OpenWeatherMap")
            return Result.success()
        }

        val location = app.settings.getWeatherLocation()
        if (location.isBlank()) {
            Log.d(tag, "Brak lokalizacji dla pogody")
            return Result.success()
        }

        val weatherService = WeatherService(apiKey)
        val geo = weatherService.geocode(location)
        if (geo == null) {
            Log.d(tag, "Nie znalazłem lokalizacji: $location")
            return Result.success()
        }

        val forecast = weatherService.getForecast(geo.lat, geo.lon)
        if (forecast == null) {
            Log.d(tag, "Nie udało się pobrać prognozy")
            return Result.success()
        }

        // 4. Analizuj i wygeneruj alerty
        val engine = ProactiveAlertsEngine()
        val alerts = engine.analyze(nextEvent, forecast)

        // 5. Wyślij notyfikacje (ale nie spamuj - tylko raz na alert)
        val prefs = app.settings
        alerts.forEach { alert ->
            val alertKey = "${alert.type}-${nextEvent.id}-${nextEvent.beginMs / (30 * 60 * 1000)}"
            if (!prefs.isAlertAlreadyShown(alertKey)) {
                sendNotification(alert)
                prefs.markAlertShown(alertKey)
            } else {
                Log.d(tag, "Alert $alertKey już wysłany, skip")
            }
        }

        return Result.success()
    }

    private fun sendNotification(alert: ProactiveAlert) {
        if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            Log.w(tag, "Brak POST_NOTIFICATIONS - nie wyślę notyfikacji")
            return
        }

        // Android 8+ - kanał
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proaktywne alerty",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pogoda, kalendarz, sugestie wyjścia"
                enableVibration(true)
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)  // TODO: dedykowana ikona
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(alert.type.ordinal + 100, notification)
            Log.i(tag, "Wysłano notyfikację: ${alert.title}")
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission missing", e)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(applicationContext, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "proactive_alerts"
        const val WORK_NAME = "proactive_alerts_worker"
    }
}
