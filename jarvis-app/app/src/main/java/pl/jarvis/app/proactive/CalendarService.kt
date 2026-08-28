package pl.jarvis.app.proactive

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Czyta kalendarz telefonu (lokalny + Google Calendar).
 *
 * Wymaga READ_CALENDAR permission. Bez OAuth, bez API key.
 * Działa z domyślnym kalendarzem użytkownika (Google, Exchange, lokalny).
 */
class CalendarService(private val context: Context) {

    private val tag = "CalendarService"

    /**
     * Sprawdza czy mamy uprawnienie.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Pobiera nadchodzące eventy (max `limit`, domyślnie 5) z `now` do `now + hoursAhead`.
     */
    suspend fun getUpcomingEvents(
        limit: Int = 5,
        hoursAhead: Int = 24
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(tag, "Brak READ_CALENDAR permission")
            return@withContext emptyList()
        }

        val start = System.currentTimeMillis()
        val end = start + hoursAhead * 60L * 60L * 1000L

        val events = mutableListOf<CalendarEvent>()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION
        )

        val selection = "${CalendarContract.Instances.BEGIN} >= ? AND " +
                "${CalendarContract.Instances.BEGIN} <= ?"
        val args = arrayOf(start.toString(), end.toString())
        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        try {
            context.contentResolver.query(
                CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .build(),
                projection, selection, args, sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < limit) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1) ?: "(bez tytułu)"
                    val begin = cursor.getLong(2)
                    val evtEnd = cursor.getLong(3)
                    val location = cursor.getString(4)
                    val calendarName = cursor.getString(5)
                    val description = cursor.getString(6)

                    // Oblicz kiedy user musi wyjść (zakładam 15 min przed + czas dojazdu)
                    val leaveBy = begin - 15 * 60 * 1000L

                    events.add(CalendarEvent(
                        id = id,
                        title = title,
                        beginMs = begin,
                        endMs = evtEnd,
                        location = location,
                        calendarName = calendarName,
                        description = description,
                        leaveByMs = leaveBy
                    ))
                }
            }
        } catch (e: SecurityException) {
            Log.w(tag, "Permission revoked", e)
        } catch (e: Exception) {
            Log.e(tag, "Failed to read calendar", e)
        }

        Log.d(tag, "Found ${events.size} upcoming events")
        events
    }

    /**
     * Następne spotkanie - kiedy user musi wyjść.
     */
    suspend fun getNextEventToLeaveFor(): CalendarEvent? {
        val now = System.currentTimeMillis()
        return getUpcomingEvents(limit = 3, hoursAhead = 12)
            .filter { it.leaveByMs > now }
            .minByOrNull { it.beginMs }
    }
}

data class CalendarEvent(
    val id: Long,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val location: String?,
    val calendarName: String?,
    val description: String?,
    /** Kiedy user powinien wyjść (15 min przed spotkaniem) */
    val leaveByMs: Long
) {
    fun minutesUntilLeave(): Long = (leaveByMs - System.currentTimeMillis()) / (60 * 1000)
    fun minutesUntilBegin(): Long = (beginMs - System.currentTimeMillis()) / (60 * 1000)
}
