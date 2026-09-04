package pl.victor.app.ble

/**
 * Protokół komunikacji z okularami HeyCyan - czyste kodowanie i dekodowanie.
 *
 * Wydzielone z [VictorManager], żeby dało się to przetestować bez Androida
 * i bez sprzętu. To jest jedyne miejsce, w którym siedzą surowe bajty -
 * reszta aplikacji operuje na typach z [NotifyEvent].
 *
 * Źródła: oficjalny przewodnik SDK producenta oraz aplikacja referencyjna
 * CyanBridge (github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK).
 *
 * ## Komendy
 * Wszystkie sterujące mają postać `0x02 0x01 <tryb>`; wyjątkiem jest
 * zapytanie o liczbę plików (`0x02 0x04`) oraz zdjęcie AI, które dokłada
 * jakość miniatury i bajt domykający.
 *
 * ## Ramki notify
 * Bajt `loadData[6]` niesie typ zdarzenia, dalsze bajty jego dane.
 */
object GlassesProtocol {

    // === Tryby pracy (drugi bajt komendy) ===

    const val WORK_PHOTO = 0x01
    const val WORK_VIDEO_START = 0x02
    const val WORK_VIDEO_STOP = 0x03
    const val WORK_TRANSFER = 0x04
    const val WORK_OTA = 0x05
    const val WORK_AI_PHOTO = 0x06
    const val WORK_AUDIO_START = 0x08
    const val WORK_AUDIO_STOP = 0x0C
    const val WORK_RESET_P2P = 0x0F

    // === Komendy eksperymentalne (Opcje programistyczne - Live Stream Lab) ===
    //
    // Znaczenie WORK_EXPERIMENTAL_07 i WORK_EXPERIMENTAL_0D jest NIEPOTWIERDZONE -
    // to jedyne dwa bajty spoza tabeli powyżej, które nie są potwierdzone jako coś
    // niebezpiecznego (w przeciwieństwie do 0x0A, który oficjalna apka potwierdza
    // jako factory reset). Wolno je wysyłać wyłącznie z gated panelu developerskiego,
    // pojedynczo, po potwierdzeniu, na sprzęcie przeznaczonym do testów.
    // WORK_RESTART_DEVICE jest za to potwierdzone (restart urządzenia) - to jedyna
    // bezpieczna komenda odzyskiwania w tym panelu.

    /** Nieznane - kandydat na aktywację trybu 8 (live streaming) wg analizy firmware. */
    const val WORK_EXPERIMENTAL_07 = 0x07

    /** Nieznane - kandydat na aktywację trybu 8 (live streaming) wg analizy firmware. */
    const val WORK_EXPERIMENTAL_0D = 0x0D

    /** Potwierdzone w oficjalnej apce jako restart urządzenia. */
    const val WORK_RESTART_DEVICE = 0x0E

    /** Zakres jakości miniatury akceptowany przez okulary. */
    val THUMBNAIL_QUALITY_RANGE = 0..6

    // === Typy ramek notify (loadData[6]) ===

    const val NOTIFY_PHOTO_READY = 0x02
    const val NOTIFY_AI_BUTTON = 0x03
    const val NOTIFY_OTA_PROGRESS = 0x04
    const val NOTIFY_BATTERY = 0x05
    const val NOTIFY_GLASSES_IP = 0x08
    const val NOTIFY_P2P_ERROR = 0x09

    /**
     * Użytkownik przerwał wypowiedź (dotknięcie zauszników w trakcie mówienia).
     * Aplikacja producenta robi tu `stopRealTimeTTS` + `setUserInterruptsAudio`,
     * czyli traktuje to jako "zamilcz", a nie jako pauzę odtwarzacza.
     */
    const val NOTIFY_INTERRUPT_SPEECH = 0x0C
    const val NOTIFY_UNBIND = 0x0D
    const val NOTIFY_LOW_MEMORY = 0x0E

    // === Typy odczytane z aplikacji producenta (Prism Pro) ===
    // Tabela skoków w MainActivity$MyDeviceNotifyListener obsługuje typy 0x02..0x18.
    // Poniższe były u nas nieobsługiwane - okulary je wysyłały, a aplikacja
    // milczała. Najważniejsze są dwa ostatnie: to one uruchamiają rozmowę.

