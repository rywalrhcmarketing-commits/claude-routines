package pl.victor.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Odtwarza surowe PCM - wyłącznie do diagnostyki.
 *
 * ## Po co to jest
 * Cała ścieżka dźwięku z okularów (BLE → Opus → PCM) może zawieść po cichu:
 * dekoder nie rzuca wyjątkiem, gdy dostanie źle poskładane dane, tylko oddaje
 * szum albo ciszę. Liczby w raporcie tego nie rozstrzygają - "rozkodowano 300
 * pakietów" wygląda tak samo przy dobrym i przy zepsutym ramkowaniu.
 *
 * Ucho rozstrzyga to w sekundę. Ten odtwarzacz istnieje TYLKO po to: żeby dało
 * się usłyszeć, co aplikacja naprawdę wysyła do modelu.
 */
object PcmPlayer {

    private const val TAG = "PcmPlayer"

    /**
     * Odtwarza PCM 16-bit mono i wraca, gdy skończy.
     *
     * Blokuje wątek wołającego na czas odtwarzania, więc wołaj z korutyny na
     * wątku roboczym. Nie rzuca - diagnostyka nie ma prawa wywrócić aplikacji.
     *
     * @return `false`, gdy nie udało się w ogóle zacząć
     */
    fun play(pcm: ByteArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return false
        var track: AudioTrack? = null
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                Log.w(TAG, "Urządzenie nie przyjmuje $sampleRate Hz mono")
                return false
            }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, pcm.size.coerceAtMost(MAX_BUFFER_BYTES)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                // Zapis blokujący: AudioTrack przyjmuje tyle, ile zmieści się w
                // buforze, i wraca dopiero, gdy zrobi się miejsce.
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
            // Bez tego odtwarzanie urywa się na ostatnim buforze.
            runCatching { track.stop() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się odtworzyć nagrania", e)
            false
        } finally {
            runCatching { track?.release() }
        }
    }

    /**
     * Głośność nagrania w skali 0..1 - liczona jako wartość skuteczna (RMS).
     *
     * Odróżnia trzy rzeczy, które w raporcie wyglądają identycznie: prawdziwą
     * mowę, ciszę (dekoder oddał same zera) i szum po złym ramkowaniu (wartości
     * przypadkowe, więc RMS bliskie maksimum).
     */
    fun loudness(pcm: ByteArray): Double {
        val samples = pcm.size / 2
        if (samples == 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            val value = ((high shl 8) or low).toShort().toInt()
            sumSquares += value.toDouble() * value.toDouble()
        }
        return Math.sqrt(sumSquares / samples) / Short.MAX_VALUE
    }

    /** Ponad tyle bajtów nie ma sensu buforować z góry. */
    private const val MAX_BUFFER_BYTES = 512 * 1024
}
