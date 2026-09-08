package pl.victor.app.proactive

/**
 * Wydarzenie w kalendarzu - wspólny model dla WSZYSTKICH źródeł.
 *
 * Wydzielone z [CalendarService], bo trafiają tu teraz dwie drogi: kalendarz
 * urządzenia i Kalendarz Google (patrz [pl.victor.app.calendar.GoogleEventBridge]).
 * Osobny plik pozwala też sprawdzić przeliczanie testem, bez wciągania dostawcy
 * treści Androida.
 */
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
