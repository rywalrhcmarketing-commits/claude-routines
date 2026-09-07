package pl.victor.app.ai

/**
 * Czy pytanie o obraz wymaga SZCZEGÓŁU, czy wystarczy ogólny widok.
 *
 * ## Po co to w ogóle istnieje
 * Zdjęcia z okularów idą domyślnie jako miniatura po BLE - kilkadziesiąt
 * kilobajtów, kilka razy mniejsza rozdzielczość niż to, co naprawdę robi
 * aparat. Do "co przede mną jest" to w zupełności wystarcza i jest szybkie.
 * Do "przeczytaj, co tu pisze" - nie wystarcza w ogóle: liter z bliska po
 * prostu nie widać, a model zgaduje albo mówi, że tekst jest nieczytelny.
 * Zgłoszone dokładnie tak: "AI chyba nadal dostaje miniaturki zdjęć, bo nie
 * może rozczytać liter z bliska".
 *
 * Pełna rozdzielczość wymaga Wi-Fi Direct, a zestawienie grupy P2P to
 * kilkanaście sekund. Dlatego NIE włączamy jej zawsze - tylko wtedy, gdy
 * pytanie faktycznie jej potrzebuje. Ta decyzja jest czystą funkcją, żeby
 * dało się ją sprawdzić testem, a nie warunkiem schowanym w orkiestratorze.
 */
object VisionDetail {

    /**
     * Czy do tego pytania trzeba zdjęcia w pełnej rozdzielczości.
     *
     * @param question pytanie użytkownika (albo polecenie z przycisku)
     */
    fun needsDetail(question: String): Boolean {
        val lower = question.lowercase()
        return STEMS.any { lower.contains(it) }
    }

    /**
     * Rdzenie słów, po których wiadomo, że chodzi o czytanie albo o drobny
     * szczegół. Rdzenie, nie całe słowa - polska odmiana zjadłaby połowę
     * trafień ("etykiety", "etykietce", "z etykietą").
     */
    private val STEMS = listOf(
        // czytanie wprost
        "przeczytaj", "przeczytać", "odczytaj", "odczytać", "czytaj",
        "co tu pisze", "co jest napisane", "napisane", "napis",
        "tekst", "liter", "czcionk", "drobnym drukiem", "drobny druk",
        // rzeczy, które czyta się z bliska
        "etykie", "skład", "instrukcj", "ulotk", "opakowani",
        "menu", "karta dań", "paragon", "rachunek", "faktur", "umow",
        "dokument", "formularz", "recept", "dawkowani",
        "ważnoś", "przydatnoś", "spożyc",
        "kod kreskow", "kod qr", "numer seryjn", "seria i numer",
        "tablicz", "szyld", "cennik", "cena", "ceny", "cenę", "cenie", "kosztuje",
        "rozkład jazdy", "godziny otwarcia",
        // ekrany i wydruki
        "ekranie", "wyświetlacz", "monitor", "wydruk", "gazet", "książk"
    )
}