    /** Okulary przerwały rozpoznawanie obrazu (`deviceIdentificationStop`). */
    const val NOTIFY_IDENTIFICATION_STOP = 0x0A

    /** Zmiana głośności zauszniki -> telefon (`setVolumeControl`). */
    const val NOTIFY_VOLUME_CHANGED = 0x12

    /** Kąt kamery (`setGimbalCameraAngle`). */
    const val NOTIFY_CAMERA_ANGLE = 0x16

    /**
     * Okulary proszą o rozpoczęcie rozmowy z AI - wariant pierwszy.
     *
     * Producent w obu wariantach (0x17 i 0x18) robi to samo: sprawdza sieć,
     * gra dźwięk przez `aiVoicePlay`, czyści kolejkę TTS i startuje
     * rozpoznawanie mowy. To jest zdarzenie wybudzenia - bez jego obsługi
     * `aiVoiceWake(true)` włącza detekcję w okularach, ale aplikacja nigdy się
     * o niej nie dowiaduje.
     */
    const val NOTIFY_AI_SESSION_A = 0x17

    /** Okulary proszą o rozpoczęcie rozmowy z AI - wariant drugi. */
    const val NOTIFY_AI_SESSION_B = 0x18

    /**
     * Bajt trybu w ramce rozpoczęcia rozmowy. `1` oznacza u producenta tryb
     * tekstu na żywo (tłumaczenie), cokolwiek innego - zwykłe pytanie do AI.
     */
    const val AI_SESSION_MODE_INDEX = 7

    /** Indeks bajtu typu zdarzenia w ramce notify. */
    const val NOTIFY_TYPE_INDEX = 6

    /** Klucz nasłuchu ogólnych ramek notify w LargeDataHandler. */
    const val DEVICE_NOTIFY_KEY = 100

    // === Kody dla aiVoicePlay (sterowanie dźwiękiem po stronie okularów) ===
    // Odczytane z Prism Pro; SDK pakuje je jako [0x02, kod] pod nagłówkiem 0x48.
    // Świadomie NIE zgadujemy tu "dźwięku powitalnego" - producent go nie gra,
    // tylko ucisza to, co akurat leci, zanim zacznie nową rozmowę.

    /** Wstrzymaj to, co okulary właśnie odtwarzają. */
    const val TONE_PAUSE_PLAYBACK = 0x02

    /** Zatrzymaj odtwarzanie - producent woła to na starcie nowej rozmowy. */
    const val TONE_STOP_PLAYBACK = 0x03

    /** Komunikat błędu - producent gra go, gdy nie ma sieci. */
    const val TONE_ERROR = 0xF1

    /** `dataType == 4` w odpowiedzi na komendę oznacza liczniki plików. */
    const val DATA_TYPE_MEDIA_COUNT = 4

    // === Kodowanie komend ===

    /** Prosta komenda sterująca: `0x02 0x01 <tryb>`. */
    fun command(workType: Int): ByteArray =
        byteArrayOf(0x02, 0x01, workType.toByte())

    fun takePhoto(): ByteArray = command(WORK_PHOTO)
    fun startVideo(): ByteArray = command(WORK_VIDEO_START)
    fun stopVideo(): ByteArray = command(WORK_VIDEO_STOP)
    fun startAudio(): ByteArray = command(WORK_AUDIO_START)
    fun stopAudio(): ByteArray = command(WORK_AUDIO_STOP)
    fun enableTransferMode(): ByteArray = command(WORK_TRANSFER)
    fun resetP2p(): ByteArray = command(WORK_RESET_P2P)

    /** Eksperymentalna, niepotwierdzona komenda - patrz [WORK_EXPERIMENTAL_07]. */
    fun experimental07(): ByteArray = command(WORK_EXPERIMENTAL_07)

    /** Eksperymentalna, niepotwierdzona komenda - patrz [WORK_EXPERIMENTAL_0D]. */
    fun experimental0D(): ByteArray = command(WORK_EXPERIMENTAL_0D)

    /** Restart urządzenia - potwierdzone, bezpieczne odzyskiwanie. */
    fun restartDevice(): ByteArray = command(WORK_RESTART_DEVICE)

