package pl.victor.app.actions

import android.util.Log
import java.util.Calendar

/**
 * Wykrywa akcje z tekstu użytkownika (komendy głosowe).
 *
 * 2 strategie:
 * 1. Regex/keyword - szybkie, działa offline, mniej inteligentne
 * 2. AI - dokładniejsze, wymaga internetu
 *
 * Dla MVP używamy głównie regex. AI ma w prompcie listę akcji żeby
 * mógł wskazać akcję specjalnym tagiem [[ACTION: ...]].
 */
class SmartActionDetector {

    private val tag = "SmartActionDetector"

    /**
     * Próbuje wykryć akcję z tekstu.
     * Zwraca listę akcji (może być wiele) lub null jeśli nic nie wykryto.
     */
    fun detect(text: String): List<Action> {
        val lower = text.lowercase().trim()
        val actions = mutableListOf<Action>()

        // === SMS ===
        // "wyślij SMS do Ani o treści cześć" / "wyślij SMS do Ani: cześć"
        val smsRegex = Regex(
            """(?:wy[lł]ij|wy[lś]lij|wy[lś]lij|wyslij)\s+(?:sms|wiadomosc)\s+do\s+(\S+?)(?:\s*[:\-]|\s+(?:o\s+tresci|tresc|tekst|ze)\s+)["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        smsRegex.find(lower)?.let { match ->
            val to = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (to.isNotBlank() && body.isNotBlank()) {
                actions.add(Action.SendSms(to = to, body = body))
            }
        }

        // === CALL ===
        // "zadzwoń do mamy" (nazwa - jedno słowo) albo
        // "zadzwoń pod 123 456 789" (numer - może mieć spacje/myślniki).
        // Wcześniej jeden wzorzec z \S+ łapał z numeru tylko pierwszy fragment
        // przed spacją ("123" z "123 456 789").
        val callNameRegex = Regex(
            """(?:zadzwon|zadzwoń|dzwon|zadzwoń)\s+do\s+(\S+)""",
            RegexOption.IGNORE_CASE
        )
        val callNumberRegex = Regex(
            """(?:zadzwon|zadzwoń|dzwon|zadzwoń)\s+(?:pod|na)\s+([\d\s+\-()]+\d)""",
            RegexOption.IGNORE_CASE
        )
        (callNumberRegex.find(lower) ?: callNameRegex.find(lower))?.let { match ->
            val to = match.groupValues[1].trim()
            if (to.isNotBlank()) {
                actions.add(Action.MakeCall(to = to))
            }
        }

        // === EMAIL ===
        // "wyślij maila do kowalski@x.com o temacie X z treścią Y"
        val emailRegex = Regex(
            """(?:wy[lś]lij|wyslij)\s+(?:mail|email|e-mail)\s+do\s+(\S+?)(?:\s+(?:o\s+)?temat[ie]?\s+["']?(.+?)["']?)?(?:\s+(?:tresci|treści|tekst|o\s+tresci|o\s+treści)\s+["']?(.+?))?$""",
            RegexOption.IGNORE_CASE
        )
        emailRegex.find(lower)?.let { match ->
            val to = match.groupValues[1].trim()
            val subject = match.groupValues[2].trim().ifBlank { "Temat" }
            val body = match.groupValues[3].trim()
            if (to.isNotBlank()) {
                actions.add(Action.SendEmail(to = to, subject = subject, body = body))
            }
        }

        // === MUZYKA ===
        // "włącz muzykę" / "puść piosenkę X" / "odtwórz X"
        //
        // "w[lł]acz" (bez "ą") nie łapało poprawnie napisanego "włącz" -
        // ten sam błąd, co wcześniej w przełącznikach WiFi/Bluetooth/latarki.
        val playMusicRegex = Regex(
            """(?:w[lł][aą]cz|pusc|pu[sś]c|odtworz|odtwórz|zagraj)\s+""" +
                """(?:muzyke|muzykę|piosenk[ęe]|utwor|song|album)\s*["']?(.+?)?["']?$""",
            RegexOption.IGNORE_CASE
        )
        playMusicRegex.find(lower)?.let { match ->
            val query = match.groupValues[1].trim().ifBlank { "playlist" }
            actions.add(Action.PlayMusic(query = query))
        }

        // "pauza" / "wstrzymaj" / "zatrzymaj muzykę" / "wznów"
        if (lower.matches(Regex(""".*(pauz|stop|wstrzym|zatrzymaj.*muzyk|pauzuj).*"""))) {
            actions.add(Action.TogglePlayPause)
        }
        if (lower.matches(Regex(""".*(wzn[oó]w|kontynuuj|play).*"""))) {
            actions.add(Action.TogglePlayPause)
        }

        // "następna piosenka" / "skip" / "dalej"
        if (lower.matches(Regex(""".*(nast[eę]pna|nastepna|skip|dalej|przeskocz|next).*"""))) {
            actions.add(Action.SkipTrack(SkipDirection.NEXT))
        }
        if (lower.matches(Regex(""".*(poprzednia|cofn|wstecz|previous|prev).*"""))) {
            actions.add(Action.SkipTrack(SkipDirection.PREVIOUS))
        }

        // === NAWIGACJA ===
        // "nawiguj do X" / "prowadź do X" / "jedź do X"
        val navRegex = Regex(
            """(?:nawiguj|prowadz|prowadź|jedz|jedź|poprowadz|poprowadź)\s+do\s+["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        navRegex.find(lower)?.let { match ->
            val dest = match.groupValues[1].trim()
            if (dest.isNotBlank()) {
                actions.add(Action.Navigate(destination = dest))
            }
        }

        // === KALENDARZ ===
        // "dodaj do kalendarza spotkanie z Anną jutro o 15" / "umów spotkanie z
        // szefem w piątek o 10:30". Backend (GoogleCalendarService.createEvent)
        // istniał już wcześniej, ale nic po stronie głosu go nie wywoływało - patrz
        // CalendarEventParser dla parsowania dnia/godziny.
        pl.victor.app.actions.CalendarEventParser.parse(lower)?.let { parsed ->
            actions.add(
                Action.CreateCalendarEvent(
                    title = parsed.title,
                    startTimeMillis = parsed.startTimeMillis
                )
            )
        }

        // === ALARM ===
        // "ustaw alarm na 7 rano" / "alarm na 7:30"
        //
        // Grupa pory dnia dopasowuje zarówno wersję z polskimi znakami, jak
        // i bez nich (rozpoznawanie mowy nie zawsze je zwraca): "południe"
        // albo "poludnie", "północ" albo "polnoc". Wcześniejszy wzorzec
        // `p[oó]lnoc` (bez `ł`) nie pasował do poprawnie zapisanego
        // "północ" wcale - "północ" nigdy się nie dopasowywała.
        val alarmRegex = Regex(
            """(?:ustaw|postaw|nastaw)\s+alarm\s+na\s+(\d{1,2})(?::(\d{2}))?\s*""" +
                """(rano|wiecz[oó]r|po[lł]udnie|p[oó][lł]noc)?""",
            RegexOption.IGNORE_CASE
        )
        alarmRegex.find(lower)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            // Dopasowanie po dokładnej wartości grupy, nie po prefiksie:
            // "południe" i "północ" mają wspólny prefiks "poł", więc łańcuch
            // startsWith() myli jedno z drugim. Do tego && wiąże mocniej niż ||,
            // więc poprzedni warunek "startsWith("po") || startsWith("polu") && ..."
            // uruchamiał gałąź południa dla KAŻDEJ pory zaczynającej się na "po" -
            // w tym dla północy - bez względu na strażnika hour < 12.
            val period = match.groupValues[3].lowercase()
                .replace("ł", "l").replace("ó", "o")
            val adjustedHour = when {
                period == "wieczor" && hour < 12 -> hour + 12
                period == "poludnie" && hour < 12 -> hour + 12
                period == "polnoc" -> if (hour == 12) 0 else hour
                else -> hour
            }
            actions.add(Action.SetAlarm(hour = adjustedHour, minute = minute))
        }

        // === TIMER ===
        // "timer na 5 minut" / "odliczaj 10 sekund"
        val timerRegex = Regex(
            """(?:timer|odlicz[auj]|odliczaj)\s+(?:na\s+)?(\d+)\s*(minut|minut[yey]|sekund|sekundy|sek|godzin)?""",
            RegexOption.IGNORE_CASE
        )
        timerRegex.find(lower)?.let { match ->
            val value = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2].lowercase()
            val (minutes, seconds) = when {
                unit.startsWith("min") -> value to 0
                unit.startsWith("sek") -> 0 to value
                unit.startsWith("godz") -> value * 60 to 0
                else -> value to 0  // domyślnie minuty
            }
            actions.add(Action.SetTimer(minutes = minutes, seconds = seconds))
        }

        // === POKAŻ NA MAPIE ===
        // "pokaż na mapie X" / "gdzie jest X" / "znajdź na mapie X"
        // Odrębne od nawigacji: użytkownik chce zobaczyć miejsce, nie jechać.
        // Action.ShowOnMap była zaimplementowana w ActionExecutor, ale nic
        // jej nie tworzyło - komenda nie miała jak zadziałać.
        val mapRegex = Regex(
            """(?:pokaz|pokaż|znajdz|znajdź|wyswietl|wyświetl)\s+(?:mi\s+)?""" +
                """(?:na\s+mapie\s+|gdzie\s+jest\s+)?["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        val whereRegex = Regex(
            """(?:gdzie\s+(?:jest|znajduje\s+sie|znajduje\s+się))\s+["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        // "gdzie jest X" samo w sobie NIE otwiera map. W trybie dostępności
        // niewidomy użytkownik pyta "gdzie jest wyjście?" o to, co przed nim -
        // przekierowanie go wtedy do Google Maps byłoby wprost szkodliwe.
        // Mapy wchodzą w grę tylko przy jawnej wzmiance o mapie albo przy
        // "najbliższy", które nie ma sensu w pytaniu o widok.
        val explicitMap = lower.contains("mapie") || lower.contains("mapa")
        val looksLikePlaceSearch = Regex(
            """najbli(?:z|ż)sz""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)

        if (explicitMap || (whereRegex.containsMatchIn(lower) && looksLikePlaceSearch)) {
            val place = (whereRegex.find(lower) ?: mapRegex.find(lower))
                ?.groupValues?.get(1)
                ?.replace(Regex("""(?:^|\s)na\s+mapie(?:\s|$)""", RegexOption.IGNORE_CASE), " ")
                ?.trim()
            if (!place.isNullOrBlank() && actions.none { it is Action.Navigate }) {
                actions.add(Action.ShowOnMap(query = place))
            }
        }

        // === OTWÓRZ STRONĘ ===
        // "otwórz stronę X" / "wejdź na X" / albo sam adres w wypowiedzi.
        // Action.OpenUrl też była martwa - wykonawca ją obsługiwał, detektor nie.
        val urlInText = Regex(
            """\b((?:https?://|www\.)[^\s<>"']+|[a-z0-9-]+\.(?:pl|com|org|net|eu|io|dev)""" +
                """(?:/[^\s<>"']*)?)""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)

        // Sam czasownik wystarczy - "wejdź na example.com" jest równie naturalne
        // jak "wejdź na stronę example.com".
        //
        // Bez \b na końcu: w Javie granica słowa opiera się na [a-zA-Z0-9_],
        // więc po polskim „ź" żadnej granicy nie ma i „wejdź " nigdy by się
        // nie dopasowało. To cicha pułapka - regex wygląda poprawnie i milczy.
        val opensPage = Regex(
            """(?:^|\s)(?:otworz|otwórz|wejdz|wejdź|odwiedz|odwiedź)(?:\s|$)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)

        val isBareUrl = lower.trim() == urlInText?.lowercase()

        if (urlInText != null && (opensPage || isBareUrl) &&
            actions.none { it is Action.ShowOnMap }
        ) {
            val url = if (urlInText.startsWith("http", ignoreCase = true)) urlInText
                else "https://$urlInText"
            actions.add(Action.OpenUrl(url = url))
        }

        // === WYSZUKIWANIE ===
        // "wyszukaj X" / "szukaj X" / "google X"
        val searchRegex = Regex(
            """(?:wyszukaj|szukaj|google|search)\s+["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE
        )
        searchRegex.find(lower)?.let { match ->
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) {
                actions.add(Action.WebSearch(query = query))
            }
        }

        // === OTWÓRZ APKĘ ===
        // "otwórz Spotify" / "uruchom Gmail" / "włącz Spotify"
        val openAppRegex = Regex(
            """(?:otworz|otwórz|uruchom|w[lł][aą]cz)\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        openAppRegex.find(lower)?.let { match ->
            val appName = match.groupValues[1].trim().lowercase()
            val appMap = mapOf(
                "spotify" to "com.spotify.music",
                "youtube" to "com.google.android.youtube",
                "mapy" to "com.google.android.apps.maps",
                "google maps" to "com.google.android.apps.maps",
                "gmail" to "com.google.android.gm",
                "mail" to "com.google.android.gm",
                "whatsapp" to "com.whatsapp",
                "telegram" to "org.telegram.messenger",
                "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana",
                "netflix" to "com.netflix.mediaclient",
                "uber" to "com.uber",
                "amazon" to "com.amazon.mShop.android.shopping",
                "kalendarz" to "com.google.android.calendar",
                "calendar" to "com.google.android.calendar",
                "notatki" to "com.google.android.keep",
                "keep" to "com.google.android.keep"
            )
            appMap[appName]?.let { pkg ->
                actions.add(Action.OpenApp(packageName = pkg, appName = appName))
            }
        }

        // === TŁUMACZENIE ===
        // "przetłumacz X na angielski"
        val translateRegex = Regex(
            """(?:przet[lł]umacz|translate|przetlumacz)\s+["']?(.+?)["']?\s+na\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        translateRegex.find(lower)?.let { match ->
            val text = match.groupValues[1].trim()
            val lang = match.groupValues[2].trim()
            actions.add(Action.Translate(text = text, targetLang = lang))
        }

        // === SYSTEM ===
        // "Wyłącz" (z nosowym ą) to jedyna poprawna polska pisownia, a wzorzec
        // "wy[lł]acz" (literalne "a", nie "ą") jej nie łapał - łapał tylko
        // "wyłacz"/"wylacz" bez diakrytyków. Poprawione na "wy[lł][aą]cz"
        // wszędzie niżej.
        if (lower.matches(Regex(""".*(wlacz|włącz).*wifi.*"""))) {
            actions.add(Action.ToggleWifi(enabled = true))
        }
        if (lower.matches(Regex(""".*(wylacz|wy[lł][aą]cz).*wifi.*"""))) {
            actions.add(Action.ToggleWifi(enabled = false))
        }
        if (lower.matches(Regex(""".*(wlacz|włącz).*bluetooth.*"""))) {
            actions.add(Action.ToggleBluetooth(enabled = true))
        }
        // Brakowało tej gałęzi - "wyłącz bluetooth" nigdy nie było wykrywane,
        // mimo że WiFi i latarka mają obie strony (włącz/wyłącz).
        if (lower.matches(Regex(""".*(wylacz|wy[lł][aą]cz).*bluetooth.*"""))) {
            actions.add(Action.ToggleBluetooth(enabled = false))
        }
        // `|` w regexie ma najniższy priorytet - wiąże CAŁE wyrażenie po obu
        // stronach, nie tylko "latarke". Wzorzec
        // ".*(wlacz|włącz).*latarke|latark[ęe].*" to w praktyce DWIE osobne
        // alternatywy: ".*(wlacz|włącz).*latarke" (bez "ę") ORAZ
        // "latark[ęe].*" (musi zaczynać się od "latark..." - matches()
        // wymaga dopasowania całego tekstu). "Włącz latarkę" z poprawnym "ę"
        // nie trafiało w żadną z nich. Grupa musi obejmować obie pisownie
        // w jednym miejscu.
        if (lower.matches(Regex(""".*(wlacz|włącz).*latark[ęe].*"""))) {
            actions.add(Action.ToggleFlashlight(enabled = true))
        }
        if (lower.matches(Regex(""".*(wylacz|wy[lł][aą]cz).*latark[ęe].*"""))) {
            actions.add(Action.ToggleFlashlight(enabled = false))
        }

        // === Specjalny marker z AI: [[ACTION: type=send_sms to="Ania" body="cześć"]] ===
        val aiActionRegex = Regex("""\[\[ACTION:\s*type=(\w+)(?:\s+(\w+)=["']?(.+?)["']?)*\]\]""")
        aiActionRegex.findAll(text).forEach { match ->
            val type = match.groupValues[1]
            val params = mutableMapOf<String, String>()
            for (i in 2 until match.groupValues.size - 1 step 2) {
                val key = match.groupValues[i]
                val value = match.groupValues[i + 1]
                if (key.isNotBlank() && value.isNotBlank()) {
                    params[key] = value
                }
            }
            parseAiAction(type, params)?.let { actions.add(it) }
        }

        // === Accessibility (niewidomi) ===
        if (matchesAny(lower, "przeczytaj", "czytaj to", "co tu pisze", "odczytaj")) {
            actions.add(Action.ReadText)
        }
        if (matchesAny(lower, "co przede mną", "co widzisz", "opisz", "co jest przed", "co tam")) {
            actions.add(Action.DescribeScene)
        }
        if (matchesAny(lower, "prowadź", "nawiguj", "idź ze mną", "idziemy")) {
            actions.add(Action.StartNavigation)
        }
        if (matchesAny(lower, "stop czytanie", "stop opis", "zatrzymaj tryb", "wyłącz tryb")) {
            actions.add(Action.StopAccessibility)
        }

        if (actions.isNotEmpty()) {
            Log.d(tag, "Detected ${actions.size} action(s): ${actions.map { it.type }}")
        }
        return actions
    }

    private fun matchesAny(text: String, vararg phrases: String): Boolean {
        return phrases.any { text.contains(it) }
    }

    /**
     * Wykrywa akcje oznaczone przez AI znacznikiem `[[ACTION: ...]]` w JEGO
     * własnej odpowiedzi - patrz [AI_ACTION_CAPABILITIES_PROMPT], który tłumaczy
     * modelowi ten format.
     *
     * Celowo OSOBNA od [detect]: [detect] dopasowuje luźne frazy ("włącz wifi")
     * w dowolnym tekście, co ma sens dla tego, co powiedział user, ale odpalone
     * na swobodnej prozie AI ryzykowałoby przypadkowe trafienie (AI wspominające
     * "włączyłbym WiFi" w wyjaśnieniu, nie jako instrukcję). Sam znacznik
     * `[[ACTION: ...]]` jest jednoznaczny - bezpiecznie skanować nim całą
     * odpowiedź AI.
     *
     * @return oczyszczony tekst (bez znaczników - user go nie widzi/słyszy)
     *         razem z wykrytymi akcjami
     */
    fun detectAiMarkedActions(text: String): Pair<String, List<Action>> {
        val actions = mutableListOf<Action>()
        val cleaned = AI_ACTION_TAG_REGEX.replace(text) { match ->
            val type = match.groupValues[1]
            val params = mutableMapOf<String, String>()
            for (i in 2 until match.groupValues.size - 1 step 2) {
                val key = match.groupValues[i]
                val value = match.groupValues[i + 1]
                if (key.isNotBlank() && value.isNotBlank()) {
                    params[key] = value
                }
            }
            parseAiAction(type, params)?.let { actions.add(it) }
            ""
        }
        return cleaned.trim() to actions
    }

    private fun parseAiAction(type: String, params: Map<String, String>): Action? {
        return when (type) {
            "send_sms" -> params["to"]?.let { to ->
                Action.SendSms(to = to, body = params["body"] ?: "")
            }
            "make_call" -> params["to"]?.let { Action.MakeCall(to = it) }
            "send_email" -> params["to"]?.let { to ->
                Action.SendEmail(
                    to = to,
                    subject = params["subject"] ?: "",
                    body = params["body"] ?: ""
                )
            }
            "play_music" -> Action.PlayMusic(query = params["query"] ?: "")
            "navigate" -> params["destination"]?.let { Action.Navigate(destination = it) }
            "set_alarm" -> {
                val hour = params["hour"]?.toIntOrNull() ?: return null
                val minute = params["minute"]?.toIntOrNull() ?: 0
                Action.SetAlarm(hour = hour, minute = minute, label = params["label"] ?: "")
            }
            "set_timer" -> {
                val minutes = params["minutes"]?.toIntOrNull() ?: return null
                Action.SetTimer(minutes = minutes, seconds = params["seconds"]?.toIntOrNull() ?: 0)
            }
            "web_search" -> params["query"]?.let { Action.WebSearch(query = it) }
            "translate" -> params["text"]?.let {
                Action.Translate(text = it, targetLang = params["target"] ?: "en")
            }
            "open_app" -> params["package"]?.let {
                Action.OpenApp(packageName = it, appName = params["name"] ?: "")
            }
            else -> null
        }
    }

    companion object {
        /** Format tagu, patrz [detectAiMarkedActions] i [AI_ACTION_CAPABILITIES_PROMPT]. */
        private val AI_ACTION_TAG_REGEX =
            Regex("""\[\[ACTION:\s*type=(\w+)(?:\s+(\w+)=["']?(.+?)["']?)*\]\]""")

        /**
         * Dopisywane do system promptu AI (patrz [pl.victor.app.AIOrchestrator]), żeby
         * model wiedział o formacie tagu z [detectAiMarkedActions]. `open_app` celowo
         * pominięty - nazwa pakietu Androida to coś, co model łatwo zgaduje błędnie,
         * zostaje dostępne tylko przez wzorce w [detect].
         */
        const val AI_ACTION_CAPABILITIES_PROMPT = """
=== WYKONYWANIE AKCJI ===
Jeśli user prosi Cię o wykonanie jednej z poniższych czynności, zakończ swoją
odpowiedź (po naturalnej, mówionej części) znacznikiem w DOKŁADNIE tym
formacie, w nowej linii:
[[ACTION: type=TYP klucz1="wartość1" klucz2="wartość2"]]

Dostępne typy i klucze:
- send_sms: to (numer lub imię), body (treść)
- make_call: to (numer lub imię)
- send_email: to (adres), subject (temat), body (treść)
- play_music: query (czego szukać)
- navigate: destination (adres lub miejsce)
- set_alarm: hour, minute (opcjonalnie), label (opcjonalnie)
- set_timer: minutes, seconds (opcjonalnie)
- web_search: query
- translate: text, target (kod języka, np. "en")

Zasady:
- Znacznika użyj TYLKO gdy user faktycznie prosi o wykonanie czynności - nie
  przy zwykłych pytaniach ani gdy tylko o czymś wspominasz.
- Znacznik nie jest widoczny ani słyszalny dla użytkownika - to co mówisz
  PRZED nim powinno brzmieć naturalnie i kompletnie (np. "Wysyłam maila do
  Jana z informacją o spóźnieniu.").
- Nigdy nie wymyślaj wartości takich jak numer telefonu czy adres email,
  jeśli user ich nie podał - dopytaj, zamiast zgadywać.
- Zanim cokolwiek wykonasz, user i tak zobaczy prośbę o potwierdzenie w
  aplikacji - to nie jest ostateczna decyzja, tylko propozycja.
"""
    }
}
