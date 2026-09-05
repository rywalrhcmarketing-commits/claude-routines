package pl.victor.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ramkowanie decyduje o tym, czy z mowy zostanie całe zdanie, czy jego ułamek.
 *
 * Bez podziału na pakiety stałej długości dekoder rozkodowywał tylko PIERWSZY
 * pakiet Opusa z każdego pakietu BLE i milczał o reszcie - a model dostawał
 * nagranie, w którym nie dało się usłyszeć pytania. Błąd nie dawał żadnego
 * wyjątku, więc jedyną obroną są te testy.
 */
class OpusFramingTest {

    @Test
    fun `wielokrotnosci czterdziestu daja pakiet czterdziestobajtowy`() {
        val sizes = listOf(240, 200, 40, 120)
        assertEquals(40, OpusFraming.detectPacketSize(sizes))
    }

    @Test
    fun `same pakiety po czterdziesci nie sa dzielone`() {
        // Każdy pakiet BLE niesie dokładnie jeden pakiet Opusa - dzielenie
        // niczego nie zmienia, a wpis w raporcie byłby mylący.
        assertEquals(
            OpusFraming.NO_FIXED_SIZE,
            OpusFraming.detectPacketSize(listOf(40, 40, 40))
        )
    }

    @Test
    fun `jeden nierowny ladunek uniewaznia kandydata`() {
        assertEquals(
            OpusFraming.NO_FIXED_SIZE,
            OpusFraming.detectPacketSize(listOf(240, 200, 137))
        )
    }

    @Test
    fun `pusta lista nie wywraca sie`() {
        assertEquals(OpusFraming.NO_FIXED_SIZE, OpusFraming.detectPacketSize(emptyList()))
    }

    @Test
    fun `zera i wartosci ujemne sa pomijane`() {
        assertEquals(40, OpusFraming.detectPacketSize(listOf(0, 240, -5, 80)))
    }

    @Test
    fun `wielokrotnosci osiemdziesieciu wybieraja czterdziesci`() {
        // 40 jest wcześniej na liście kandydatów, a 80 też się przez nie dzieli.
        // To jest właściwy wybór: 80-bajtowy blok to dwa pakiety po 40.
        assertEquals(40, OpusFraming.detectPacketSize(listOf(160, 240)))
    }

    @Test
    fun `dzielenie oddaje kawalki po kolei`() {
        val body = ByteArray(120) { it.toByte() }
        val frames = OpusFraming.split(body, 40)
        assertEquals(3, frames.size)
        assertEquals(40, frames[0].size)
        assertEquals(0.toByte(), frames[0][0])
        assertEquals(40.toByte(), frames[1][0])
        assertEquals(80.toByte(), frames[2][0])
    }

    @Test
    fun `krotszy ladunek zostaje w calosci`() {
        val body = ByteArray(30)
        assertEquals(1, OpusFraming.split(body, 40).size)
    }

    @Test
    fun `nierowny podzial zostaje w calosci`() {
        val body = ByteArray(100)
        val frames = OpusFraming.split(body, 40)
        assertEquals(1, frames.size)
        assertEquals(100, frames[0].size)
    }

    @Test
    fun `brak ramkowania zostawia ladunek w calosci`() {
        val body = ByteArray(240)
        assertEquals(1, OpusFraming.split(body, OpusFraming.NO_FIXED_SIZE).size)
    }

    @Test
    fun `pusty ladunek daje pusta liste`() {
        assertEquals(0, OpusFraming.split(ByteArray(0), 40).size)
    }

    @Test
    fun `suma kawalkow to caly ladunek`() {
        val body = ByteArray(200) { (it % 251).toByte() }
        val joined = OpusFraming.split(body, 40).fold(ByteArray(0)) { acc, f -> acc + f }
        assertEquals(body.toList(), joined.toList())
    }
}