    /**
     * Zdjęcie AI wraz z odesłaniem miniatury.
     * Producent wymaga sześciu bajtów: jakość podawana jest dwukrotnie,
     * a ramkę domyka `0x02`.
     *
     * @param quality 0..6; wartości spoza zakresu są przycinane
     */
    fun captureAiPhoto(quality: Int): ByteArray {
        val q = quality.coerceIn(THUMBNAIL_QUALITY_RANGE).toByte()
        return byteArrayOf(0x02, 0x01, WORK_AI_PHOTO.toByte(), q, q, 0x02)
    }

    /** Zapytanie o liczbę niezsynchronizowanych plików. */
    fun requestMediaCount(): ByteArray = byteArrayOf(0x02, 0x04)

    // === Dekodowanie komend wychodzących (symulator, diagnostyka) ===

    /**
     * Wyciąga tryb pracy z komendy sterującej `0x02 0x01 <tryb>`.
     * @return tryb albo `null` gdy to nie jest komenda sterująca
     */
    fun workTypeOf(command: ByteArray?): Int? {
        if (command == null || command.size < 3) return null
        if (command[0].toInt() != 0x02 || command[1].toInt() != 0x01) return null
        return command[2].toInt() and 0xFF
    }

    fun isMediaCountRequest(command: ByteArray?): Boolean =
        command != null && command.size >= 2 &&
            command[0].toInt() == 0x02 && command[1].toInt() == 0x04

    /** Opis komendy po polsku - dla ekranu diagnostycznego i logów. */
    fun describeCommand(command: ByteArray?): String {
        if (command == null || command.isEmpty()) return "(pusta komenda)"
        if (isMediaCountRequest(command)) return "Zapytanie o liczbę plików"
        return when (workTypeOf(command)) {
            WORK_PHOTO -> "Zdjęcie"
            WORK_VIDEO_START -> "Start nagrywania wideo"
            WORK_VIDEO_STOP -> "Stop nagrywania wideo"
            WORK_TRANSFER -> "Tryb transferu (Wi-Fi Direct)"
            WORK_OTA -> "Aktualizacja firmware (OTA)"
            WORK_AI_PHOTO -> "Zdjęcie AI z miniaturą" +
                if (command.size > 3) " (jakość ${command[3].toInt() and 0xFF})" else ""
            WORK_AUDIO_START -> "Start nagrywania audio"
            WORK_AUDIO_STOP -> "Stop nagrywania audio"
            WORK_RESET_P2P -> "Reset P2P"
            WORK_EXPERIMENTAL_07 -> "[EKSPERYMENT] Nieznana komenda 0x07"
            WORK_EXPERIMENTAL_0D -> "[EKSPERYMENT] Nieznana komenda 0x0D"
            WORK_RESTART_DEVICE -> "Restart urządzenia"
            else -> "Nieznana komenda: ${formatFrame(command)}"
        }
    }

    // === Budowanie ramek notify (symulator, testy) ===

    /**
     * Składa ramkę notify tak, jak przysłałyby ją okulary.
     *
     * Bajty 0..5 to nagłówek vendor SDK, którego nie parsujemy - wypełniamy je
     * markerem `SIM`, żeby na ekranie diagnostycznym od razu było widać, że
     * ramka pochodzi z symulatora, a nie ze sprzętu.
     */
    fun notifyFrame(type: Int, vararg payload: Int): ByteArray {
        val frame = ByteArray(NOTIFY_TYPE_INDEX + 1 + payload.size)
        SIMULATED_HEADER.copyInto(frame)
        frame[NOTIFY_TYPE_INDEX] = type.toByte()
        payload.forEachIndexed { i, value ->
            frame[NOTIFY_TYPE_INDEX + 1 + i] = value.toByte()
        }
        return frame
    }

    fun photoReadyFrame(): ByteArray = notifyFrame(NOTIFY_PHOTO_READY)

    fun buttonPressedFrame(): ByteArray = notifyFrame(NOTIFY_AI_BUTTON, 1)

