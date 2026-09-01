package pl.victor.app.ble

import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.entity.RecordEntity
import com.oudmon.ble.base.communication.file.IRecordCallback
import com.oudmon.ble.base.communication.file.RecordHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Pobieranie nagrań głosowych z okularów **przez BLE**, bez Wi-Fi Direct.
 *
 * Vendor SDK ma na to osobny kanał (`RecordHandle`), niezależny od
 * `LargeDataHandler`, którym idą komendy i ramki notify. To jedyna droga do
 * nagrań, gdy Wi-Fi Direct nie chce się podnieść - a na części telefonów
 * nie chce.
 *
 * ## Czego nie wiemy
 * `start(fileType)` i `readRecordFile(fileType, nazwa)` przyjmują numer typu
 * pliku, którego producent nigdzie nie udokumentował, a w SDK inicjalizuje się
 * on na `0`. Dlatego typ jest tu **parametrem**, a nie wbudowaną stałą:
 * właściwą wartość ustala się doświadczalnie na sprzęcie (ekran diagnostyczny
 * ma do tego selektor). Zgadywanie stałej i wpisanie jej na sztywno byłoby
 * gorsze niż przyznanie, że jej nie znamy.
 *
 * ## Współbieżność
 * `RecordHandle` to singleton z jednym gniazdem callbacku, więc operacje są
 * szeregowane mutexem. Po każdej sesji callback jest kasowany - inaczej
 * następna operacja dostawałaby dane poprzedniej.
 */
class GlassesRecordings(
    private val handle: RecordHandle = RecordHandle.getInstance(),
    private val largeDataHandler: LargeDataHandler = LargeDataHandler.getInstance()
) {

    private val mutex = Mutex()

    /** Postęp pobierania: 0.0 - 1.0, albo `null` gdy nic się nie dzieje. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /**
     * Pobiera listę nagrań z okularów.
     *
     * @param fileType numer typu pliku - patrz uwaga o nieudokumentowanych typach
     * @return lista nagrań albo pusta, gdy okulary nie odpowiedziały w limicie czasu
     */
    suspend fun list(fileType: Int = DEFAULT_FILE_TYPE): List<Recording> = mutex.withLock {
        val result = CompletableDeferred<List<Recording>>()

        val callback = object : RecordCallbackAdapter() {
            override fun onFileNames(names: ArrayList<RecordEntity>?) {
                val list = names.orEmpty().map { Recording(it.fileName.orEmpty(), it.length) }
                if (!result.isCompleted) result.complete(list)
            }

            override fun onActionResult(code: Int) {
                if (code != 0 && !result.isCompleted) {
                    Log.w(TAG, "Lista nagrań: okulary zgłosiły błąd (kod=$code)")
                    result.complete(emptyList())
                }
            }
        }

        session(callback) {
            handle.start(fileType)
            withTimeoutOrNull(LIST_TIMEOUT_MS) { result.await() } ?: run {
                Log.w(TAG, "Lista nagrań: brak odpowiedzi w ${LIST_TIMEOUT_MS} ms")
                emptyList()
            }
        }
    }

    /**
     * Pobiera pojedyncze nagranie.
     *
     * @return bajty pliku albo `null` gdy transfer się nie udał
     */
    suspend fun download(
        fileName: String,
        fileType: Int = DEFAULT_FILE_TYPE
    ): ByteArray? = mutex.withLock {
        val output = ByteArrayOutputStream()
        val done = CompletableDeferred<Boolean>()

        val callback = object : RecordCallbackAdapter() {
            override fun onReceiver(data: ByteArray?) {
                if (data != null && data.isNotEmpty()) output.write(data)
            }

            override fun onProgress(fraction: Float) {
                _progress.value = fraction.coerceIn(0f, 1f)
            }

            override fun onComplete() {
                if (!done.isCompleted) done.complete(output.size() > 0)
            }

            override fun onActionResult(code: Int) {
                if (code != 0 && !done.isCompleted) {
                    Log.w(TAG, "Pobieranie nagrania: błąd urządzenia (kod=$code)")
                    done.complete(false)
                }
            }
        }

        session(callback) {
            _progress.value = 0f
            try {
                handle.readRecordFile(fileType, fileName)
                val ok = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { done.await() }
                if (ok != true) {
                    Log.w(TAG, "Pobieranie nagrania '$fileName' nie powiodło się")
                    null
                } else {
                    output.toByteArray().takeIf { it.isNotEmpty() }
                }
            } finally {
                _progress.value = null
            }
        }
    }

    /**
     * Zestawia sesję `RecordHandle` i sprząta po niej.
     *
     * `initRegister()` podmienia callback w [com.oudmon.ble.base.bluetooth.BleOperateManager],
     * a to jedno gniazdo. Sprawdzone: `LargeDataHandler` z niego nie korzysta
     * (trzyma własną mapę nasłuchów), więc ramki notify przeżywają sesję.
     * `initEnable()` na końcu i tak przywraca nasłuch - taniej niż ryzykować.
     */
    private inline fun <T> session(callback: IRecordCallback, block: () -> T): T {
        handle.clearCallback()
        handle.registerCallback(callback)
        handle.initRegister()
        return try {
            block()
        } finally {
            runCatching { handle.endAndRelease() }
                .onFailure { Log.w(TAG, "endAndRelease nie powiodło się", it) }
            handle.clearCallback()
            runCatching { largeDataHandler.initEnable() }
                .onFailure { Log.w(TAG, "Przywrócenie nasłuchu notify nie powiodło się", it) }
        }
    }

    companion object {
        private const val TAG = "GlassesRecordings"

        /**
         * SDK inicjalizuje `currFileType` na 0, więc to najlepszy punkt startu.
         * Jeśli okulary zwrócą pustą listę mimo nagrań w pamięci, spróbuj innych
         * wartości z ekranu diagnostycznego.
         */
        const val DEFAULT_FILE_TYPE = 0

        private const val LIST_TIMEOUT_MS = 10_000L
        private const val DOWNLOAD_TIMEOUT_MS = 120_000L
    }
}

/**
 * Pusta implementacja [IRecordCallback] - interfejs wymaga pięciu metod,
 * a każde użycie potrzebuje dwóch albo trzech.
 */
private abstract class RecordCallbackAdapter : IRecordCallback {
    override fun onFileNames(names: ArrayList<RecordEntity>?) = Unit
    override fun onProgress(fraction: Float) = Unit
    override fun onComplete() = Unit
    override fun onReceiver(data: ByteArray?) = Unit
    override fun onActionResult(code: Int) = Unit
}
