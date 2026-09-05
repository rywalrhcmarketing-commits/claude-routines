package pl.victor.app.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.victor.app.ble.VictorManager
import java.io.ByteArrayOutputStream

/**
 * Zbiera dźwięk z mikrofonu okularów przesyłany po BLE i składa z niego WAV.
 *
 * ## Skąd to się bierze
 * Aplikacja producenta nie używa mikrofonu okularów przez profil zestawu
 * słuchawkowego. Odbiera pakiety `AiChatResponse` po BLE, dekoduje je jako Opus
 * i podaje prosto do rozpoznawania mowy. Tutaj jest to samo, z jedną różnicą:
 * zamiast do rozpoznawania mowy PCM idzie do modelu multimodalnego jako WAV -
 * model i tak potrafi wysłuchać pytania i od razu na nie odpowiedzieć, więc
 * jeden krok mniej i jedna rzecz mniej do zepsucia.
 *
 * ## Dlaczego to nie jest ścieżka domyślna
 * Bo nie wiadomo z góry, czy dany egzemplarz w ogóle nadaje tym kanałem i czy
 * `subData` to goły pakiet Opusa. Klasa jest napisana tak, żeby jej
 * niepowodzenie NIC nie psuło: gdy nie przyjdzie ani jeden pakiet albo dekoder
 * odmówi, [stop] zwraca wynik z zerami, a wołający leci swoją dotychczasową
 * drogą (klasyczny Bluetooth albo mikrofon telefonu).
 *
 * [Result.describe] mówi wprost, na czym stanęło - łącznie z kształtem
 * pierwszego pakietu, bo to jedyna rzecz, której nie da się ustalić bez sprzętu.
 */
class GlassesVoiceCapture(private val glasses: VictorManager) {

    private val decoder = OpusDecoder()

    /**
     * Surowe pakiety odłożone do rozkodowania PO zakończeniu nagrania.
     *
     * ## Dlaczego nie dekodujemy na bieżąco
     * `onPacket` przychodzi z wątku obsługi BLE. Dekodowanie potrafi na nim
     * zaczekać - a przy zgadywaniu ramkowania nawet 150 ms na wariant, cztery
     * warianty, osiem pakietów. Zablokowanie wątku BLE na kilka sekund to nie
     * jest "wolniej": to gubione pakiety i zrywane połączenie z okularami.
     * Odkładanie kosztuje pamięć, której nie ma czego żałować - Opus przy
     * 48 kHz to jakieś 8 kB na sekundę, a nagranie trwa kilkanaście sekund.
     */
    private val packets = mutableListOf<ByteArray>()
    private val lock = Any()

    private var decodedPackets = 0
    private var failedPackets = 0

    /**
     * O ile bajtów od początku pakietu zaczyna się ładunek Opusa.
     *
     * `-1` znaczy "jeszcze nie wiem". Producent może opakowywać dane własnym
     * nagłówkiem (numer sekwencji, długość), a tego nie da się ustalić inaczej
     * niż empirycznie - dekoder albo przyjmie ładunek, albo nie. Sprawdzamy
     * więc kilka typowych przesunięć na pierwszych pakietach i zapamiętujemy to,
     * które zadziałało. Zgadywanie kosztuje ułamek sekundy raz na nagranie, a
     * bez niego cała ścieżka po BLE stoi lub upada na jednym założeniu.
     */
    private var payloadOffset = -1
    private var probeAttempts = 0
    private var startedAtMs = 0L
    private var active = false

    /**
     * Podpina się pod strumień i zaczyna dekodować.
     *
     * @return `false`, gdy urządzenie nie ma dekodera Opusa - wtedy i tak
     *   zbieramy statystyki pakietów, bo one same w sobie są odpowiedzią na
     *   pytanie "czy okulary nadają"
     */
    fun start(): Boolean = synchronized(lock) {
        if (active) return true
        packets.clear()
        decodedPackets = 0
        failedPackets = 0
        payloadOffset = -1
        probeAttempts = 0
        startedAtMs = System.currentTimeMillis()
        active = true

        val decoderOk = decoder.start()
        glasses.resetMicStreamStats()
        glasses.startGlassesMicStream { packet -> onPacket(packet) }
        decoderOk
    }

    /**
     * Odpina się od strumienia, NIC nie dekodując.
     *
     * Musi być zwykłą funkcją, nie `suspend`: wołający sprząta po turze w bloku
     * `finally`, a ten wykonuje się także po ANULOWANIU. Wywołanie funkcji
     * zawieszalnej w anulowanej korutynie natychmiast rzuca - subskrypcja BLE
     * zostałaby wtedy zarejestrowana na zawsze, bo nikt by jej nie zdjął.
     * Przerwana tura nie ma czego dekodować, więc to wystarcza.
     */
    fun detach() {
        detachAndTake()
    }

