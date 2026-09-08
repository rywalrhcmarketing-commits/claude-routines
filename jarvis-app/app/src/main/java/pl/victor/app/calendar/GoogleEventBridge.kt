package pl.victor.app.calendar

/**
 * Przekłada wydarzenie z Google Calendar API na model kalendarza urządzenia.
 *
 * ## Po co to w ogóle istnieje
 * W aplikacji są DWIE niezależne drogi do kalendarza i to była przyczyna
 * zgłoszenia "AI mówi, że nie ma dostępu do kalendarza Google, ale potrafi
 * utworzyć wydarzenie":
 *
 * - ODCZYT szedł przez `CalendarContract`, czyli kalendarz urządzenia, i wymagał
 *   uprawnienia systemowego READ_CALENDAR;
 * - ZAPIS szedł przez Google Calendar API i wymagał połączonego konta.
 *
 * Podłączenie konta Google nie dawało więc odczytu, a przyznanie uprawnienia nie
 * dawało zapisu. Z zewnątrz wyglądało to na kaprys asystenta: raz kalendarz jest,
 * raz go nie ma - a naprawdę były to dwie różne funkcje o tej samej nazwie.
 *
 * Ten most pozwala czytać przez API i wpuścić wynik w tę samą drogę, którą już
 * idzie kalendarz urządzenia - bez dublowania formatowania kontekstu.
 *
 * ## Czemu osobny plik i czysta funkcja
 * Bo to jedyny kawałek tej ścieżki, który da się sprawdzić bez telefonu, konta
 * Google i sieci. Pomyłka w przeliczeniu czasu jest niewidoczna w kodzie, a w
 * użyciu daje spotkania o złej godzinie.
 */
object GoogleEventBridge {

    /**
     * Wydarzenie z API w postaci, której oczekuje reszta aplikacji.
     *
     * @param event wydarzenie z Google Calendar API
     * @param index pozycja na liście - służy wyłącznie za identyfikator, bo model
     *   urządzenia ma `id` liczbowe, a API tekstowe. Nic się na tym `id` nie
     *   opiera poza rozróżnianiem pozycji w obrębie jednej listy.
     */
    fun toDeviceEvent(
        event: CalendarEvent,
        index: Int
    ): pl.victor.app.proactive.CalendarEvent =
        pl.victor.app.proactive.CalendarEvent(
            id = index.toLong(),
            title = event.title,
            beginMs = event.startTimeMillis,
            endMs = event.endTimeMillis,
            location = event.location.ifBlank { null },
            calendarName = CALENDAR_NAME,
            description = event.description.ifBlank { null },
            // Ten sam kwadrans co w kalendarzu urządzenia - inaczej to samo
            // spotkanie miałoby inny moment wyjścia zależnie od tego, którą
            // drogą przyszło.
            leaveByMs = event.startTimeMillis - LEAVE_BUFFER_MS
        )

    /** Cała lista naraz - kolejność zachowana, bo od niej zależy `id`. */
    fun toDeviceEvents(events: List<CalendarEvent>): List<pl.victor.app.proactive.CalendarEvent> =
        events.mapIndexed { index, event -> toDeviceEvent(event, index) }

    /** Nazwa pokazywana przy wydarzeniu, żeby było widać, skąd pochodzi. */
    const val CALENDAR_NAME = "Kalendarz Google"

    /** Ile przed początkiem trzeba wyjść - zgodnie z kalendarzem urządzenia. */
    private const val LEAVE_BUFFER_MS = 15 * 60 * 1000L
}
