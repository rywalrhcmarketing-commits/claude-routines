package pl.victor.app.proactive

/**
 * Codzienny briefing - co powiedzieć rano, zanim ktokolwiek o cokolwiek zapyta.
 *
 * ## Czym to się różni od alertów
 * Alert reaguje na zdarzenie ("będzie padać przed Twoim spotkaniem"). Briefing
 * jest jeden, o stałej porze, i ma odpowiedzieć na pytanie "co mnie dziś czeka"
 * ZANIM się je zada. To jedyna funkcja w tej aplikacji, która odzywa się bez
 * powodu w danych - dlatego ma być krótka i dająca się przyciąć.
 *
 * ## Preferencje są tu funkcją, nie ozdobą
 * Briefing, którego nie da się skrócić, po tygodniu jest wyłączany na stałe.
 * Każdą sekcję da się wyłączyć osobno, a [Preferences.focus] to zdanie własnymi
 * słowami, doklejane do polecenia dla modelu - bo nie da się z góry przewidzieć,
 * co dla kogo jest ważne.
 *
 * Ta klasa tylko SKŁADA POLECENIE. Zbieranie danych i mówienie należy do
 * [DailyBriefingWorker] - dzięki temu układ briefingu da się sprawdzić testem
 * bez sieci, kalendarza i syntezatora mowy.
 */
object DailyBriefing {

    /** Co ma być w briefingu i jak długi ma być. */
    data class Preferences(
        val includeCalendar: Boolean = true,
        val includeWeather: Boolean = true,
        val includeAirQuality: Boolean = false,
        val includeMail: Boolean = false,
        /** Własnymi słowami: na co zwracać uwagę. Pusty = bez dodatkowych wskazówek. */
        val focus: String = "",
        val length: Length = Length.SHORT
    )

    enum class Length(val id: String, val label: String, val instruction: String) {
        SHORT("short", "Krótki", "Zmieść się w trzech zdaniach."),
        NORMAL("normal", "Zwykły", "Zmieść się w pięciu zdaniach."),
        DETAILED("detailed", "Szczegółowy", "Możesz użyć do dziesięciu zdań.");

        companion object {
            fun fromId(id: String?): Length = entries.firstOrNull { it.id == id } ?: SHORT
        }
    }

    /** Fragmenty kontekstu zebrane przez wołającego; `null` = niedostępne. */
    data class Material(
        val calendar: String? = null,
        val weather: String? = null,
        val mail: String? = null
    )

    /**
     * Czy jest z czego zrobić briefing.
     *
     * Bez tego sprawdzenia asystent mówiłby rano "nie mam żadnych informacji",
     * co jest gorsze niż milczenie - budzi i nic nie wnosi.
     */
    fun hasAnything(material: Material, preferences: Preferences): Boolean =
        sectionsFrom(material, preferences).isNotEmpty()

    /**
     * Buduje polecenie dla modelu.
     *
     * @return polecenie albo `null`, gdy nie ma o czym mówić
     */
    fun buildPrompt(material: Material, preferences: Preferences): String? {
        val sections = sectionsFrom(material, preferences)
        if (sections.isEmpty()) return null

        return buildString {
            append("Przygotuj poranny briefing dla użytkownika. ")
            append("Mówisz na głos, więc pisz tak, jak się mówi: bez list, bez ")
            append("nagłówków, bez znaczników. ")
            append(preferences.length.instruction).append(' ')
            append("Zacznij od najważniejszej rzeczy, nie od powitania. ")
            append("Nie wymyślaj niczego, czego nie ma w danych poniżej - ")
            append("jeśli czegoś brakuje, po prostu o tym nie mów.")
            if (preferences.focus.isNotBlank()) {
                append("\n\nUżytkownik prosi, żeby zwracać uwagę na: ")
                append(preferences.focus.trim())
            }
            append("\n\n")
            sections.forEach { section ->
                append(section).append('\n')
            }
        }
    }

    private fun sectionsFrom(material: Material, preferences: Preferences): List<String> =
        buildList {
            if (preferences.includeCalendar) material.calendar?.takeIf { it.isNotBlank() }?.let(::add)
            if (preferences.includeWeather) material.weather?.takeIf { it.isNotBlank() }?.let(::add)
            if (preferences.includeMail) material.mail?.takeIf { it.isNotBlank() }?.let(::add)
        }

    /**
     * Czy wypowiedź to prośba o briefing.
     *
     * Dopasowanie jest ścisłe - całe zdanie. "Co dziś" jako fragment łapałoby
     * "co dziś jadłeś" i podobne pytania, które briefingiem nie są.
     */
    fun isBriefingRequest(text: String): Boolean {
        val normalized = text.lowercase().trim().trimEnd('.', '!', '?').trim()
        return normalized in REQUEST_PHRASES
    }

    private val REQUEST_PHRASES = setOf(
        "briefing",
        "co dziś",
        "co dzisiaj",
        "co mnie dziś czeka",
        "co mnie dzisiaj czeka",
        "podsumuj dzień",
        "streść mi dzień",
        "jak wygląda mój dzień"
    )
}