    /** Zdejmuje subskrypcję i oddaje zebrane pakiety; `null`, gdy już nieaktywne. */
    private fun detachAndTake(): List<ByteArray>? {
        val collected: List<ByteArray>
        synchronized(lock) {
            if (!active) return null
            active = false
            collected = packets.toList()
            packets.clear()
        }
        runCatching { glasses.stopGlassesMicStream() }
            .onFailure { Log.w(TAG, "Odpięcie strumienia nie powiodło się", it) }
        return collected
    }

    /** Wołane z wątku BLE - musi być szybkie, więc tylko odkłada pakiet. */
    private fun onPacket(packet: ByteArray) {
        synchronized(lock) {
            if (!active) return
            if (packets.size >= MAX_BUFFERED_PACKETS) return
            packets.add(packet.copyOf())
        }
    }

    /**
     * Dekoduje pakiet, po drodze ustalając, gdzie w nim zaczyna się Opus.
     *
     * Gdy przesunięcie jest już znane, to zwykłe wywołanie dekodera. Gdy nie -
     * próbuje kilku typowych i zapamiętuje pierwsze, które dało dźwięk. Po
     * nieudanej próbie dekoder trzeba wyczyścić, bo śmieciowy pakiet potrafi
     * zostawić go w stanie odrzucającym także poprawne dane.
     */
    private fun decodeWithKnownOrGuessedOffset(packet: ByteArray, timeUs: Long): ByteArray? {
        if (payloadOffset >= 0) {
            if (packet.size <= payloadOffset) return null
            val body = if (payloadOffset == 0) packet
                else packet.copyOfRange(payloadOffset, packet.size)
            return decoder.decode(body, timeUs)
        }
        if (probeAttempts >= MAX_PROBE_PACKETS) return null
        probeAttempts++

        for (offset in CANDIDATE_OFFSETS) {
            if (packet.size <= offset + MIN_OPUS_PAYLOAD) continue
            val body = if (offset == 0) packet else packet.copyOfRange(offset, packet.size)
            // Dłuższy limit na wyjście: dekoder dopiero się rozkręca, a krótkie
            // czekanie odrzuciłoby POPRAWNE przesunięcie tylko dlatego, że
            // pierwsza porcja PCM nie zdążyła wyjść.
            val decoded = decoder.decode(body, timeUs, OpusDecoder.PROBE_TIMEOUT_US)
            if (decoded != null) {
                payloadOffset = offset
                Log.i(TAG, "Ładunek Opusa zaczyna się o $offset B od początku pakietu")
                return decoded
            }
            // Podaliśmy dekoderowi śmieci - to normalny etap zgadywania, ale
            // trzeba go z tego wyprowadzić, zanim spróbujemy następnego wariantu.
            if (!decoder.recover()) return null
        }
        return null
    }

    /**
     * Odpina się od strumienia, rozkodowuje odłożone pakiety i zwraca wynik.
     *
     * Całe dekodowanie dzieje się TUTAJ, a nie w trakcie nagrywania - patrz
     * [packets]. Wołający jest korutyną, więc idzie na wątek roboczy: kilka
     * sekund dźwięku to kilkaset milisekund pracy, której nie chcemy na wątku
     * głównym ani tym bardziej na wątku BLE.
     */
    suspend fun stop(): Result = withContext(Dispatchers.Default) {
        val collected = detachAndTake() ?: return@withContext Result()

        val pcm = ByteArrayOutputStream()
        collected.forEach { packet ->
            // Znacznik czasu liczony z już zebranego PCM, nie z zegara: dekoder
            // oczekuje ciągłej osi czasu strumienia, a nie momentu odbioru
            // pakietu (BLE potrafi je dostarczyć nierówno).
            val timeUs = pcm.size().toLong() * 1_000_000L /
                (OpusDecoder.SAMPLE_RATE.toLong() * BYTES_PER_SAMPLE)
            val decoded = if (decoder.isReady) {
                decodeWithKnownOrGuessedOffset(packet, timeUs)
            } else {
                null
            }
            if (decoded != null) {
                pcm.write(decoded)
                decodedPackets++
            } else {
                failedPackets++
            }
        }
        decoder.release()

        val samples = pcm.toByteArray()
        Result(
            packets = collected.size,
            bytes = collected.sumOf { it.size },
            decodedPackets = decodedPackets,
            failedPackets = failedPackets,
            packetSizes = collected.map { it.size },
            firstPacketHex = collected.firstOrNull()?.let { hex(it, HEX_PREVIEW_BYTES) },
            pcmBytes = samples.size,
            wav = if (samples.isEmpty()) null else WavWriter.wrap(samples, OpusDecoder.SAMPLE_RATE),
            durationMs = System.currentTimeMillis() - startedAtMs,
            payloadOffset = payloadOffset
        ).also { Log.i(TAG, it.describe()) }
    }

