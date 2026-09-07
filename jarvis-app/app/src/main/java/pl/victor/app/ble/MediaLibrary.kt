package pl.victor.app.ble

/**
 * Porządkowanie listy plików z okularów.
 *
 * Okulary oddają płaską listę nazw (`getMediaFileList`), bez typów, dat ani
 * rozmiarów - tylko nazwy. Cała wiedza o tym, co jest czym, siedzi więc w
 * rozszerzeniu pliku i w kolejności nazw. Wydzielone z UI, żeby dało się to
 * sprawdzić testem: pomyłka tutaj pokazuje wideo w zakładce ze zdjęciami, a
 * tego nie widać w kodzie ekranu.
 */
object MediaLibrary {

    enum class Kind(val title: String, val emoji: String) {
        PHOTO("Zdjęcia", "📷"),
        VIDEO("Wideo", "🎬"),
        AUDIO("Nagrania", "🎙️"),
        OTHER("Inne pliki", "📄")
    }

    data class Item(val name: String, val kind: Kind)

    private val PHOTO_EXT = listOf(".jpg", ".jpeg", ".png")
    private val VIDEO_EXT = listOf(".mp4", ".avi", ".mov")
    private val AUDIO_EXT = listOf(".wav", ".mp3", ".opus", ".pcm", ".amr")

    fun kindOf(name: String): Kind {
        val lower = name.lowercase()
        return when {
            PHOTO_EXT.any { lower.endsWith(it) } -> Kind.PHOTO
            VIDEO_EXT.any { lower.endsWith(it) } -> Kind.VIDEO
            AUDIO_EXT.any { lower.endsWith(it) } -> Kind.AUDIO
            else -> Kind.OTHER
        }
    }

    /**
     * Układa pliki w grupy, najnowsze na górze.
     *
     * Nazwy z okularów są sekwencyjne albo oparte na czasie, więc sortowanie
     * malejąco po nazwie stawia najnowsze pierwsze - tak samo, jak robi to
     * [VictorManager.downloadLatestPhoto], gdy szuka najnowszego pliku.
     * Puste grupy nie trafiają do wyniku: pusta zakładka "Wideo" wygląda jak
     * awaria, a znaczy tylko tyle, że nikt nie nagrywał.
     */
    fun group(names: List<String>): List<Pair<Kind, List<Item>>> =
        names.asSequence()
            .filter { it.isNotBlank() }
            .map { Item(it.trim(), kindOf(it.trim())) }
            .groupBy { it.kind }
            .toList()
            .sortedBy { (kind, _) -> kind.ordinal }
            .map { (kind, items) -> kind to items.sortedByDescending { it.name } }
            .filter { (_, items) -> items.isNotEmpty() }

    /** Czy ten plik da się pokazać na ekranie bez zewnętrznej aplikacji. */
    fun isViewable(item: Item): Boolean = item.kind == Kind.PHOTO
}
