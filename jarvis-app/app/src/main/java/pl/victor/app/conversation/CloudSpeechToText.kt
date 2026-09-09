package pl.victor.app.conversation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.victor.app.audio.WavWriter
import java.util.concurrent.TimeUnit

/**
 * Przepisuje nagranie na tekst przez usługę w chmurze.
 *
 * ## Po co, skoro są już dwie inne drogi
 * Bo obie są słabe tam, gdzie to najbardziej boli. Rozpoznawanie systemowe wymaga
 * Androida 13+ i pobranego pakietu języka, a przy zablokowanym ekranie na wielu
 * telefonach po prostu nie startuje. Vosk działa zawsze, ale jego mały model
 * polski myli słowa - a przekręcone pytanie jest gorsze niż brak pytania, bo model
 * odpowiada wtedy pewnie i nie na temat. Zgłoszone: "na pytanie jaka jest pogoda
 * odpowiada, czym jest rozwód".
 *
 * Ta droga używa tego samego klucza OpenAI, który jest już w ustawieniach, więc
 * nie wymaga zakładania niczego nowego. Jakość dla polszczyzny jest nieporównanie
 * lepsza od obu poprzednich.
 *
 * ## Czego świadomie NIE robi
 * Nie wysyła niczego, gdy klucza nie ma. Nagranie głosu to dane wrażliwe i nie
 * może wychodzić z telefonu przez samo włączenie funkcji o innej nazwie - stąd
 * osobne ustawienie i wyraźny opis przy nim.
 */
class CloudSpeechToText(private val apiKey: String) {

    private val tag = "CloudSpeechToText"

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * @param pcm surowe 16-bitowe PCM mono
     * @param sampleRate częstotliwość próbkowania nagrania
     * @param languageTag język wypowiedzi ("pl-PL"); sama część przed myślnikiem
     *   idzie do API, bo oczekuje ono kodu ISO-639-1
     * @return rozpoznany tekst albo `null`, gdy się nie udało
     */
    suspend fun transcribe(
        pcm: ByteArray,
        sampleRate: Int,
        languageTag: String
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || pcm.isEmpty()) return@withContext null
        val wav = runCatching { WavWriter.wrap(pcm, sampleRate) }.getOrNull() ?: return@withContext null
        return@withContext try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", MODEL)
                // Język podany wprost, a nie zgadywany: przy krótkim nagraniu
                // wykrywanie języka bywa zawodne, a polskie pytanie rozpoznane
                // jako czeskie to dokładnie ten rodzaj błędu, który tu naprawiamy.
                .addFormDataPart("language", languageTag.substringBefore('-'))
                .addFormDataPart(
                    "file",
                    "nagranie.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Kod HTTP w logu, bo 401 (zły klucz) i 429 (limit) wymagają
                    // zupełnie różnych działań, a oba wyglądają jak "nie działa".
                    Log.w(tag, "Transkrypcja w chmurze odrzucona: ${response.code}")
                    return@use null
                }
                val heard = runCatching { JSONObject(text).optString("text").trim() }
                    .getOrDefault("")
                if (heard.isBlank()) null else heard
            }
        } catch (e: Exception) {
            Log.w(tag, "Transkrypcja w chmurze nie powiodła się", e)
            null
        }
    }

    companion object {
        private const val API_URL = "https://api.openai.com/v1/audio/transcriptions"

        /**
         * Model transkrypcji.
         *
         * `whisper-1` świadomie, mimo że są nowsze: jest najszerzej dostępny na
         * kluczach, także tych bez dostępu do najnowszych modeli, a dla polszczyzny
         * i tak bije wszystko, co liczy się lokalnie.
         */
        private const val MODEL = "whisper-1"

        private const val CONNECT_TIMEOUT_S = 10L

        /** Nagranie to kilka sekund - dłuższe czekanie znaczy, że coś jest nie tak. */
        private const val READ_TIMEOUT_S = 25L
    }
}