    fun batteryFrame(level: Int, charging: Boolean): ByteArray =
        notifyFrame(NOTIFY_BATTERY, level.coerceIn(0, 100), if (charging) 1 else 0)

    /**
     * @throws IllegalArgumentException gdy adres nie jest poprawnym IPv4 -
     *         lepiej wysypać się w teście niż wysłać ramkę, której nikt nie zdekoduje
     */
    fun glassesIpFrame(ip: String): ByteArray {
        val octets = ip.split('.')
        require(octets.size == 4) { "Oczekiwano adresu IPv4, dostano: $ip" }
        val values = octets.map { part ->
            val value = part.toIntOrNull()
            require(value != null && value in 0..255) { "Niepoprawny oktet '$part' w adresie $ip" }
            value
        }
        return notifyFrame(NOTIFY_GLASSES_IP, values[0], values[1], values[2], values[3])
    }

    fun otaProgressFrame(download: Int, soc: Int, nor: Int): ByteArray =
        notifyFrame(NOTIFY_OTA_PROGRESS, download, soc, nor)

    fun p2pErrorFrame(code: Int): ByteArray = notifyFrame(NOTIFY_P2P_ERROR, code)

    fun lowMemoryFrame(): ByteArray = notifyFrame(NOTIFY_LOW_MEMORY)

    /**
     * Ramka "okulary proszą o rozmowę" - do symulatora i testów, żeby dało się
     * przejść całą ścieżkę wybudzenia bez sprzętu na głowie.
     */
    fun aiSessionFrame(realtimeText: Boolean = false): ByteArray =
        notifyFrame(NOTIFY_AI_SESSION_A, if (realtimeText) 1 else 0)

    /** Ramka "użytkownik uciszył asystenta". */
    fun interruptSpeechFrame(): ByteArray = notifyFrame(NOTIFY_INTERRUPT_SPEECH)

    /** Ramka zmiany głośności na zausznikach. */
    fun volumeFrame(level: Int): ByteArray =
        notifyFrame(NOTIFY_VOLUME_CHANGED, level.coerceIn(0, 100))

    /** Marker `SIM` w nagłówku - patrz [notifyFrame]. */
    private val SIMULATED_HEADER = byteArrayOf(0x53, 0x49, 0x4D, 0x00, 0x00, 0x00)

    // === Dekodowanie ramek notify ===

    /**
     * Rozkłada ramkę notify na zdarzenie.
     *
     * @return zdarzenie albo [NotifyEvent.Malformed] gdy ramka jest za krótka,
     *         albo [NotifyEvent.Unknown] dla typu, którego nie obsługujemy
     */
    fun decodeNotify(loadData: ByteArray?): NotifyEvent {
        if (loadData == null || loadData.size <= NOTIFY_TYPE_INDEX) {
            return NotifyEvent.Malformed(loadData?.size ?: 0)
        }

        return when (val type = loadData[NOTIFY_TYPE_INDEX].toIntUnsigned()) {
            NOTIFY_PHOTO_READY -> NotifyEvent.PhotoReady

            NOTIFY_AI_BUTTON ->
                if (loadData.size > 7 && loadData[7].toIntUnsigned() == 1) {
                    NotifyEvent.ButtonPressed
                } else {
                    NotifyEvent.Unknown(type)
                }

            NOTIFY_BATTERY ->
                if (loadData.size > 8) {
                    NotifyEvent.Battery(
                        level = loadData[7].toIntUnsigned(),
                        charging = loadData[8].toIntUnsigned() == 1
                    )
                } else {
                    NotifyEvent.Malformed(loadData.size)
                }

            NOTIFY_GLASSES_IP ->
                if (loadData.size > 10) {
                    NotifyEvent.GlassesIp(
                        buildString {
                            append(loadData[7].toIntUnsigned()).append('.')
                            append(loadData[8].toIntUnsigned()).append('.')
                            append(loadData[9].toIntUnsigned()).append('.')
                            append(loadData[10].toIntUnsigned())
                        }
                    )
                } else {
                    NotifyEvent.Malformed(loadData.size)
                }

            NOTIFY_P2P_ERROR -> NotifyEvent.P2pError(
                code = if (loadData.size > 7) loadData[7].toIntUnsigned() else -1
            )

            NOTIFY_OTA_PROGRESS ->
                if (loadData.size > 9) {
                    NotifyEvent.OtaProgress(
                        download = loadData[7].toIntUnsigned(),
                        soc = loadData[8].toIntUnsigned(),
                        nor = loadData[9].toIntUnsigned()
                    )
                } else {
                    NotifyEvent.Malformed(loadData.size)
                }

            NOTIFY_LOW_MEMORY -> NotifyEvent.LowMemory
            NOTIFY_INTERRUPT_SPEECH -> NotifyEvent.SpeechInterrupted
            NOTIFY_UNBIND -> NotifyEvent.Unbound

            NOTIFY_IDENTIFICATION_STOP -> NotifyEvent.IdentificationStopped

            NOTIFY_VOLUME_CHANGED -> NotifyEvent.VolumeChanged(
                level = if (loadData.size > 7) loadData[7].toIntUnsigned() else -1
            )

            NOTIFY_CAMERA_ANGLE -> NotifyEvent.CameraAngle(
                angle = if (loadData.size > 7) loadData[7].toIntUnsigned() else -1
            )

            NOTIFY_AI_SESSION_A, NOTIFY_AI_SESSION_B -> NotifyEvent.AiSessionRequested(
                realtimeText = loadData.size > AI_SESSION_MODE_INDEX &&
                    loadData[AI_SESSION_MODE_INDEX].toIntUnsigned() == 1
            )

            else -> NotifyEvent.Unknown(type)
        }
    }

