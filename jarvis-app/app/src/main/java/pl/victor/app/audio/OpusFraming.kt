package pl.victor.app.audio

/**
 * Ramkowanie strumienia Opusa z okularów - czyste funkcje, bez Androida.
 *
 * ## Problem, który to rozwiązuje
 * Okulary nadają po BLE pakiety o długości do ~244 bajtów, ale Opus wewnątrz
 * nich jest pocięty na kawałki STAŁEJ długości. Oficjalna aplikacja producenta
 * dekoduje ten strumień ustawieniami `hasHead=false, packetSize=40` - czyli
 * surowe 40-bajtowe pakiety, bez żadnego nagłówka.
 *
 * Jeden pakiet BLE mieści więc kilka pakietów Opusa sklejonych jeden za drugim.
 * Podany dekoderowi w całości, taki blok rozkodowuje się TYLKO DO PIERWSZEGO
 * pakietu - reszta przepada bez błędu. Z każdej sekundy mowy zostaje ułamek, a
 * model dostaje nagranie, w którym nie da się usłyszeć pytania.
 *
 * Wydzielone z [GlassesVoiceCapture], bo to jedyna część tej ścieżki, którą da
 * się sprawdzić testem bez sprzętu i bez systemowego dekodera.
 */
object OpusFraming {

    /** Brak stałego ramkowania - pakiet BLE niesie dokładnie jeden pakiet Opusa. */
    const val NO_FIXED_SIZE = -1

    /**
     * Kandydaci na stały rozmiar pakietu Opusa, od najbardziej prawdopodobnego.
     *
     * 40 bajtów to wartość używana przez oficjalną aplikację producenta.
     * Pozostałe są na wypadek innego wariantu firmware'u - kolejność decyduje
     * przy remisie, więc idą od najczęstszej.
     */
    private val CANDIDATE_SIZES = intArrayOf(40, 80, 60, 20, 120, 160)

    /**
     * Najmniejszy sensowny pakiet Opusa. Poniżej tego progu "wspólny dzielnik"
     * jest przypadkiem, a nie ramkowaniem.
     */
    private const val MIN_PACKET_BYTES = 16

    /**
     * Zgaduje stałą długość pakietu Opusa na podstawie długości ładunków.
     *
     * ## Dlaczego przez największy wspólny dzielnik
     * Bo błędne dzielenie psuje dźwięk gorzej niż jego brak, a sama lista
     * kandydatów tego nie pilnuje: przy samych 40-bajtowych ładunkach kandydat
     * "20" dzieli je równo i pociąłby KAŻDY pakiet Opusa na pół. NWD jest
     * górnym ograniczeniem na rozmiar pakietu - żaden pakiet nie może być
     * większy - więc najpierw liczymy jego, a dopiero potem szukamy wśród
     * kandydatów tego, który go dzieli. Lista rozstrzyga remisy w stronę
     * wartości znanej z oficjalnej aplikacji (40 B).
     *
     * Dzielimy tylko wtedy, gdy przynajmniej jeden ładunek jest DŁUŻSZY od
     * wyliczonego pakietu. Inaczej każdy pakiet BLE i tak niesie jeden pakiet
     * Opusa, dzielenie niczego nie zmienia, a wpis w raporcie byłby mylący.
     *
     * @param bodyLengths długości ładunków (już bez ewentualnego nagłówka)
     * @return długość pakietu albo [NO_FIXED_SIZE]
     */
    fun detectPacketSize(bodyLengths: List<Int>): Int {
        val bodies = bodyLengths.filter { it > 0 }
        if (bodies.isEmpty()) return NO_FIXED_SIZE

        val common = bodies.reduce { a, b -> gcd(a, b) }
        if (common < MIN_PACKET_BYTES) return NO_FIXED_SIZE
        if (bodies.none { it > common }) return NO_FIXED_SIZE

        return CANDIDATE_SIZES.firstOrNull { common % it == 0 } ?: common
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /**
     * Tnie ładunek na pakiety Opusa.
     *
     * Gdy [packetSize] jest nieznany albo nie dzieli ładunku równo, zwraca
     * ładunek w całości - czyli zachowuje się jak przed wprowadzeniem podziału.
     */
    fun split(body: ByteArray, packetSize: Int): List<ByteArray> {
        if (body.isEmpty()) return emptyList()
        if (packetSize <= 0 || body.size <= packetSize || body.size % packetSize != 0) {
            return listOf(body)
        }
        return (body.indices step packetSize).map { start ->
            body.copyOfRange(start, start + packetSize)
        }
    }
}
