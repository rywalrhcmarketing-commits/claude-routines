package pl.victor.app.calendar

import android.content.Context
import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.google.api.services.calendar.model.Events
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import pl.victor.app.google.GoogleAccountManager

/**
 * Google Calendar API integration.
 *
 * Logowanie i credential idą przez współdzielony [GoogleAccountManager] -
 * jeden login Google daje dostęp do Calendar, Gmaila i innych usług
 * naraz (patrz jego dokumentacja).
 *
 * Limity:
 * - Darmowy tier: 1M queries/day
 *
 * Wymaga w build.gradle.kts:
 * - implementation("com.google.android.gms:play-services-auth:20.7.0")
 * - implementation("com.google.api-client:google-api-client-android:2.2.0")
 * - implementation("com.google.apis:google-api-services-calendar:v3-rev20260708-2.0.0")
 */
class GoogleCalendarService(context: Context) {

    private val tag = "GoogleCalendarService"
    private val accountManager = GoogleAccountManager(context)

    /**
     * Sprawdza czy user jest zalogowany (i ma nadany dostęp do Calendar).
     *
     * Logowanie/wylogowanie samo idzie przez [GoogleAccountManager] bezpośrednio
     * (patrz ekran Ustawień) - jest wspólne dla Calendar i Gmaila, więc nie ma
     * sensu duplikować go tutaj.
     */
    fun isSignedIn(): Boolean = accountManager.isSignedIn()

    /**
     * Tworzy Calendar service z konta.
     */
    private fun getCalendarService(): Calendar? {
        val credential = accountManager.getCredential(
            Collections.singleton(CalendarScopes.CALENDAR)
        ) ?: return null
        return try {
            Calendar.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("V.I.C.T.O.R.")
                .build()
        } catch (e: Exception) {
            Log.e(tag, "Failed to build calendar service", e)
            null
        }
    }

    /**
     * Pobiera nadchodzące eventy z kalendarza.
     */
    suspend fun getUpcomingEvents(maxResults: Int = 10): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val service = getCalendarService() ?: return@withContext emptyList()
        try {
            val now = com.google.api.client.util.DateTime(System.currentTimeMillis())
            val events: Events = service.events().list("primary")
                .setMaxResults(maxResults)
                .setTimeMin(now)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute()

            events.items?.mapNotNull { event ->
                parseEvent(event)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch events", e)
            emptyList()
        }
    }

    /**
     * Tworzy nowy event w kalendarzu.
     */
    suspend fun createEvent(
        title: String,
        description: String? = null,
        startTimeMillis: Long,
        durationMinutes: Int = 60,
        location: String? = null
    ): CalendarEvent? = withContext(Dispatchers.IO) {
        val service = getCalendarService() ?: return@withContext null
        try {
            val event = Event().apply {
                summary = title
                this.description = description
                this.location = location

                start = EventDateTime().apply {
                    dateTime = com.google.api.client.util.DateTime(startTimeMillis)
                    timeZone = java.util.TimeZone.getDefault().id
                }
                end = EventDateTime().apply {
                    dateTime = com.google.api.client.util.DateTime(startTimeMillis + durationMinutes * 60_000L)
                    timeZone = java.util.TimeZone.getDefault().id
                }
            }

            val created = service.events().insert("primary", event).execute()
            Log.i(tag, "Created event: ${created.id}")
            parseEvent(created)
        } catch (e: Exception) {
            Log.e(tag, "Failed to create event", e)
            null
        }
    }

    /**
     * Parsuje Google Event na nasz model.
     */
    private fun parseEvent(event: Event): CalendarEvent? {
        val start = event.start?.dateTime?.value ?: event.start?.date?.value ?: return null
        val end = event.end?.dateTime?.value ?: event.end?.date?.value ?: return null
        return CalendarEvent(
            id = event.id ?: "",
            title = event.summary ?: "(bez tytułu)",
            description = event.description ?: "",
            startTimeMillis = start,
            endTimeMillis = end,
            location = event.location ?: "",
            allDay = event.start?.date != null
        )
    }
}

/**
 * Model eventu kalendarza (niezależny od Google API).
 */
data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val location: String,
    val allDay: Boolean
) {
    fun startTimeFormatted(): String {
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(startTimeMillis))
    }

    fun durationMinutes(): Int = ((endTimeMillis - startTimeMillis) / 60_000L).toInt()
}
