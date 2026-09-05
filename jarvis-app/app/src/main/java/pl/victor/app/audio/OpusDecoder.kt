package pl.victor.app.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dekoder Opusa oparty o systemowy [MediaCodec] - bez żadnej biblioteki natywnej.
 *
 * ## Po co
 * Mikrofon okularów ma dwie drogi. Pierwsza to klasyczny Bluetooth (profil
 * rozmowy SCO/HFP) - działa bez dekodowania, ale nie każdy egzemplarz go
 * wystawia, a zestawienie SCO potrafi zawiesić odtwarzanie A2DP. Druga to
 * strumień po BLE: aplikacja producenta odbiera pakiety `AiChatResponse` i
 * dekoduje je biblioteką JieLi (`com.jieli.jl_audio_decode.opus.OpusManager`).
 * Ta klasa robi to samo, tylko dekoderem, który Android ma na pokładzie.
 *
 * ## Dlaczego bez biblioteki natywnej
 * Android od 5.0 ma programowy dekoder Opusa (`c2.android.opus.decoder`) -
 * używany do WebM. Da się go nakarmić SUROWYMI pakietami Opusa, o ile poda mu
 * się trzy bufory konfiguracyjne, których zwykle dostarcza kontener:
 * - `csd-0` - nagłówek `OpusHead` (19 bajtów),
 * - `csd-1` - opóźnienie kodeka w nanosekundach,
 * - `csd-2` - `seek pre-roll` w nanosekundach.
 * Bez nich dekoder odmawia konfiguracji. To jedyna nieoczywista rzecz w całym
 * tym pliku - reszta to zwykła pętla MediaCodec.
 *
 * ## Czego NIE wiemy
 * Nie wiadomo na pewno, czy `subData` z SDK to goły pakiet Opusa, czy pakiet w
 * jakiejś ramce producenta. Dlatego [decode] nie rzuca wyjątkiem przy
 * niepowodzeniu, tylko zwraca `null` - a diagnostyka pokazuje kształt pakietów,
 * żeby dało się to rozstrzygnąć na sprzęcie, a nie zgadywaniem.
 */
class OpusDecoder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = 1
) {
    private var codec: MediaCodec? = null
    private var configured = false

    /** Czy dekoder udało się w ogóle uruchomić na tym urządzeniu. */
    val isReady: Boolean get() = configured

    /**
     * Przygotowuje dekoder. Wołane raz, przed pierwszym [decode].
     *
     * @return `false`, gdy urządzenie nie ma dekodera Opusa - wtedy cała ścieżka
     *   po BLE po prostu nie jest dostępna i zostaje klasyczny Bluetooth
     */
    fun start(): Boolean {
        if (configured) return true
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(nanosLe(CODEC_DELAY_NS)))
                setByteBuffer("csd-2", ByteBuffer.wrap(nanosLe(SEEK_PREROLL_NS)))
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                configure(format, null, null, 0)
                start()
            }
            configured = true
            Log.i(TAG, "Dekoder Opusa gotowy ($sampleRate Hz, $channels kanał/y)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się uruchomić dekodera Opusa", e)
            release()
            false
        }
    }

    /**
     * Dekoduje jeden pakiet Opusa na PCM 16 bit little-endian.
     *
     * @return próbki PCM albo `null`, gdy dekoder odmówił (najczęściej: to nie
     *   był goły pakiet Opusa)
     */
    fun decode(packet: ByteArray, presentationTimeUs: Long): ByteArray? {
        val mc = codec ?: return null
        if (!configured) return null
        return try {
            val inIndex = mc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIndex < 0) {
                Log.d(TAG, "Brak wolnego bufora wejściowego - pomijam pakiet")
                return null
            }
            mc.getInputBuffer(inIndex)?.apply {
                clear()
                put(packet)
            }
            mc.queueInputBuffer(inIndex, 0, packet.size, presentationTimeUs, 0)
            drain(mc)
        } catch (e: Exception) {
            Log.w(TAG, "Dekodowanie pakietu nie powiodło się (${packet.size} B)", e)
            null
        }
    }

    /**
     * Zbiera wszystko, co dekoder zdążył wypluć.
     *
     * Jeden pakiet wejściowy nie musi dać dokładnie jednego wyjściowego, więc
     * pętla leci do wyczerpania - inaczej PCM zostawałby w kolejce i wracał z
     * opóźnieniem przy następnym pakiecie.
     */
    private fun drain(mc: MediaCodec): ByteArray? {
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = mc.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    val buffer = mc.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(chunk)
                        out.write(chunk)
                    }
                    mc.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Format wyjściowy: ${mc.outputFormat}")
                }
                else -> break
            }
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        configured = false
    }

    /**
     * Nagłówek `OpusHead` - dokładnie tak, jak opisuje go RFC 7845.
     *
     * Dekoder Androida oczekuje go w `csd-0`; bez niego `configure()` rzuca.
     */
    private fun opusHead(): ByteArray {
        val buffer = ByteBuffer.allocate(OPUS_HEAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OpusHead".toByteArray(Charsets.US_ASCII))
        buffer.put(1)                       // wersja
        buffer.put(channels.toByte())       // liczba kanałów
        buffer.putShort(PRE_SKIP.toShort()) // pre-skip
        buffer.putInt(sampleRate)           // wejściowa częstotliwość próbkowania
        buffer.putShort(0)                  // output gain
        buffer.put(0)                       // mapping family
        return buffer.array()
    }

    private fun nanosLe(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    companion object {
        private const val TAG = "OpusDecoder"

        /** Opus zawsze pracuje wewnętrznie na 48 kHz. */
        const val SAMPLE_RATE = 48_000

        private const val OPUS_HEAD_SIZE = 19

        /** Standardowe pre-skip dla Opusa - 312 próbek przy 48 kHz. */
        private const val PRE_SKIP = 312

        /** Pre-skip przeliczone na nanosekundy: 312 / 48000 s. */
        private const val CODEC_DELAY_NS = 6_500_000L

        /** Wartość zalecana przez specyfikację kontenerów (80 ms). */
        private const val SEEK_PREROLL_NS = 80_000_000L

        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
