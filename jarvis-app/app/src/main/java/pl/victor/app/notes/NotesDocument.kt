package pl.victor.app.notes

/**
 * Notatki jako dokument tekstowy do wysłania na Dysk Google.
 *
 * ## Po co osobny plik
 * Bo to jest jedyna część eksportu, którą da się sprawdzić testem: reszta to
 * sieć, OAuth i Dysk. Treść dokumentu ma znaczenie praktyczne - to ona trafia
 * do NotebookLM jako źródło, a model odpowiada z niej na pytania. Dokument bez
 * dat albo z pomieszaną kolejnością jest gorszy niż jego brak, bo odpowiedzi
 * brzmią wiarygodnie i są nieprawdziwe.
 */
object NotesDocument {

    /** Nazwa dokumentu na Dysku. Stała, bo plik jest jeden i aktualizowany w miejscu. */
    const val FILE_NAME = "V.I.C.T.O.R. - notatki"

    /**
     * Składa notatki w dokument.
     *
     * Najstarsze NA GÓRZE - odwrotnie niż w aplikacji. W aplikacji przegląda się
     * to, co świeże; dokument czyta się jak dziennik, od początku, i tak też
     * czyta go model.
     *
     * @param nowMs czas wygenerowania, podawany z zewnątrz dla testowalności
     */
    fun build(notes: List<Notes.Note>, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = formatDateTime(nowMs)
        return buildString {
            append("Notatki z V.I.C.T.O.R.\n")
            append("Zaktualizowano: ").append(stamp).append('\n')
            append("Liczba notatek: ").append(notes.size).append("\n\n")
            if (notes.isEmpty()) {
                append("Brak notatek.\n")
                return@buildString
            }
            // Grupujemy po dniu: bez tego pytanie "co zapisałem w poniedziałek"
            // wymaga od modelu liczenia na datach, a to robi źle znacznie
            // częściej niż czytanie gotowego nagłówka.
            notes.sortedBy { it.createdAtMs }
                .groupBy { formatDate(it.createdAtMs) }
                .forEach { (day, sameDay) ->
                    append("== ").append(day).append(" ==\n")
                    sameDay.forEach { note ->
                        append("- ")
                        if (note.createdAtMs > 0L) {
                            append('[').append(formatTime(note.createdAtMs)).append("] ")
                        }
                        append(note.text).append('\n')
                    }
                    append('\n')
                }
        }
    }

    private fun formatDate(ms: Long): String {
        if (ms <= 0L) return "Bez daty"
        return zoned(ms).toLocalDate().toString()
    }

    private fun formatTime(ms: Long): String =
        zoned(ms).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    private fun formatDateTime(ms: Long): String =
        zoned(ms).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun zoned(ms: Long): java.time.ZonedDateTime =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
}
