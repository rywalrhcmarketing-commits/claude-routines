package pl.victor.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Zmniejsza zdjęcie przed wysłaniem go do modelu.
 *
 * ## Po co
 * Zdjęcie w pełnej rozdzielczości idzie z okularów przez Wi-Fi, a potem w całości
 * do modelu - i to jest najcięższy element całej tury. Tekst na kartce zostaje
 * jednak czytelny po zmniejszeniu dwu- czy trzykrotnym, bo model nie potrzebuje
 * pełnej matrycy, tylko ostrych krawędzi liter.
 *
 * Zgłoszone wprost: "wolałbym, żeby było zmniejszone np. 2 lub 3x, żeby tekst
 * nadal był czytelny dla AI, ale żeby mniej ważyło i przesyłało się szybciej".
 *
 * ## Dlaczego dzielnik, a nie docelowa szerokość
 * Bo okulary mogą oddać zdjęcie o różnej rozdzielczości, a użytkownik myśli w
 * kategoriach "dwa razy mniejsze", nie "tysiąc dwieście pikseli". Dzielnik daje
 * przewidywalny efekt niezależnie od tego, co przyszło z aparatu.
 */
object PhotoScaler {

    /**
     * Zmniejsza obraz [divisor] razy w każdym wymiarze.
     *
     * Gdy cokolwiek pójdzie nie tak - nieznany format, brak pamięci, dzielnik
     * jeden - oddaje oryginał. Gorsze zdjęcie jest lepsze niż brak zdjęcia, więc
     * ta funkcja nigdy nie zwraca `null`.
     *
     * @param jpeg zdjęcie w formacie, który rozumie [BitmapFactory]
     * @param divisor ile razy zmniejszyć; 1 albo mniej oddaje oryginał
     * @param quality jakość zapisu JPEG (0-100)
     */
    fun shrink(jpeg: ByteArray, divisor: Int, quality: Int = DEFAULT_QUALITY): ByteArray {
        if (divisor <= 1 || jpeg.isEmpty()) return jpeg
        return try {
            // inSampleSize dekoduje OD RAZU mniejszy obraz, więc pełna bitmapa
            // nigdy nie ląduje w pamięci. Rozumie tylko potęgi dwójki, stąd
            // ewentualne doskalowanie niżej.
            val options = BitmapFactory.Options().apply {
                inSampleSize = largestPowerOfTwoAtMost(divisor)
            }
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                ?: return jpeg
            val scaled = if (options.inSampleSize == divisor) {
                decoded
            } else {
                // Dzielnik nie jest potęgą dwójki (np. 3) - resztę dobieramy
                // zwykłym skalowaniem, licząc od WYMIARÓW ORYGINAŁU, nie od już
                // zmniejszonych, żeby wynik zgadzał się z tym, o co poproszono.
                val targetWidth = (decoded.width * options.inSampleSize / divisor).coerceAtLeast(1)
                val targetHeight = (decoded.height * options.inSampleSize / divisor).coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                    .also { if (it !== decoded) decoded.recycle() }
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            scaled.recycle()
            val result = out.toByteArray()
            // Zmniejszanie, które powiększa plik, jest bez sensu - zdarza się przy
            // bardzo małych miniaturach, gdzie sam nagłówek JPEG waży więcej niż
            // oszczędność.
            if (result.isEmpty() || result.size >= jpeg.size) jpeg else result
        } catch (e: Throwable) {
            Log.w("PhotoScaler", "Nie udało się zmniejszyć zdjęcia", e)
            jpeg
        }
    }

    /** Największa potęga dwójki nie większa niż [value] - dla inSampleSize. */
    fun largestPowerOfTwoAtMost(value: Int): Int {
        if (value <= 1) return 1
        var result = 1
        while (result * 2 <= value) result *= 2
        return result
    }

    /**
     * Jakość zapisu.
     *
     * Osiemdziesiąt pięć, nie sto: powyżej tego plik rośnie szybciej niż ostrość
     * liter, a to właśnie litery są tu powodem całej operacji.
     */
    const val DEFAULT_QUALITY = 85
}