    /** Bajty w Kotlinie są ze znakiem, a protokół operuje na 0..255. */
    private fun Byte.toIntUnsigned(): Int = this.toInt() and 0xFF

    /** Czytelny podgląd ramki - do logów i ekranu diagnostycznego. */
    fun formatFrame(loadData: ByteArray?): String {
        if (loadData == null || loadData.isEmpty()) return "(pusta ramka)"
        return loadData.joinToString(" ") { "%02X".format(it) }
    }
}

/**
 * Zdarzenie odebrane z okularów.
 *
 * `Unknown` i `Malformed` są celowo osobne: pierwsze oznacza ramkę poprawną,
 * ale nieobsługiwaną (nowy firmware), drugie ramkę uszkodzoną lub za krótką.
 */
sealed class NotifyEvent {
    /** Okulary zrobiły zdjęcie i miniatura jest gotowa do pobrania. */
    object PhotoReady : NotifyEvent()

    /** Wciśnięto fizyczny przycisk AI. */
    object ButtonPressed : NotifyEvent()

    data class Battery(val level: Int, val charging: Boolean) : NotifyEvent()

    /** Adres okularów w grupie Wi-Fi Direct. */
    data class GlassesIp(val ip: String) : NotifyEvent()

    /** Błąd Wi-Fi Direct; kod 255 bywa zgłaszany rutynowo. */
    data class P2pError(val code: Int) : NotifyEvent()

    data class OtaProgress(val download: Int, val soc: Int, val nor: Int) : NotifyEvent()

    object LowMemory : NotifyEvent()

    /** Użytkownik uciszył V.I.C.T.O.R.-a dotknięciem zauszników. */
    object SpeechInterrupted : NotifyEvent()

    object Unbound : NotifyEvent()

    /** Okulary przerwały rozpoznawanie obrazu. */
    object IdentificationStopped : NotifyEvent()

    /** Zmieniono głośność na zausznikach. */
    data class VolumeChanged(val level: Int) : NotifyEvent()

    /** Zgłoszony kąt kamery. */
    data class CameraAngle(val angle: Int) : NotifyEvent()

    /**
     * Okulary proszą o rozmowę: użytkownik powiedział słowo wybudzenia albo
     * przytrzymał zausznik.
     *
     * @param realtimeText tryb tekstu na żywo (tłumaczenie) zamiast pytania do AI
     */
    data class AiSessionRequested(val realtimeText: Boolean) : NotifyEvent()

    /** Ramka poprawna, ale typ nieobsługiwany. */
    data class Unknown(val type: Int) : NotifyEvent()

    /** Ramka za krótka albo pusta. */
    data class Malformed(val size: Int) : NotifyEvent()
}
