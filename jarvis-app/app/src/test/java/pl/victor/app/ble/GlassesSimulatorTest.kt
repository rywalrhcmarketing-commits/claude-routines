package pl.victor.app.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy symulatora okularów.
 *
 * Klucz jest taki: symulator ma produkować ramki, które **prawdziwy** dekoder
 * [GlassesProtocol.decodeNotify] rozumie. Gdyby symulator składał ramki po swojemu,
 * cała symulacja sprawdzałaby samą siebie i nie mówiła nic o kodzie produkcyjnym.
 * Dlatego każdy test przepuszcza wyprodukowaną ramkę przez decodeNotify.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesSimulatorTest {

    private val frames = mutableListOf<ByteArray>()

    private fun simulator(
        scope: TestScope,
        faults: GlassesSimulator.Faults = GlassesSimulator.Faults()
    ) = GlassesSimulator(
        scope = scope,
        timings = GlassesSimulator.Timings.INSTANT,
        photos = EmbeddedPhotoSource,
        faults = faults,
        onNotify = { frames.add(it) }
    )

    private fun events(): List<NotifyEvent> = frames.map { GlassesProtocol.decodeNotify(it) }

    // === Cykl połączenia ===

    @Test
    fun `polaczenie przechodzi przez stany i konczy sie ramka baterii`() = runTest {
        val sim = simulator(this)
        val states = mutableListOf<ConnectionState>()

        sim.connect { states.add(it) }
        advanceUntilIdle()

        assertEquals(
            listOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.READY
            ),
            states
        )
        assertTrue("po połączeniu symulator ma być połączony", sim.connected)
        val battery = events().filterIsInstance<NotifyEvent.Battery>().single()
        assertEquals(87, battery.level)
        assertFalse(battery.charging)
    }

    @Test
    fun `rozlaczenie konczy nagrywanie`() = runTest {
        val sim = simulator(this)
        sim.connect { }
        advanceUntilIdle()
        sim.handleCommand(GlassesProtocol.startVideo())
        sim.handleCommand(GlassesProtocol.startAudio())
        assertTrue(sim.recordingVideo)
        assertTrue(sim.recordingAudio)

        sim.disconnect()

        assertFalse(sim.connected)
        assertFalse(sim.recordingVideo)
        assertFalse(sim.recordingAudio)
    }

    @Test
    fun `urzadzenie rozglaszane w skanie ma adres MAC i nazwe`() = runTest {
        val device = simulator(this).advertisedDevice()
        assertEquals(GlassesSimulator.SIMULATED_MAC, device.address)
        assertEquals(GlassesSimulator.SIMULATED_NAME, device.name)
    }

    // === Zdjęcia ===

    @Test
    fun `zdjecie AI konczy sie ramka PhotoReady`() = runTest {
        val sim = simulator(this)
        sim.handleCommand(GlassesProtocol.captureAiPhoto(2))
        advanceUntilIdle()

        assertTrue(events().any { it is NotifyEvent.PhotoReady })
    }

    @Test
    fun `zwykle zdjecie tez konczy sie ramka PhotoReady`() = runTest {
        val sim = simulator(this)
        sim.handleCommand(GlassesProtocol.takePhoto())
        advanceUntilIdle()

        assertTrue(events().any { it is NotifyEvent.PhotoReady })
    }

    @Test
    fun `awaria dropPhotoReadyNotify wycisza ramke gotowosci`() = runTest {
        val sim = simulator(this, GlassesSimulator.Faults(dropPhotoReadyNotify = true))
        sim.handleCommand(GlassesProtocol.captureAiPhoto(2))
        advanceUntilIdle()

        assertTrue(
            "przy tej awarii aplikacja nie ma dostać ramki 0x02",
            events().none { it is NotifyEvent.PhotoReady }
        )
    }

    @Test
    fun `miniatura to poprawny JPEG`() = runTest {
        val bytes = simulator(this).thumbnail()
        assertNotNull(bytes)
        val jpeg = bytes!!
        assertTrue("JPEG zaczyna się od SOI (FFD8)", jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        assertTrue(
            "JPEG kończy się EOI (FFD9)",
            jpeg[jpeg.size - 2] == 0xFF.toByte() && jpeg[jpeg.size - 1] == 0xD9.toByte()
        )
    }

    @Test
    fun `awaria emptyThumbnail zwraca null`() = runTest {
        val sim = simulator(this, GlassesSimulator.Faults(emptyThumbnail = true))
        assertNull(sim.thumbnail())
    }

    // === Tryb transferu ===

    @Test
    fun `tryb transferu zglasza blad P2P a potem IP`() = runTest {
        val sim = simulator(this)
        sim.handleCommand(GlassesProtocol.enableTransferMode())
        advanceUntilIdle()

        val decoded = events()
        assertTrue(
            "prawdziwe okulary rutynowo rzucają błędem 255 zanim podadzą IP",
            decoded.any { it is NotifyEvent.P2pError && it.code == 255 }
        )
        val ip = decoded.filterIsInstance<NotifyEvent.GlassesIp>().single()
        assertEquals(GlassesSimulator.SIMULATED_IP, ip.ip)
    }

    @Test
    fun `awaria transferModeNeverGivesIp nie podaje adresu`() = runTest {
        val sim = simulator(this, GlassesSimulator.Faults(transferModeNeverGivesIp = true))
        sim.handleCommand(GlassesProtocol.enableTransferMode())
        advanceUntilIdle()

        assertTrue(events().none { it is NotifyEvent.GlassesIp })
    }

    // === Liczniki plików ===

    @Test
    fun `zatrzymanie wideo zwieksza licznik i dodaje plik`() = runTest {
        val sim = simulator(this)
        val before = sim.mediaCount()

        sim.handleCommand(GlassesProtocol.startVideo())
        sim.handleCommand(GlassesProtocol.stopVideo())
        advanceUntilIdle()

        assertEquals(before.videos + 1, sim.mediaCount().videos)
        assertTrue(sim.mediaFileList().any { it.endsWith(".mp4") })
    }

    @Test
    fun `zatrzymanie audio bez startu nic nie zmienia`() = runTest {
        val sim = simulator(this)
        val before = sim.mediaCount()

        sim.handleCommand(GlassesProtocol.stopAudio())
        advanceUntilIdle()

        assertEquals(before.records, sim.mediaCount().records)
    }

    @Test
    fun `zdjecie zwieksza licznik obrazow`() = runTest {
        val sim = simulator(this)
        val before = sim.mediaCount().images

        sim.handleCommand(GlassesProtocol.takePhoto())
        advanceUntilIdle()

        assertEquals(before + 1, sim.mediaCount().images)
    }

    // === Pliki ===

    @Test
    fun `pobranie pliku JPG zwraca zdjecie`() = runTest {
        val sim = simulator(this)
        val name = sim.mediaFileList().first { it.endsWith(".jpg") }
        val bytes = sim.fileBytes(name)
        assertTrue(bytes.isNotEmpty())
        assertEquals(0xFF.toByte(), bytes[0])
    }

    @Test(expected = VictorException::class)
    fun `pobranie nieistniejacego pliku rzuca wyjatkiem`() = runTest {
        simulator(this).fileBytes("NIE_MA.jpg")
    }

    // === Zdarzenia wstrzykiwane ręcznie ===

    @Test
    fun `pressButton daje zdarzenie przycisku`() = runTest {
        simulator(this).pressButton()
        assertTrue(events().single() is NotifyEvent.ButtonPressed)
    }

    @Test
    fun `setBattery zglasza podany poziom i ladowanie`() = runTest {
        simulator(this).setBattery(12, isCharging = true)
        val battery = events().single() as NotifyEvent.Battery
        assertEquals(12, battery.level)
        assertTrue(battery.charging)
    }

    @Test
    fun `signalLowMemory daje ostrzezenie o pamieci`() = runTest {
        simulator(this).signalLowMemory()
        assertTrue(events().single() is NotifyEvent.LowMemory)
    }

    @Test
    fun `injectFrame przepuszcza dowolna ramke`() = runTest {
        simulator(this).injectFrame(GlassesProtocol.otaProgressFrame(40, 0, 0))
        val ota = events().single() as NotifyEvent.OtaProgress
        assertEquals(40, ota.download)
    }

    // === Opisy komend ===

    @Test
    fun `handleCommand zwraca czytelny opis`() = runTest {
        val sim = simulator(this)
        assertEquals("Zdjęcie", sim.handleCommand(GlassesProtocol.takePhoto()))
        assertEquals(
            "Zapytanie o liczbę plików",
            sim.handleCommand(GlassesProtocol.requestMediaCount())
        )
        assertEquals(
            "Zdjęcie AI z miniaturą (jakość 3)",
            sim.handleCommand(GlassesProtocol.captureAiPhoto(3))
        )
    }

    @Test
    fun `ostatnia komenda jest zapamietana`() = runTest {
        val sim = simulator(this)
        sim.handleCommand(GlassesProtocol.startAudio())
        assertEquals("Start nagrywania audio", sim.lastCommand)
    }

    @Test
    fun `bateria spada po zrobieniu zdjecia`() = runTest {
        val sim = simulator(this)
        sim.requestBattery()
        advanceUntilIdle()
        val start = (events().last() as NotifyEvent.Battery).level

        sim.handleCommand(GlassesProtocol.takePhoto())
        advanceUntilIdle()
        frames.clear()
        sim.requestBattery()
        advanceUntilIdle()

        val after = (events().last() as NotifyEvent.Battery).level
        assertTrue("bateria ma spadać przy pracy: $start -> $after", after < start)
    }

    @Test
    fun `OTA zglasza rosnacy postep`() = runTest {
        val sim = simulator(this)
        sim.handleCommand(GlassesProtocol.command(GlassesProtocol.WORK_OTA))
        advanceUntilIdle()

        val steps = events().filterIsInstance<NotifyEvent.OtaProgress>()
        assertTrue("OTA ma zgłosić kilka kroków, było ${steps.size}", steps.size >= 5)
        assertEquals(0, steps.first().download)
        assertEquals(100, steps.last().soc)
    }

    // === Nagrania po BLE ===

    @Test
    fun `lista nagran zawiera tylko pliki REC`() = runTest {
        val sim = simulator(this)
        val list = sim.recordings()
        assertTrue("symulator ma mieć jakieś nagrania", list.isNotEmpty())
        assertTrue(
            "kanał nagrań nie może zwracać zdjęć ani wideo, było: $list",
            list.all { it.fileName.startsWith("REC_") }
        )
        assertTrue("nagranie ma mieć niezerowy rozmiar", list.all { it.lengthBytes > 0 })
    }

    @Test
    fun `nowe nagranie pojawia sie na liscie po zatrzymaniu`() = runTest {
        val sim = simulator(this)
        val before = sim.recordings().size

        sim.handleCommand(GlassesProtocol.startAudio())
        sim.handleCommand(GlassesProtocol.stopAudio())
        advanceUntilIdle()

        assertEquals(before + 1, sim.recordings().size)
    }

    @Test
    fun `pobranie nagrania zwraca bajty`() = runTest {
        val sim = simulator(this)
        val name = sim.recordings().first().fileName
        val bytes = sim.recordingBytes(name)
        assertNotNull(bytes)
        assertTrue(bytes!!.isNotEmpty())
    }

    @Test
    fun `pobranie nieistniejacego nagrania zwraca null`() = runTest {
        assertNull(simulator(this).recordingBytes("NIE_MA.opus"))
    }

}
