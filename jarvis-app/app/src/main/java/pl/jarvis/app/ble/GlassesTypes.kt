package pl.jarvis.app.ble

/**
 * Typy opisujące okulary - wydzielone z [JarvisManager], bo nie zależą od Androida
 * i dzięki temu [GlassesSimulator] oraz testy jednostkowe mogą ich używać
 * bez ciągnięcia za sobą vendor SDK.
 */

/** Liczba niezsynchronizowanych plików w pamięci okularów. */
data class MediaCount(
    val images: Int,
    val videos: Int,
    val records: Int
) {
    val total: Int get() = images + videos + records
}

/** Urządzenie znalezione podczas skanowania BLE. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int
)

/** Stan połączenia z okularami. */
enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    READY,
    ERROR
}

/** Zdarzenie z fizycznego przycisku na okularach. */
sealed class ButtonEvent {
    object ShortClick : ButtonEvent()
    object DoubleClick : ButtonEvent()
    object TripleClick : ButtonEvent()
    object LongPress : ButtonEvent()
    object Release : ButtonEvent()
}

/** Błąd warstwy komunikacji z okularami. */
class JarvisException(message: String) : Exception(message)

/**
 * Wpis dziennika ramek notify - surowy hex plus odczytane znaczenie.
 * Używany przez ekran diagnostyczny; pozwala porównać, co przysłały okulary,
 * z tym, jak to zrozumiał [GlassesProtocol].
 */
data class NotifyLogEntry(
    val timestampMs: Long,
    val hex: String,
    val meaning: String
)
