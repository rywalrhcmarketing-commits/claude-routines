package pl.victor.app.ble

/**
 * Dlaczego telefon nie dołączył do sieci Wi-Fi Direct okularów - po ludzku.
 *
 * ## Po co osobna klasa
 * Bo poprzednie komunikaty zgadywały. Galeria mówiła "podejdź bliżej" albo
 * "okulary nie postawiły swojej sieci" niezależnie od tego, co się naprawdę
 * stało - a najczęstsze przyczyny leżą w USTAWIENIACH TELEFONU i odległość nie
 * ma z nimi nic wspólnego:
 *
 * - wyłączone Wi-Fi (samo Wi-Fi Direct nie włącza radia),
 * - wyłączona systemowa Lokalizacja - na Androidzie 12 i starszym wykrywanie
 *   urządzeń Wi-Fi jest pod nią podpięte i bez niej `discoverPeers` po prostu
 *   NIC nie zwraca, mimo przyznanych uprawnień,
 * - framework P2P zajęty poprzednią, nieposprzątaną próbą.
 *
 * Zgłoszone jako "Galeria nie działa - nie łączy się po wifi z okularami".
 *
 * Wszystko tutaj to czyste funkcje, żeby dało się je sprawdzić testem - stanu
 * radia na maszynie budującej nie ma.
 */
object WifiDirectDiagnosis {

    /** Kody z [android.net.wifi.p2p.WifiP2pManager.ActionListener.onFailure]. */
    const val ERROR = 0
    const val P2P_UNSUPPORTED = 1
    const val BUSY = 2

    /** Od tej wersji Androida wykrywanie urządzeń Wi-Fi nie potrzebuje Lokalizacji. */
    const val NEARBY_WIFI_SDK = 33

    /**
     * Czy przed szukaniem trzeba mieć włączoną systemową Lokalizację.
     *
     * Uprawnienie to nie to samo co włączony przełącznik: do Androida 12
     * `discoverPeers` z przyznanym ACCESS_FINE_LOCATION, ale zgaszoną
     * Lokalizacją, kończy się pustą listą bez żadnego błędu.
     */
    fun needsLocationOn(sdkInt: Int): Boolean = sdkInt < NEARBY_WIFI_SDK

    /**
     * Sprawdza warunki, które da się sprawdzić PRZED szukaniem.
     *
     * @return zdanie dla użytkownika albo `null`, gdy nic nie stoi na
     *   przeszkodzie i można szukać
     */
    fun preflight(
        p2pAvailable: Boolean,
        wifiEnabled: Boolean,
        locationEnabled: Boolean,
        sdkInt: Int
    ): String? = when {
        !p2pAvailable ->
            "Ten telefon nie obsługuje Wi-Fi Direct, więc nie pobiorę z okularów " +
                "wideo ani zdjęć w pełnej rozdzielczości. Miniatury po Bluetooth działają."
        !wifiEnabled ->
            "Wi-Fi jest wyłączone. Okulary rozdają pliki własną siecią Wi-Fi - " +
                "włącz Wi-Fi w telefonie i spróbuj ponownie. Nie musisz się do niczego " +
                "logować."
        needsLocationOn(sdkInt) && !locationEnabled ->
            "Włącz Lokalizację w ustawieniach telefonu. Na tej wersji Androida bez niej " +
                "telefon nie pokazuje żadnych urządzeń Wi-Fi w pobliżu - także okularów. " +
                "Po pobraniu plików możesz ją z powrotem wyłączyć."
        else -> null
    }

    /**
     * Tłumaczy kod odmowy z frameworka P2P.
     *
     * @param reason kod z `ActionListener.onFailure`
     */
    fun discoveryRefused(reason: Int): String = when (reason) {
        P2P_UNSUPPORTED ->
            "Android odmówił szukania urządzeń Wi-Fi Direct - ten telefon go nie obsługuje."
        BUSY ->
            "Wi-Fi Direct jest zajęte poprzednim połączeniem. Odczekaj chwilę i spróbuj " +
                "jeszcze raz; jeśli wraca, pomaga wyłączenie i włączenie Wi-Fi."
        else ->
            "Android odmówił szukania urządzeń Wi-Fi Direct. Wyłącz i włącz Wi-Fi, " +
                "potem spróbuj ponownie."
    }

    /**
     * Co powiedzieć, gdy szukanie się udało, ale okularów wśród wyników nie ma.
     *
     * @param seen nazwy urządzeń, które telefon zobaczył
     */
    fun nothingFound(seen: List<String>): String = if (seen.isEmpty()) {
        "Telefon nie widzi w pobliżu ŻADNEJ sieci Wi-Fi Direct - także okularów. " +
            "Okulary stawiają ją dopiero na żądanie i tylko wtedy, gdy nic nie nagrywają. " +
            "Zakończ nagrywanie, odczekaj chwilę i spróbuj ponownie."
    } else {
        "Nie znalazłem sieci okularów. Telefon widzi w pobliżu: " +
            seen.joinToString(", ") + ". Sprawdź, czy okulary są włączone i w zasięgu."
    }
}
