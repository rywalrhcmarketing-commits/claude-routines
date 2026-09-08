package pl.victor.app.calendar

/**
 * Wydarzenie tak, jak oddaje je Google Calendar API.
 *
 * Wydzielone z [GoogleCalendarService], żeby przeliczenie na model urządzenia
 * ([GoogleEventBridge]) dało się sprawdzić testem - sam serwis ciągnie za sobą
 * klienta API, konto i sieć.
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
