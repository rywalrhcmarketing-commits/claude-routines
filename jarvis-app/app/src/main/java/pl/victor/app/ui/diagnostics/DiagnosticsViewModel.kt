package pl.victor.app.ui.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.VictorApplication
import pl.victor.app.ble.ConnectionState
import pl.victor.app.ble.GlassesRecordings
import pl.victor.app.ble.GlassesSimulator
import pl.victor.app.ble.VictorManager
import pl.victor.app.ble.MediaCount
import pl.victor.app.ble.NotifyLogEntry
import pl.victor.app.power.BatteryOptimizationHelper

/**
 * ViewModel ekranu diagnostycznego.
 *
 * Ekran ma dwa zastosowania:
 * 1. **Przed przyjściem okularów** - włącz symulację i przejdź całą ścieżkę
 *    aplikacji, żeby wiedzieć, że wszystko poza sprzętem działa.
 * 2. **Po podłączeniu okularów** - podglądaj surowe ramki notify i sprawdzaj
 *    kolejne funkcje pojedynczo, zamiast zgadywać, co poszło nie tak.
 */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VictorApplication
    private val manager: VictorManager = app.glassesManager

    val connectionState: StateFlow<ConnectionState> = manager.connectionState
    val batteryLevel: StateFlow<Int?> = manager.batteryLevel
    val isCharging: StateFlow<Boolean> = manager.isCharging
    val glassesIp: StateFlow<String?> = manager.glassesIp
    val mediaCount: StateFlow<MediaCount?> = manager.mediaCount
    val notifyLog: StateFlow<List<NotifyLogEntry>> = manager.notifyLog
    val lastCommand: StateFlow<String?> = manager.lastCommand
    val simulationEnabled: StateFlow<Boolean> = manager.simulationEnabled

    /** Wynik ostatniego testu - jedna linia do pokazania pod przyciskami. */
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Raport ze sprawdzenia całej ścieżki - osobno od [result].
     *
     * Wspólne pole znaczyłoby, że ten sam tekst pokazuje się jednocześnie w
     * trzech kartach ekranu, bo każda renderuje [result].
     */
    private val _fullCheck = MutableStateFlow<String?>(null)
    val fullCheck: StateFlow<String?> = _fullCheck.asStateFlow()

    /** Postęp pobierania nagrania po BLE. */
    val recordingProgress: StateFlow<Float?> = manager.recordingProgress

    /**
     * Czy Android ma wyłączoną optymalizację baterii dla V.I.C.T.O.R. To jest zwykle
     * prawdziwa przyczyna, gdy wake word albo połączenie z okularami "działa przez
     * chwilę, a potem samo się rozłącza" - Doze usypia proces, zanim cokolwiek
     * w kodzie zdąży zgłosić błąd. Odświeżane w onResume, bo to ekran Ustawień
     * systemowych, nie runtime permission z callbackiem.
     */
    private val _batteryExemptionGranted = MutableStateFlow(
        BatteryOptimizationHelper.isIgnoringOptimizations(application)
    )
    val batteryExemptionGranted: StateFlow<Boolean> = _batteryExemptionGranted.asStateFlow()

    fun refreshBatteryExemption() {
        _batteryExemptionGranted.value = BatteryOptimizationHelper.isIgnoringOptimizations(app)
    }

    /**
     * Numer typu pliku dla kanału nagrań. Producent go nie udokumentował,
     * więc użytkownik musi go znaleźć metodą prób - stąd suwak zamiast stałej.
     */
    private val _recordingFileType = MutableStateFlow(GlassesRecordings.DEFAULT_FILE_TYPE)
    val recordingFileType: StateFlow<Int> = _recordingFileType.asStateFlow()

    fun setRecordingFileType(type: Int) {
        _recordingFileType.value = type.coerceIn(RECORDING_FILE_TYPE_RANGE)
    }

    // === Tryb symulacji ===

    fun setSimulation(enabled: Boolean) {
        app.settings.setGlassesSimulationEnabled(enabled)
        manager.setSimulationEnabled(enabled)
        manager.initialize()
        _result.value = if (enabled) {
            "Symulacja włączona. Kliknij \"Połącz\", żeby zacząć."
        } else {
            "Symulacja wyłączona - aplikacja wróciła na prawdziwe BLE."
        }
    }

    /** W symulacji łączy od razu; na sprzęcie trzeba przejść przez ekran parowania. */
    fun connectSimulated() {
        val sim = manager.simulatorOrNull()
        if (sim == null) {
            _result.value = "To działa tylko w trybie symulacji - na sprzęcie użyj ekranu parowania."
            return
        }
        manager.connect(GlassesSimulator.SIMULATED_MAC)
    }

    fun disconnect() {
        manager.disconnect()
        _result.value = "Rozłączono."
    }

    // === Wstrzykiwanie zdarzeń (tylko symulacja) ===

    fun injectButtonPress() = withSimulator("Nie ma czego wstrzykiwać - symulacja wyłączona.") {
        it.pressButton()
        _result.value = "Wstrzyknięto wciśnięcie przycisku AI."
    }

    fun injectLowBattery() = withSimulator("Symulacja wyłączona.") {
        it.setBattery(9, isCharging = false)
        _result.value = "Ustawiono baterię na 9%."
    }

    fun injectLowMemory() = withSimulator("Symulacja wyłączona.") {
        it.signalLowMemory()
        _result.value = "Zgłoszono brak pamięci na okularach."
    }

    /**
     * Udaje wybudzenie po stronie okularów - to samo zdarzenie, które przychodzi,
     * gdy okulary usłyszą swoją frazę. Cała ścieżka rozmowy (audio -> nasłuch ->
     * AI -> odpowiedź głosem) da się dzięki temu przejść bez sprzętu na głowie.
     */
    fun injectWakeWord() = withSimulator("Symulacja wyłączona.") {
        it.requestAiSession(realtimeText = false)
        _result.value = "Zgłoszono wybudzenie - powinien ruszyć nasłuch."
    }

    /** Udaje dotknięcie zauszników w trakcie mówienia ("cicho"). */
    fun injectInterrupt() = withSimulator("Symulacja wyłączona.") {
        it.interruptSpeech()
        _result.value = "Zgłoszono przerwanie wypowiedzi."
    }

    /** Udaje zmianę głośności na zausznikach. */
    fun injectVolume() = withSimulator("Symulacja wyłączona.") {
        it.setVolume(7)
        _result.value = "Zgłoszono zmianę głośności."
    }

    /**
     * Sprawdza, czy telefon widzi okulary jako zestaw audio Bluetooth, i mówi
     * przez nie zdanie testowe.
     *
     * To najczęstsza przyczyna "okulary nie mówią": BLE jest połączone (bo to
     * inny kanał), ale część klasyczna nie została sparowana w ustawieniach
     * Bluetooth telefonu - i wtedy dźwięk idzie w głośnik telefonu.
     */
    fun testGlassesAudio() {
        viewModelScope.launch {
            val audio = app.audio
            if (!audio.hasBluetoothAudioDevice()) {
                _result.value = "Telefon NIE widzi żadnego zestawu audio Bluetooth.\n" +
                    "Okulary trzeba sparować osobno, w ustawieniach Bluetooth telefonu " +
                    "(BLE to inny kanał niż dźwięk). Jeśli ich tam nie ma - połącz się " +
                    "najpierw z aplikacji, bo dopiero wtedy okulary włączają część audio."
                return@launch
            }
            val held = audio.beginConversationRouting()
            try {
                // Rozdzielamy odtwarzanie od rozmowy. Zestaw, który wystawia samo
                // A2DP, będzie dobrze MÓWIŁ, ale mikrofon poleci z telefonu -
                // bez tego rozróżnienia wygląda to na losową usterkę.
                _result.value = audio.audioProfileSummary() + "\n\n" + if (held) {
                    "Rozmowa idzie przez zestaw Bluetooth. Mów teraz - powinno " +
                        "być słychać odpowiedź w okularach."
                } else {
                    "Nie udało się przełączyć ROZMOWY na zestaw Bluetooth. " +
                        "Odpowiedź i tak może być słyszalna w okularach (A2DP), " +
                        "ale pytania będzie zbierał mikrofon telefonu."
                }
                audio.speakAndAwait(
                    "Test dźwięku. Jeśli mnie słyszysz w okularach, wszystko gra.",
                    language = app.settings.getResponseLanguage()
                )
            } finally {
                if (held) audio.endConversationRouting()
            }
        }
    }

    /** Statystyki strumienia audio z mikrofonu okularów. */
    val micStats = manager.micStreamStats

    /**
     * Włącza nasłuch strumienia audio z okularów na 10 sekund i mówi, ile
     * przyszło.
     *
     * To jedyny sposób, żeby odróżnić "okulary nie nadają dźwięku" od "nadają,
     * ale nie umiemy tego rozkodować" - bez pomiaru obie sytuacje wyglądają
     * identycznie, czyli jako cisza. Producent bierze ten strumień po BLE i
     * dekoduje go jako Opus; my na razie tylko liczymy pakiety.
     */
    fun testGlassesMicStream() {
        if (manager.connectionState.value != ConnectionState.READY) {
            _result.value = "Najpierw połącz okulary."
            return
        }
        viewModelScope.launch {
            val capture = pl.victor.app.audio.GlassesVoiceCapture(manager)
            val decoderOk = capture.start()
            // Odliczanie na żywo, bo zgłoszono, że "nic się nie dzieje": pomiar
            // trwał kilkanaście sekund w całkowitej ciszy, więc wyglądał jak
            // martwy przycisk. Teraz na ekranie widać, że coś leci, ILE jeszcze
            // i czy pakiety już przychodzą - a nie tylko wynik na końcu.
            for (remaining in MIC_TEST_SECONDS downTo 1) {
                val stats = manager.micStreamStats.value
                _result.value = buildString {
                    append("Nasłuchuję strumienia BLE z okularów: ")
                    append(remaining).append(" s\n")
                    append("TERAZ wybudź okulary - naciśnij przycisk AI albo powiedz ")
                    append("słowo kluczowe - i mów.\n")
                    append("Same okulary nie nadają dźwięku bez wybudzenia, więc ")
                    append("czekanie w ciszy zawsze da zero.\n")
                    append("Pakiety: ").append(stats.packets)
                    append(" (").append(stats.bytes).append(" B)")
                }
                kotlinx.coroutines.delay(1_000)
            }
            val result = capture.stop()
            _result.value = buildString {
                append(result.describe()).append("\n\n")
                when {
                    result.packets == 0 -> append(
                        "Jeśli w trakcie pomiaru okulary były wybudzone, to znaczy, że " +
                            "ten egzemplarz nie nadaje mikrofonu tą drogą. Zostaje ścieżka " +
                            "przez klasyczny Bluetooth - sparuj okulary jako zestaw " +
                            "słuchawkowy w ustawieniach Bluetooth telefonu."
                    )
                    !decoderOk -> append(
                        "To urządzenie nie ma systemowego dekodera Opusa, więc strumienia " +
                            "nie da się tu wykorzystać - ale okulary NADAJĄ, co jest " +
                            "najważniejszą informacją z tego pomiaru."
                    )
                    result.hasAudio -> append(
                        "Ścieżka po BLE DZIAŁA: dźwięk z mikrofonu okularów da się " +
                            "rozkodować i wysłać do modelu. Aplikacja użyje jej " +
                            "automatycznie, gdy zwykłe rozpoznawanie mowy nic nie usłyszy."
                    )
                    else -> append(
                        "Pakiety przychodzą, ale to nie jest goły strumień Opusa - " +
                            "prawdopodobnie ma własną ramkę producenta. Podgląd " +
                            "pierwszego pakietu wyżej wystarczy, żeby to rozstrzygnąć."
                    )
                }
            }
        }
    }


    // === Sprawdzenie całej ścieżki ===

    /**
     * Przechodzi po kolei przez wszystko, co musi zadziałać, żeby okulary były
     * użyteczne, i mówi, KTÓRY etap nie działa.
     *
     * ## Po co osobny przycisk, skoro są testy pojedynczych funkcji
     * Bo "AI nie reaguje" nie wskazuje etapu. Ścieżka od wybudzenia do
     * odpowiedzi ma siedem ogniw - BLE, zgoda na mikrofon, rozpoznawanie mowy,
     * profil audio, syntezator, klucz do modelu, aparat - i awaria KAŻDEGO
     * wygląda tak samo: cisza. Testy pojedyncze wymagały wiedzy, którego z nich
     * użyć; ten przycisk sprawdza wszystkie po kolei i pokazuje raport.
     *
     * Wynik dopisuje się na bieżąco, żeby było widać postęp, a nie martwy ekran.
     */
    fun runFullCheck() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val report = StringBuilder("SPRAWDZENIE CAŁEJ ŚCIEŻKI\n")
            fun step(line: String) {
                report.append(line).append('\n')
                _fullCheck.value = report.toString()
            }
            try {
                step(checkGlasses())
                step(checkMicPermission())
                step(checkSpeechRecognition())
                step(checkAudioRoute())
                step(checkTts())
                step(checkAiProvider())
                step(checkCamera())
                report.append('\n').append(verdict(report.toString()))
                _fullCheck.value = report.toString()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun checkGlasses(): String {
        val state = manager.connectionState.value
        return if (state == ConnectionState.READY) {
            val battery = manager.batteryLevel.value
            "✅ 1. Okulary połączone (BLE)" + (battery?.let { ", bateria $it%" } ?: "")
        } else {
            "❌ 1. Okulary NIE są połączone (stan: $state).\n" +
                "   → Ekran Parowanie. Bez tego nic dalej nie zadziała."
        }
    }

    private fun checkMicPermission(): String {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (granted) {
            "✅ 2. Zgoda na mikrofon jest"
        } else {
            "❌ 2. BRAK zgody na mikrofon.\n" +
                "   → Ekran główny, przycisk Powiedz - system zapyta. To jest\n" +
                "     najczęstsza przyczyna \"wybudzenie działa, ale potem cisza\"."
        }
    }

    private fun checkSpeechRecognition(): String {
        val available = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(app)
        }.getOrDefault(false)
        return if (available) {
            "✅ 3. Rozpoznawanie mowy dostępne"
        } else {
            "❌ 3. To urządzenie NIE ma rozpoznawania mowy.\n" +
                "   → Zainstaluj Google (aplikację) albo używaj klawiatury."
        }
    }

    private fun checkAudioRoute(): String {
        val audio = app.audio
        if (!audio.hasBluetoothAudioDevice()) {
            return "❌ 4. Telefon nie widzi okularów jako urządzenia audio.\n" +
                "   → Sparuj je DODATKOWO w ustawieniach Bluetooth telefonu.\n" +
                "     BLE (to połączenie) i dźwięk to dwa różne kanały."
        }
        val mic = audio.hasConversationMic()
        val micOn = app.settings.isGlassesMicEnabled()
        return buildString {
            append("✅ 4. Dźwięk przez Bluetooth działa\n")
            append("   ").append(audio.conversationDeviceName() ?: "urządzenie bez nazwy").append('\n')
            if (mic && micOn) {
                append("   Mikrofon okularów dostępny (profil rozmowy SCO/HFP)")
            } else if (mic) {
                append("   Mikrofon okularów jest, ale wyłączony w Ustawieniach -\n")
                append("   pytania zbiera telefon")
            } else {
                append("   ⚠️ Tylko odtwarzanie (A2DP), bez profilu rozmowy -\n")
                append("   pytania zbierze mikrofon telefonu")
            }
        }
    }

    private suspend fun checkTts(): String {
        val audio = app.audio
        if (!audio.ttsReady.value) {
            return "❌ 5. Syntezator mowy nie wystartował.\n" +
                "   → Ustawienia Androida > Zamiana tekstu na mowę."
        }
        val voice = audio.currentVoice.value
        val spoke = audio.speakAndAwait("Sprawdzam dźwięk.", app.settings.getResponseLanguage())
        return if (spoke) {
            "✅ 5. Syntezator mówi" + (voice?.let { ", głos: ${it.displayName}" } ?: "") +
                "\n   Czy to było słychać W OKULARACH? Jeśli w telefonie -\n" +
                "   dźwięk nie idzie przez zestaw."
        } else {
            "⚠️ 5. Syntezator jest, ale wypowiedź się nie zakończyła.\n" +
                "   → Sprawdź głośność i czy nic innego nie gra."
        }
    }

    private suspend fun checkAiProvider(): String {
        val settings = app.settings
        val providerId = settings.getActiveProvider()
        val key = settings.getApiKey(providerId)
        if (key.isNullOrBlank()) {
            return "❌ 6. Brak klucza API dla providera \"$providerId\".\n" +
                "   → Ustawienia > Model AI."
        }
        val models = pl.victor.app.data.RemoteModelValidator(key, providerId)
            .fetchAvailableModels()
        val selected = settings.getSelectedModel(providerId)
        return when {
            models.isEmpty() ->
                "⚠️ 6. Nie udało się pobrać listy modeli dla \"$providerId\".\n" +
                    "   Klucz może być zły albo nie ma internetu - albo ten\n" +
                    "   provider po prostu nie udostępnia takiej listy."
            selected != null && !models.contains(selected) ->
                "⚠️ 6. Klucz działa, ale wybrany model \"$selected\" nie jest\n" +
                    "   na liście dostępnych (${models.size} modeli).\n" +
                    "   → Ustawienia > Model AI, wybierz z listy."
            else ->
                "✅ 6. Klucz do \"$providerId\" działa (${models.size} modeli)" +
                    (selected?.let { ", wybrany: $it" } ?: "")
        }
    }

    private suspend fun checkCamera(): String {
        if (manager.connectionState.value != ConnectionState.READY) {
            return "⏭️ 7. Aparat pominięty - okulary nie są połączone."
        }
        val bytes = manager.capturePhoto()
        return if (bytes != null && bytes.size > 1000) {
            "✅ 7. Zdjęcie z okularów przyszło (${bytes.size / 1024} kB)"
        } else {
            "❌ 7. Zdjęcie NIE przyszło.\n" +
                "   → Pytania o to, co widzisz, nie zadziałają.\n" +
                "     Spróbuj rozłączyć i połączyć okulary."
        }
    }

    /** Jedno zdanie na koniec - żeby nie trzeba było czytać całego raportu. */
    private fun verdict(report: String): String = when {
        report.contains("❌") -> "WNIOSEK: coś nie działa - napraw pozycje z ❌ od góry."
        report.contains("⚠️") -> "WNIOSEK: podstawy działają, ale zobacz ostrzeżenia ⚠️."
        else -> "WNIOSEK: cała ścieżka działa. Wybudź okulary i zadaj pytanie."
    }

    private inline fun withSimulator(onMissing: String, block: (GlassesSimulator) -> Unit) {
        val sim = manager.simulatorOrNull()
        if (sim == null) _result.value = onMissing else block(sim)
    }

    // === Testy funkcji - działają tak samo na symulatorze i na sprzęcie ===

    fun testBattery() {
        manager.requestBatteryLevel()
        _result.value = "Wysłano zapytanie o baterię - odpowiedź pojawi się w dzienniku."
    }

    fun testMediaCount() {
        manager.requestMediaCount { images, videos, records ->
            _result.value = "Na okularach: $images zdjęć, $videos wideo, $records nagrań."
        }
    }

    fun testPhoto() = runTest("Zdjęcie") {
        val bytes = manager.capturePhoto()
        if (bytes == null) {
            "Zdjęcie NIE dotarło (brak odpowiedzi w limicie czasu)."
        } else {
            "Zdjęcie OK: ${bytes.size} B, nagłówek ${bytes.take(2).joinToString(" ") { b ->
                "%02X".format(b)
            }}."
        }
    }

    fun testVideo() {
        manager.startVideoRecording()
        _result.value = "Rozpoczęto nagrywanie wideo - zatrzymaj drugim przyciskiem."
    }

    fun stopVideo() {
        manager.stopVideoRecording()
        _result.value = "Zatrzymano nagrywanie wideo."
    }

    fun testAudio() {
        manager.startAudioRecording()
        _result.value = "Rozpoczęto nagrywanie audio."
    }

    fun stopAudio() {
        manager.stopAudioRecording()
        _result.value = "Zatrzymano nagrywanie audio."
    }

    fun testTransferMode() {
        manager.enableTransferMode()
        _result.value = "Włączono tryb transferu - czekam na ramkę 0x08 z adresem IP."
    }

    fun testFileList() = runTest("Lista plików") {
        // Sam włącz tryb transferu i poczekaj na adres - wcześniej trzeba było
        // zrobić to ręcznie osobnym przyciskiem i trafić w moment, w którym
        // okulary już zgłosiły IP.
        if (manager.ensureTransferMode() == null) {
            return@runTest "Okulary nie zgłosiły adresu Wi-Fi Direct w 15 s.\n" +
                "Spróbuj \"Reset P2P\", potem jeszcze raz. Nagrania da się wylistować " +
                "także bez Wi-Fi Direct - patrz karta niżej."
        }
        val files = manager.getMediaFileList()
        if (files.isEmpty()) "Okulary nie zgłosiły żadnych plików."
        else "Plików: ${files.size}. Pierwsze: ${files.take(3).joinToString(", ")}"
    }

    fun testDownloadPhoto() = runTest("Pobranie zdjęcia") {
        if (manager.ensureTransferMode() == null) {
            return@runTest "Okulary nie zgłosiły adresu Wi-Fi Direct w 15 s."
        }
        val bytes = manager.downloadLatestPhoto()
        if (bytes == null) "Nie udało się pobrać zdjęcia przez Wi-Fi Direct."
        else "Pobrano zdjęcie: ${bytes.size} B."
    }

    /**
     * Lista nagrań kanałem BLE - działa bez Wi-Fi Direct, więc jest to droga
     * awaryjna, gdy grupa P2P nie chce się podnieść.
     */
    fun testListRecordings() = runTest("Nagrania (BLE)") {
        val type = _recordingFileType.value
        val list = manager.listRecordings(type)
        if (list.isEmpty()) {
            "Typ $type: brak nagrań. Nagraj coś przyciskiem albo spróbuj innego typu pliku."
        } else {
            "Typ $type: ${list.size} nagrań. Pierwsze: " +
                list.take(3).joinToString(", ") { "${it.fileName} (${it.lengthBytes} B)" }
        }
    }

    fun testDownloadRecording() = runTest("Pobranie nagrania (BLE)") {
        val type = _recordingFileType.value
        val first = manager.listRecordings(type).firstOrNull()
            ?: return@runTest "Typ $type: nie ma czego pobrać."
        val bytes = manager.downloadRecording(first.fileName, type)
        if (bytes == null) "Nie udało się pobrać ${first.fileName}."
        else "Pobrano ${first.fileName}: ${bytes.size} B."
    }

    fun resetP2p() {
        manager.resetP2p()
        _result.value = "Wysłano reset P2P."
    }

    fun clearLog() {
        manager.clearNotifyLog()
        _result.value = null
    }

    /**
     * Uruchamia test w tle i zapisuje wynik. Wyjątki są łapane celowo -
     * ekran diagnostyczny ma pokazać błąd, a nie wywalić aplikację.
     */
    private companion object {
        /** Rozsądny zakres do przeszukania; typów jest niewiele. */
        val RECORDING_FILE_TYPE_RANGE = 0..7

        /** Okno pomiaru strumienia z mikrofonu - tyle, żeby zdążyć wybudzić i coś powiedzieć. */
        const val MIC_TEST_SECONDS = 15
    }

    private fun runTest(label: String, block: suspend () -> String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _result.value = "$label: w toku..."
            _result.value = try {
                block()
            } catch (e: Exception) {
                "$label - błąd: ${e.message ?: e::class.java.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }
}