    /**
     * Co przyszło z okularów i co się z tym udało zrobić.
     *
     * Rozdzielenie `packets` od `decodedPackets` jest tu najważniejsze: to
     * właśnie ta para odróżnia "okulary nie nadają" od "nadają, ale to nie jest
     * goły Opus" - dwie awarie, które bez pomiaru wyglądają identycznie.
     */
    data class Result(
        val packets: Int = 0,
        val bytes: Int = 0,
        val decodedPackets: Int = 0,
        val failedPackets: Int = 0,
        val packetSizes: List<Int> = emptyList(),
        val firstPacketHex: String? = null,
        val pcmBytes: Int = 0,
        val wav: ByteArray? = null,
        val durationMs: Long = 0L,
        /** Gdzie w pakiecie zaczyna się Opus; `-1`, gdy nie udało się ustalić. */
        val payloadOffset: Int = -1
    ) {
        /** Czy jest z czego zrobić pytanie do modelu. */
        val hasAudio: Boolean get() = wav != null && pcmBytes >= MIN_USEFUL_PCM_BYTES

        val audioSeconds: Double
            get() = WavWriter.durationSeconds(pcmBytes, OpusDecoder.SAMPLE_RATE)

        fun describe(): String = buildString {
            if (packets == 0) {
                append("Okulary nie przysłały ani jednego pakietu audio po BLE.")
                return@buildString
            }
            append("Pakiety: ").append(packets).append(" (").append(bytes).append(" B)")
            val distinct = packetSizes.distinct().sorted()
            if (distinct.size <= SIZES_IN_SUMMARY) {
                append(", rozmiary: ").append(distinct.joinToString("/"))
            } else {
                append(", rozmiary ").append(distinct.first()).append("-").append(distinct.last())
            }
            append('\n')
            firstPacketHex?.let { append("Pierwszy pakiet: ").append(it).append('\n') }
            when {
                decodedPackets == 0 ->
                    append("Dekoder Opusa nie przyjął ANI JEDNEGO pakietu - to nie jest ")
                        .append("goły strumień Opusa albo urządzenie nie ma dekodera.")
                failedPackets > 0 ->
                    append("Rozkodowano ").append(decodedPackets).append(" z ")
                        .append(packets).append(" pakietów; ")
                        .append("%.1f".format(audioSeconds)).append(" s dźwięku.")
                else ->
                    append("Rozkodowano wszystko: ").append("%.1f".format(audioSeconds))
                        .append(" s dźwięku (").append(pcmBytes).append(" B PCM).")
            }
            if (payloadOffset > 0) {
                append("\nPakiety mają ").append(payloadOffset)
                    .append("-bajtowy nagłówek producenta przed Opusem.")
            }
        }
    }

    private fun hex(bytes: ByteArray, limit: Int): String =
        bytes.take(limit).joinToString(" ") { "%02X".format(it) } +
            if (bytes.size > limit) " ..." else ""

    companion object {
        private const val TAG = "GlassesVoiceCapture"
        private const val BYTES_PER_SAMPLE = 2
        private const val HEX_PREVIEW_BYTES = 16
        private const val SIZES_IN_SUMMARY = 6

        /**
         * Poniżej tego nie ma sensu wysyłać nagrania do modelu - 0,3 s to nawet
         * nie jedno słowo, a zapytanie i tak kosztuje.
         */
        private const val MIN_USEFUL_PCM_BYTES = 28_800

        /**
         * Przesunięcia ładunku, których warto spróbować: goły pakiet, bajt typu
         * lub numeru sekwencji, dwubajtowa długość, czterobajtowy nagłówek.
         */
        private val CANDIDATE_OFFSETS = intArrayOf(0, 1, 2, 4)

        /** Po tylu pakietach bez trafienia przestajemy zgadywać. */
        private const val MAX_PROBE_PACKETS = 8

        /** Krótszy ładunek nie jest sensownym pakietem Opusa - nie ma czego próbować. */
        private const val MIN_OPUS_PAYLOAD = 4

        /**
         * Sufit na odłożone pakiety - około minuty dźwięku.
         *
         * Nagranie trwa najwyżej kilkanaście sekund, więc tego limitu nie da się
         * osiągnąć w normalnej pracy. Jest po to, żeby zawieszony strumień (bo
         * okulary zapomniały przestać nadawać) nie zjadł pamięci telefonu.
         */
        private const val MAX_BUFFERED_PACKETS = 3_000
    }
}
