package pl.victor.app.calendar

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
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

/**
 * Google Calendar API integration.
 *
 * OAuth2 flow:
 * 1. User loguje się kontem Google (Sign-In)
 * 2. Dostajemy token
 * 3. Tworzymy Calendar service
 * 4. Możemy czytać/dodawać eventy
 *
 * Limity:
 * - Darmowy tier: 1M queries/day
 * - Wymaga Google API Key (do tworzenia OAuth client)
 *
 * Wymaga w build.gradle.kts:
 * - implementation("com.google.android.gms:play-services-auth:20.7.0")
 * - implementation("com.google.api-client:google-api-client-android:2.2.0")
 * - implementation("com.google.apis:google-api-services-calendar:v3-rev20240605-2.0.0")
 */
class GoogleCalendarService(private val context: Context) {

    private val tag = "GoogleCalendarService"

    private val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(
            com.google.android.gms.common.api.Scope(CalendarScopes.CALENDAR),
            com.google.android.gms.common.api.Scope(CalendarScopes.CALENDAR_EVENTS)
        )
        .build()

    private val signInClient: GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions)

    /**
     * Intent do uruchomienia flow logowania Google.
     */
    fun getSignInIntent() = signInClient.signInIntent

    /**
     * Sprawdza czy user jest zalogowany.
     */
    fun isSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    /**
     * Pobiera obecne konto Google.
     */
    fun getCurrentAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    /**
     * Wylogowanie.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        signInClient.signOut()
        Log.i(tag, "Wylogowano z Google")
    }

    /**
     * Tworzy Calendar service z konta.
     */
    private fun getCalendarService(): Calendar? {
        val account = getCurrentAccount() ?: return null
        return try {
            val transport = NetHttpTransport()
            Calendar.Builder(
                transport,
                GsonFactory.getDefaultInstance(),
                GoogleAccountCredential.usingOAuth2(
                    context, Collections.singleton(CalendarScopes.CALENDAR)
                ).setSelectedAccountName(account.email)
            )
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

/**
 * Wrapper na Google Account Credential.
 * To musi być w oddzielnym obiekcie bo Google Account Credential
 * zależy od com.google.api-client:google-api-client-android.
 */
private object GoogleAccountCredential {
    fun usingOAuth2(context: Context, scopes: Collection<String>): com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential {
        return com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
            .usingOAuth2(context, scopes)
    }
}
