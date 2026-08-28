package pl.jarvis.app.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zapisuje nieobsłużone wyjątki do pliku, żeby dało się je odczytać po restarcie.
 *
 * Bez tego crash znika razem z procesem - `adb logcat` pokaże go tylko wtedy,
 * gdy telefon był podpięty w momencie awarii.
 *
 * Logi trafiają do `filesDir/crashes/` i są automatycznie przycinane do
 * [MAX_FILES] najnowszych plików.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crashes"
    private const val MAX_FILES = 10

    /**
     * Instaluje handler nieobsłużonych wyjątków.
     * Poprzedni handler jest wywoływany na końcu, żeby system nadal
     * pokazał standardowy dialog awarii.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Nie udało się zapisać raportu awarii", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "Handler awarii zainstalowany")
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.txt")

        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()

        file.writeText(
            buildString {
                appendLine("Czas: $stamp")
                appendLine("Wątek: ${thread.name}")
                appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine()
                append(stackTrace)
            }
        )
        Log.e(TAG, "Zapisano raport awarii: ${file.name}")
        trimOldFiles(dir)
    }

    /** Zostawia tylko [MAX_FILES] najnowszych raportów. */
    private fun trimOldFiles(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    /** Lista zapisanych raportów, od najnowszego. */
    fun listReports(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** Treść najnowszego raportu albo `null` gdy brak awarii. */
    fun latestReport(context: Context): String? =
        listReports(context).firstOrNull()?.readText()

    /** Kasuje wszystkie zapisane raporty. */
    fun clear(context: Context) {
        File(context.applicationContext.filesDir, DIR_NAME).listFiles()?.forEach { it.delete() }
    }
}
