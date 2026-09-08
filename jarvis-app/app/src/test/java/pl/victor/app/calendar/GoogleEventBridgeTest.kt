package pl.victor.app.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pomyłka w przeliczeniu jest niewidoczna w kodzie, a w użyciu daje spotkania o
 * złej godzinie albo puste pola tam, gdzie API zwraca pusty tekst zamiast null.
 */
class GoogleEventBridgeTest {

    private fun google(
        title: String = "Spotkanie",
        start: Long = 1_800_000_000_000L,
        end: Long = 1_800_003_600_000L,
        location: String = "",
        description: String = ""
    ) = CalendarEvent(
        id = "abc",
        title = title,
        description = description,
        startTimeMillis = start,
        endTimeMillis = end,
        location = location,
        allDay = false
    )

    @Test
    fun `godziny przechodza bez zmiany`() {
        val mapped = GoogleEventBridge.toDeviceEvent(google(), 0)
        assertEquals(1_800_000_000_000L, mapped.beginMs)
        assertEquals(1_800_003_600_000L, mapped.endMs)
    }

    @Test
    fun `wyjscie kwadrans przed poczatkiem`() {
        // Ten sam kwadrans co w kalendarzu urządzenia - inaczej to samo spotkanie
        // miałoby inny moment wyjścia zależnie od drogi, którą przyszło.
        val mapped = GoogleEventBridge.toDeviceEvent(google(start = 1_000_000_000L), 0)
        assertEquals(1_000_000_000L - 15 * 60 * 1000L, mapped.leaveByMs)
    }

    @Test
    fun `puste pola z API staja sie nullem`() {
        // API oddaje pusty ciąg, a model urządzenia null. Bez tego kontekst dla
        // modelu dostawałby "Miejsce: " bez wartości.
        val mapped = GoogleEventBridge.toDeviceEvent(google(location = "", description = ""), 0)
        assertNull(mapped.location)
        assertNull(mapped.description)
    }

    @Test
    fun `wypelnione pola zostaja`() {
        val mapped = GoogleEventBridge.toDeviceEvent(
            google(location = "Biuro", description = "Kwartalne"), 0
        )
        assertEquals("Biuro", mapped.location)
        assertEquals("Kwartalne", mapped.description)
    }

    @Test
    fun `zrodlo jest widoczne w nazwie kalendarza`() {
        assertEquals("Kalendarz Google", GoogleEventBridge.toDeviceEvent(google(), 0).calendarName)
    }

    @Test
    fun `cala lista zachowuje kolejnosc i numeruje pozycje`() {
        val mapped = GoogleEventBridge.toDeviceEvents(
            listOf(google(title = "Pierwsze"), google(title = "Drugie"))
        )
        assertEquals(listOf("Pierwsze", "Drugie"), mapped.map { it.title })
        assertEquals(listOf(0L, 1L), mapped.map { it.id })
    }

    @Test
    fun `pusta lista daje pusta liste`() {
        assertEquals(emptyList<Any>(), GoogleEventBridge.toDeviceEvents(emptyList()))
    }
}
