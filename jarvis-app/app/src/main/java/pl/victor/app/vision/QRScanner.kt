package pl.victor.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Skaner QR kodów - używa ML Kit (offline, darmowy).
 *
 * Obsługuje: QR_CODE, EAN_13, EAN_8, CODE_128, DATA_MATRIX, PDF417, AZTEC
 * W MVP skupiamy się na QR_CODE (najpopularniejszy).
 */
class QRScanner {

    private val tag = "QRScanner"

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_AZTEC
        )
        .build()

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(options)

    /**
     * Skanuje kody z bitmapy.
     *
     * Wcześniej ta metoda była zaślepką: uruchamiała skan, ignorowała wynik
     * i **zawsze** zwracała pustą listę. Kod, który jej użył, po cichu nie
     * znajdował żadnego kodu. Teraz czeka na wynik ML Kit na wątku IO.
     *
     * @return znalezione kody (zazwyczaj 0 lub 1)
     */
    suspend fun scan(bitmap: Bitmap): List<ScannedCode> =
        withContext(Dispatchers.IO) { scanSync(bitmap) }

    /** Skanuje kody z bajtów obrazu (JPEG/PNG). */
    suspend fun scanImageBytes(imageBytes: ByteArray): List<ScannedCode> =
        withContext(Dispatchers.IO) { scanImageBytesSync(imageBytes) }

    /** Skanuje kody z pliku na dysku. */
    suspend fun scanFile(path: String): List<ScannedCode> = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(path) ?: return@withContext emptyList()
        scanSync(bitmap)
    }

    /**
     * Sync wrapper - blokuje na wyniku (używać tylko z coroutine na IO dispatcher).
     *
     * Uwaga: ML Kit jest natywnie async. Ten wrapper używa CompletableFuture.
     * Zwraca pustą listę jeśli timeout.
     */
    fun scanSync(bitmap: Bitmap, timeoutMs: Long = 3000): List<ScannedCode> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val task = scanner.process(image)

        return try {
            // ML Kit zwraca Task z Play Services, nie java.util.concurrent.Future.
            val barcodes = Tasks.await(task, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            barcodes.map { barcode ->
                ScannedCode(
                    rawValue = barcode.rawValue ?: "",
                    format = formatName(barcode.format),
                    url = barcode.url?.url,
                    type = barcodeValueType(barcode.valueType)
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "QR scan failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Convenience: skanuj z bajtów.
     */
    fun scanImageBytesSync(imageBytes: ByteArray): List<ScannedCode> {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return emptyList()
        return scanSync(bitmap)
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "UNKNOWN($format)"
    }

    private fun barcodeValueType(type: Int): String = when (type) {
        Barcode.TYPE_URL -> "URL"
        Barcode.TYPE_EMAIL -> "EMAIL"
        Barcode.TYPE_PHONE -> "PHONE"
        Barcode.TYPE_SMS -> "SMS"
        Barcode.TYPE_WIFI -> "WIFI"
        Barcode.TYPE_GEO -> "GEO"
        Barcode.TYPE_CALENDAR_EVENT -> "CALENDAR"
        Barcode.TYPE_CONTACT_INFO -> "CONTACT"
        Barcode.TYPE_TEXT -> "TEXT"
        else -> "OTHER"
    }

    fun close() {
        scanner.close()
    }
}

/**
 * Wynik skanowania QR.
 */
data class ScannedCode(
    val rawValue: String,
    val format: String,
    val url: String? = null,
    val type: String = "TEXT"
) {
    /**
     * Human-readable opis kodu.
     */
    fun describe(): String = when (type) {
        "URL" -> "Link: $url"
        "EMAIL" -> "Email: $rawValue"
        "PHONE" -> "Telefon: $rawValue"
        "WIFI" -> "WiFi: $rawValue"
        "CONTACT" -> "Wizytówka: $rawValue"
        else -> rawValue
    }
}
