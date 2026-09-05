package pl.victor.app.audio

/**
 * Zmiana częstotliwości próbkowania PCM 16-bit little-endian, mono.
 *
 * ## Po co
 * Opus z okularów rozkodowuje się na 48 kHz, a rozpoznawanie mowy na
 * urządzeniu pracuje na 16 kHz - tak działa cały łańcuch modeli mowy w
 * Androidzie. `EXTRA_AUDIO_SOURCE_SAMPLING_RATE` pozwala wprawdzie zadeklarować
 * własną częstotliwość, ale czy usługa ją PRZELICZY, zależy od jej
 * implementacji. Podanie 48 kHz i liczenie na to, że druga strona sobie
 * poradzi, to zakład - a stawką jest cała transkrypcja mowy z okularów.
 * Przeliczamy więc sami, u siebie, gdzie widać, co się dzieje.
 *
 * ## Dlaczego uśrednianie, a nie samo wyrzucanie próbek
 * Przy dzieleniu częstotliwości trzy razy zwykłe "bierz co trzecią próbkę"
 * składa wszystko powyżej 8 kHz z powrotem w pasmo mowy jako szum (aliasing) -
 * czyli psuje dokładnie to, co miało pomóc. Uśrednienie trzech kolejnych
 * próbek jest najprostszym filtrem, który temu zapobiega, a przy mowie w
 * zupełności wystarcza.
 */
object PcmResampler {

    /** Częstotliwość, na której pracuje rozpoznawanie mowy w Androidzie. */
    const val SPEECH_SAMPLE_RATE = 16_000

    private const val BYTES_PER_SAMPLE = 2

    /**
     * Przelicza [pcm] z [sourceRate] na [targetRate].
     *
     * @return nowe próbki, albo to samo wejście, gdy przeliczanie jest zbędne
     *   lub niemożliwe (puste wejście, bezsensowna częstotliwość)
     */
    fun resample(
        pcm: ByteArray,
        sourceRate: Int,
        targetRate: Int = SPEECH_SAMPLE_RATE
    ): ByteArray {
        if (pcm.size < BYTES_PER_SAMPLE) return pcm
        if (sourceRate <= 0 || targetRate <= 0) return pcm
        if (sourceRate == targetRate) return pcm

        val input = toShorts(pcm)
        val output = when {
            // Całkowita krotność (48 000 -> 16 000 to dokładnie 3) - uśredniamy
            // po kolejnych próbkach, co jest jednocześnie filtrem.
            sourceRate % targetRate == 0 -> decimate(input, sourceRate / targetRate)
            else -> interpolate(input, sourceRate, targetRate)
        }
        return toBytes(output)
    }

    /** Uśrednia [factor] kolejnych próbek w jedną. */
    private fun decimate(input: ShortArray, factor: Int): ShortArray {
        val outputSize = input.size / factor
        if (outputSize == 0) return input
        val output = ShortArray(outputSize)
        for (i in 0 until outputSize) {
            var sum = 0
            val base = i * factor
            for (k in 0 until factor) sum += input[base + k]
            output[i] = (sum / factor).toShort()
        }
        return output
    }

    /**
     * Przeliczenie liniowe - dla częstotliwości, które nie dzielą się bez
     * reszty. Gorsze od uśredniania, ale ta gałąź obsługuje przypadki, których
     * na tym sprzęcie nie ma; jest po to, żeby nie było ich nieobsłużonych.
     */
    private fun interpolate(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        val outputSize = (input.size.toLong() * targetRate / sourceRate).toInt()
        if (outputSize <= 0) return input
        val output = ShortArray(outputSize)
        val step = input.size.toDouble() / outputSize
        for (i in 0 until outputSize) {
            val position = i * step
            val left = position.toInt()
            val right = (left + 1).coerceAtMost(input.size - 1)
            val fraction = position - left
            output[i] = (input[left] + (input[right] - input[left]) * fraction)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return output
    }

    private fun toShorts(pcm: ByteArray): ShortArray {
        val count = pcm.size / BYTES_PER_SAMPLE
        val out = ShortArray(count)
        for (i in 0 until count) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            out[i] = ((high shl 8) or low).toShort()
        }
        return out
    }

    private fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * BYTES_PER_SAMPLE)
        for (i in samples.indices) {
            val value = samples[i].toInt()
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }
}
