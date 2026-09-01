package pl.victor.app.conversation

import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rozpoznawanie mowy na prawdziwym Androidzie.
 *
 * Emulator w CI zwykle **nie ma** pakietu rozpoznawania mowy i to jest tu
 * najważniejszy przypadek: `listen()` ma wtedy oddać `null` od razu, a nie
 * zawiesić się na limicie czasu albo wywalić aplikację. Dokładnego
 * rozpoznawania mowy nie da się sprawdzić bez odtwarzania dźwięku
 * do mikrofonu, więc tego nie udajemy.
 */
@RunWith(AndroidJUnit4::class)
class SpeechToTextTest {

    private val stt: SpeechToText
        get() = SpeechToText(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun brakRozpoznawaniaNieWieszaAniNieWywala() = runBlocking {
        val speech = stt
        if (speech.isAvailable()) {
            // Na urządzeniu z rozpoznawaniem sprawdzamy tylko, że wywołanie
            // wraca w rozsądnym czasie przy ciszy.
            val start = System.currentTimeMillis()
            speech.listen(timeoutMs = 2_000)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("listen() nie oddało sterowania w limicie: $elapsed ms", elapsed < 10_000)
        } else {
            val start = System.currentTimeMillis()
            val result = speech.listen(timeoutMs = 5_000)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("bez rozpoznawania listen() ma zwrócić null", result == null)
            assertTrue(
                "bez rozpoznawania listen() ma wrócić od razu, a wróciło po $elapsed ms",
                elapsed < 2_000
            )
        }
    }

    @Test
    fun isAvailableNieRzuca() {
        // Sam odczyt dostępności nie może wywrócić aplikacji na żadnym urządzeniu.
        val available = stt.isAvailable()
        assertTrue("isAvailable() ma zwrócić boolean", available || !available)
    }

    @Test
    fun kodyBleduMajaCzytelneOpisy() {
        val speech = stt
        val codes = listOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        )
        for (code in codes) {
            val description = speech.describeError(code)
            assertNotNull(description)
            assertFalse(
                "kod $code nie ma własnego opisu, dostał: $description",
                description.startsWith("nieznany")
            )
        }
        assertTrue(
            "nieznany kod ma być oznaczony jako nieznany",
            speech.describeError(9999).startsWith("nieznany")
        )
    }
}
