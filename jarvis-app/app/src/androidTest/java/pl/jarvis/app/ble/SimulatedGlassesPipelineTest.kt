package pl.jarvis.app.ble

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test całej ścieżki komunikacji z okularami na symulatorze, uruchamiany
 * na prawdziwym Androidzie (emulator w CI albo telefon).
 *
 * To jest odpowiedź na pytanie "czy aplikacja zadziała, gdy przyjdą okulary".
 * Nie zastąpi sprzętu - nie sprawdzi firmware'u ani zasięgu BLE - ale sprawdza
 * wszystko, co jest po naszej stronie: skan, połączenie, komendy, dekodowanie
 * ramek, transfer miniatury, tryb transferu i pobieranie plików. Jedyne, co
 * jest udawane, to warstwa transportowa.
 *
 * Zdjęcia idą przez [CanvasPhotoSource], więc test weryfikuje też, że bajty
 * naprawdę dekodują się do bitmapy - a to jest dokładnie to, czego potrzebuje
 * warstwa AI.
 */
@RunWith(AndroidJUnit4::class)
class SimulatedGlassesPipelineTest {

    private lateinit var manager: JarvisManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = JarvisManager.getInstance(context)
        manager.setSimulationEnabled(
            enabled = true,
            photoSource = CanvasPhotoSource(),
            timings = FAST_TIMINGS
        )
        manager.initialize()
    }

    @After
    fun tearDown() {
        manager.disconnect()
        manager.setSimulationEnabled(false)
    }

    @Test
    fun skanZnajdujeSymulowaneOkulary() = runBlocking {
        manager.startScan()
        val devices = awaitCondition { manager.discoveredDevices.value.isNotEmpty() }
        assertTrue("skan miał znaleźć symulowane okulary", devices)
        assertEquals(
            GlassesSimulator.SIMULATED_MAC,
            manager.discoveredDevices.value.first().address
        )
        manager.stopScan()
    }

    @Test
    fun polaczenieDochodziDoStanuReady() = runBlocking {
        connect()
        assertEquals(ConnectionState.READY, manager.connectionState.value)
        assertTrue("isConnected() ma zgadzać się ze stanem", manager.isConnected())
    }

    @Test
    fun poPolaczeniuPrzychodziRamkaBaterii() = runBlocking {
        connect()
        assertTrue(
            "okulary zgłaszają baterię zaraz po połączeniu",
            awaitCondition { manager.batteryLevel.value != null }
        )
        val level = manager.batteryLevel.value!!
        assertTrue("poziom baterii poza zakresem: $level", level in 0..100)
    }

    @Test
    fun zdjecieWracaJakoDekodowalnaBitmapa() = runBlocking {
        connect()

        val bytes = manager.capturePhoto()

        assertNotNull("capturePhoto() nie zwróciło bajtów", bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size)
        assertNotNull(
            "bajty zdjęcia nie dekodują się do bitmapy - warstwa AI dostałaby śmieci",
            bitmap
        )
        assertTrue("bitmapa ma zerowy rozmiar", bitmap.width > 0 && bitmap.height > 0)
        bitmap.recycle()
    }

    @Test
    fun kolejneZdjeciaRozniaSie() = runBlocking {
        connect()

        val first = manager.capturePhoto()
        val second = manager.capturePhoto()

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(
            "symulator ma zmieniać scenę - inaczej AI dostaje wciąż ten sam obraz",
            !first!!.contentEquals(second!!)
        )
    }

    @Test
    fun dziennikRamekZapisujeZdarzenia() = runBlocking {
        connect()
        awaitCondition { manager.notifyLog.value.isNotEmpty() }

        val log = manager.notifyLog.value
        assertTrue("dziennik ramek pusty po połączeniu", log.isNotEmpty())
        assertTrue(
            "wpis ma nieść surowy hex, było: ${log.first().hex}",
            log.first().hex.matches(Regex("([0-9A-F]{2} )*[0-9A-F]{2}"))
        )
        assertTrue("wpis ma nieść opis zdarzenia", log.first().meaning.isNotBlank())
    }

    @Test
    fun przyciskNaOkularachDajeZdarzenie() = runBlocking {
        connect()
        manager.clearNotifyLog()

        manager.simulatorOrNull()!!.pressButton()

        // Celowo nie sprawdzamy `manager.buttonEvent`: AIOrchestrator obserwuje
        // ten StateFlow i kasuje zdarzenie zaraz po obsłużeniu, więc odpytywanie
        // stanu ściga się z prawdziwym odbiorcą i bywa puste. Dziennik ramek jest
        // dopisywany, więc dowodzi tego samego bez wyścigu: ramka doszła
        // i została rozpoznana jako wciśnięcie przycisku.
        assertTrue(
            "ramka przycisku nie dotarła albo nie została rozpoznana",
            awaitCondition {
                manager.notifyLog.value.any { it.meaning.contains("przycisk") }
            }
        )
    }

    @Test
    fun licznikiPlikowSaOdczytywane() = runBlocking {
        connect()
        var result: Triple<Int, Int, Int>? = null
        manager.requestMediaCount { i, v, r -> result = Triple(i, v, r) }

        assertNotNull("requestMediaCount nie oddało wyniku", result)
        assertEquals(result, manager.mediaCount.value?.let {
            Triple(it.images, it.videos, it.records)
        })
    }

    @Test
    fun trybTransferuPodajeAdresIp() = runBlocking {
        connect()

        manager.enableTransferMode()

        assertTrue(
            "tryb transferu ma zakończyć się ramką 0x08 z adresem IP",
            awaitCondition(timeoutMs = 5_000) { manager.glassesIp.value != null }
        )
        assertEquals(GlassesSimulator.SIMULATED_IP, manager.glassesIp.value)
    }

    @Test
    fun listaPlikowJestNiepusta() = runBlocking {
        connect()
        val files = manager.getMediaFileList()
        assertTrue("symulator ma zgłaszać jakieś pliki", files.isNotEmpty())
        assertTrue(
            "wśród plików ma być zdjęcie, było: $files",
            files.any { it.endsWith(".jpg", ignoreCase = true) }
        )
    }

    @Test
    fun pobranieZdjeciaPrzezWifiDzialaOdKonca() = runBlocking {
        connect()

        val bytes = manager.downloadLatestPhoto()

        assertNotNull("downloadLatestPhoto() nie zwróciło pliku", bytes)
        assertTrue("pobrany plik jest pusty", bytes!!.isNotEmpty())
        // Sesja transferu ma się zamknąć - inaczej cały ruch aplikacji zostaje
        // na grupie Wi-Fi okularów.
        assertEquals(null, manager.glassesIp.value)
    }

    @Test
    fun brakRamkiGotowosciNieBlokujeZdjecia() = runBlocking {
        // Starszy firmware może nie odsyłać ramki 0x02 - aplikacja musi wtedy
        // wejść na wariant zapasowy, a nie czekać w nieskończoność.
        manager.setSimulationEnabled(false)
        manager.setSimulationEnabled(
            enabled = true,
            photoSource = CanvasPhotoSource(),
            timings = FAST_TIMINGS,
            faults = GlassesSimulator.Faults(dropPhotoReadyNotify = true)
        )
        manager.initialize()
        connect()

        val bytes = manager.capturePhoto()

        assertNotNull("bez ramki 0x02 zdjęcie i tak ma dojść wariantem zapasowym", bytes)
    }

    // === Pomocnicze ===

    private suspend fun connect() {
        manager.connect(GlassesSimulator.SIMULATED_MAC)
        val ready = awaitCondition { manager.connectionState.value == ConnectionState.READY }
        assertTrue("okulary nie doszły do stanu READY", ready)
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!condition()) kotlinx.coroutines.delay(20)
        true
    } == true

    private companion object {
        /**
         * Skrócone opóźnienia - test ma być szybki, ale nie zerowy:
         * zerowe czasy ukryłyby wyścigi, których na sprzęcie nie da się uniknąć.
         */
        val FAST_TIMINGS = GlassesSimulator.Timings(
            connectMs = 50,
            serviceDiscoveryMs = 50,
            shutterMs = 100,
            thumbnailMs = 50,
            transferModeMs = 100,
            otaStepMs = 10,
            batteryReplyMs = 10,
            httpLatencyMs = 20
        )
    }
}
