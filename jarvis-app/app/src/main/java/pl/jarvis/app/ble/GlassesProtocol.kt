package pl.jarvis.app.ble

/**
 * Protokół komunikacji z okularami HeyCyan - czyste kodowanie i dekodowanie.
 *
 * Wydzielone z [JarvisManager], żeby dało się to przetestować bez Androida
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

    /** Zakres jakości miniatury akceptowany przez okulary. */
    val THUMBNAIL_QUALITY_RANGE = 0..6

    // === Typy ramek notify (loadData[6]) ===

    const val NOTIFY_PHOTO_READY = 0x02
    const val NOTIFY_AI_BUTTON = 0x03
    const val NOTIFY_OTA_PROGRESS = 0x04
    const val NOTIFY_BATTERY = 0x05
    const val NOTIFY_GLASSES_IP = 0x08
    const val NOTIFY_P2P_ERROR = 0x09
    const val NOTIFY_PAUSE = 0x0C
    const val NOTIFY_UNBIND = 0x0D
    const val NOTIFY_LOW_MEMORY = 0x0E

    /** Indeks bajtu typu zdarzenia w ramce notify. */
    const val NOTIFY_TYPE_INDEX = 6

    /** Klucz nasłuchu ogólnych ramek notify w LargeDataHandler. */
    const val DEVICE_NOTIFY_KEY = 100

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
            NOTIFY_PAUSE -> NotifyEvent.Paused
            NOTIFY_UNBIND -> NotifyEvent.Unbound

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
    object Paused : NotifyEvent()
    object Unbound : NotifyEvent()

    /** Ramka poprawna, ale typ nieobsługiwany. */
    data class Unknown(val type: Int) : NotifyEvent()

    /** Ramka za krótka albo pusta. */
    data class Malformed(val size: Int) : NotifyEvent()
}
