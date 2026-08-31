package pl.jarvis.app.power

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Wyjątek Doze/App Standby na optymalizację baterii.
 *
 * Bez tego system usypia proces po zgaszeniu ekranu - połączenie BLE z okularami
 * i nasłuch wake worda przestają działać po kilku minutach, niezależnie od tego,
 * jak dobrze [pl.jarvis.app.ble.JarvisForegroundService] jest napisany. Foreground
 * service zwiększa priorytet procesu, ale agresywne nakładki producentów (Xiaomi,
 * Huawei, Samsung) potrafią go i tak zabić bez tego wyjątku.
 */
object BatteryOptimizationHelper {

    fun isIgnoringOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Bezpośrednia prośba systemowa o wyjątek dla tej aplikacji. */
    fun requestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Fallback, gdy producent nie obsługuje intencji bezpośredniej - pełna lista aplikacji. */
    fun optimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Ostateczny fallback - strona "Informacje o aplikacji". */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Próbuje kolejno: prośbę bezpośrednią, listę wyjątków, informacje o aplikacji.
     * Część producentów (np. MIUI) nie obsługuje [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
     * mimo że deklaruje je w AOSP - stąd łańcuch fallbacków zamiast jednej próby.
     */
    fun launchExemptionFlow(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            context.startActivity(requestExemptionIntent(context))
            return
        } catch (_: ActivityNotFoundException) {
            // spadamy niżej
        }
        try {
            context.startActivity(optimizationListIntent())
            return
        } catch (_: ActivityNotFoundException) {
            // spadamy niżej
        }
        try {
            context.startActivity(appDetailsIntent(context))
        } catch (_: ActivityNotFoundException) {
            // Nic więcej nie da się zrobić - producent zablokował wszystkie ścieżki.
        }
    }
}
