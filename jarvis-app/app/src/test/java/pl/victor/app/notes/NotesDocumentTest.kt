package pl.victor.app.notes

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dokument trafia do NotebookLM jako źródło i model odpowiada Z NIEGO. Zła
 * kolejność albo brak dat dają odpowiedzi, które brzmią wiarygodnie i są
 * nieprawdziwe - dlatego to jest sprawdzane testem, a nie na oko.
 */
class NotesDocumentTest {

    private val now = 1_757_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `dokument grupuje notatki po dniach`() {
        val doc = NotesDocument.build(
            listOf(
                Notes.Note("Kupić mleko", now),
                Notes.Note("Oddać książkę", now - day),
                Notes.Note("Zadzwonić do Ani", now - day)
            ),
            nowMs = now
        )
        // Nagłówek dnia zamiast liczenia na datach przez model.
        assertTrue(doc.contains("== "))
        assertTrue(doc.contains("Kupić mleko"))
        assertTrue(doc.contains("Oddać książkę"))
        assertTrue(doc.contains("Liczba notatek: 3"))
    }

    @Test
    fun `najstarsze ida pierwsze`() {
        val doc = NotesDocument.build(
            listOf(Notes.Note("Nowa", now), Notes.Note("Stara", now - 5 * day)),
            nowMs = now
        )
        // Dokument czyta się jak dziennik, od początku - odwrotnie niż listę w aplikacji.
        assertTrue(doc.indexOf("Stara") < doc.indexOf("Nowa"))
    }

    @Test
    fun `pusty dokument mowi wprost ze jest pusty`() {
        val doc = NotesDocument.build(emptyList(), nowMs = now)
        assertTrue(doc.contains("Brak notatek"))
    }

    @Test
    fun `notatka bez daty nie wywraca dokumentu`() {
        val doc = NotesDocument.build(listOf(Notes.Note("Stara notatka", 0)), nowMs = now)
        assertTrue(doc.contains("Bez daty"))
        assertTrue(doc.contains("Stara notatka"))
    }
}
