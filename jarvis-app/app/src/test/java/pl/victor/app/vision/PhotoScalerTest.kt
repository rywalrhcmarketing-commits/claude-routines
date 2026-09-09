package pl.victor.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sam dobór inSampleSize da się sprawdzić bez Androida, a pomyłka tutaj daje
 * zdjęcie zmniejszone dwa razy bardziej albo mniej, niż poprosił użytkownik.
 */
class PhotoScalerTest {

    @Test
    fun `potegi dwojki zostaja soba`() {
        assertEquals(1, PhotoScaler.largestPowerOfTwoAtMost(1))
        assertEquals(2, PhotoScaler.largestPowerOfTwoAtMost(2))
        assertEquals(4, PhotoScaler.largestPowerOfTwoAtMost(4))
        assertEquals(8, PhotoScaler.largestPowerOfTwoAtMost(8))
    }

    @Test
    fun `dzielnik trzy schodzi do dwoch`() {
        // Kluczowy przypadek: użytkownik prosi o 3x, dekoder umie 2x, resztę
        // dobiera skalowanie. Gdyby wyszło 4, zdjęcie byłoby wyraźnie mniejsze
        // niż zamówione.
        assertEquals(2, PhotoScaler.largestPowerOfTwoAtMost(3))
    }

    @Test
    fun `dzielniki posrednie zaokraglaja w dol`() {
        assertEquals(4, PhotoScaler.largestPowerOfTwoAtMost(5))
        assertEquals(4, PhotoScaler.largestPowerOfTwoAtMost(7))
        assertEquals(8, PhotoScaler.largestPowerOfTwoAtMost(15))
    }

    @Test
    fun `zero i wartosci ujemne nie zmniejszaja`() {
        assertEquals(1, PhotoScaler.largestPowerOfTwoAtMost(0))
        assertEquals(1, PhotoScaler.largestPowerOfTwoAtMost(-4))
    }

    @Test
    fun `dzielnik jeden oddaje oryginal bez dekodowania`() {
        // Ważne, bo ta gałąź nie dotyka Androida - i tylko dlatego da się ją
        // sprawdzić w tym teście.
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertEquals(bytes, PhotoScaler.shrink(bytes, 1))
    }

    @Test
    fun `puste zdjecie nie wywraca`() {
        val empty = ByteArray(0)
        assertEquals(empty, PhotoScaler.shrink(empty, 2))
    }
}
