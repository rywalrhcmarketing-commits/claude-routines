package pl.victor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Katalog komend głosowych musi mówić prawdę.
 *
 * Poprzednia wersja oferowała 16 fraz, z których 11 - łącznie z domyślną -
 * po cichu schodziło na „jarvis", bo Porcupine ma tylko 14 wbudowanych komend
 * i wszystkie angielskie. Te testy pilnują, żeby to się nie powtórzyło:
 * każda pozycja oznaczona jako działająca musi wskazywać komendę, którą
 * Porcupine faktycznie zna.
 */
class WakeWordRegistryTest {

    @Test
    fun `kazda komenda dzialajaca wskazuje wbudowana fraze Porcupine`() {
        for (word in WakeWordRegistry.builtIn()) {
            val keyword = word.porcupineKeyword
            assertNotNull("${word.id} jest oznaczona jako działająca, ale nie ma frazy", keyword)
            assertTrue(
                "${word.id} wskazuje \"$keyword\", czego Porcupine nie zna",
                keyword in WakeWordRegistry.BUILT_IN_KEYWORDS
            )
        }
    }

    @Test
    fun `komendy wymagajace modelu nie udaja dzialajacych`() {
        for (word in WakeWordRegistry.all().filterNot { it.worksOutOfTheBox }) {
            assertNull(
                "${word.id} nie powinna wskazywać wbudowanej frazy",
                word.porcupineKeyword
            )
        }
    }

    @Test
    fun `domyslna komenda dziala bez dodatkowych plikow`() {
        // Domyślna wartość musi działać zaraz po wpisaniu klucza - inaczej
        // użytkownik włącza przełącznik i nic się nie dzieje.
        val default = WakeWordRegistry.default()
        assertTrue(
            "domyślna komenda ${default.id} wymaga własnego modelu",
            default.worksOutOfTheBox
        )
        assertTrue(default.porcupineKeyword in WakeWordRegistry.BUILT_IN_KEYWORDS)
    }

    @Test
    fun `identyfikatory sa unikalne`() {
        val ids = WakeWordRegistry.all().map { it.id }
        assertEquals("identyfikatory się powtarzają: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `zadna komenda nie ma pustego opisu ani emoji`() {
        for (word in WakeWordRegistry.all()) {
            assertTrue("${word.id} bez opisu", word.description.isNotBlank())
            assertTrue("${word.id} bez emoji", word.emoji.isNotBlank())
        }
    }

    @Test
    fun `fraza jest pusta tylko dla wlasnej komendy`() {
        for (word in WakeWordRegistry.all()) {
            if (word.id == "custom") {
                assertTrue("własna komenda ma mieć pustą frazę", word.phrase.isEmpty())
            } else {
                assertTrue("${word.id} bez frazy", word.phrase.isNotBlank())
            }
        }
    }

    @Test
    fun `findById zwraca null dla nieznanego identyfikatora`() {
        // Stare preferencje mogą trzymać identyfikatory, których już nie ma
        // (np. "jarvis_start", "hej_cyan") - katalog ma je odrzucić, a nie zgadywać.
        assertNull(WakeWordRegistry.findById("jarvis_start"))
        assertNull(WakeWordRegistry.findById("hej_cyan"))
        assertNull(WakeWordRegistry.findById(""))
    }

    @Test
    fun `lista wbudowanych zgadza sie z Porcupine 3_0`() {
        // Sprawdzone przez javap na porcupine-android-3.0.0.aar.
        assertEquals(14, WakeWordRegistry.BUILT_IN_KEYWORDS.size)
        assertTrue("jarvis" in WakeWordRegistry.BUILT_IN_KEYWORDS)
        assertTrue("computer" in WakeWordRegistry.BUILT_IN_KEYWORDS)
        // Polskich fraz Porcupine nie zna - to jest sedno sprawy.
        assertTrue("cześć" !in WakeWordRegistry.BUILT_IN_KEYWORDS)
        assertTrue("jarvis_start" !in WakeWordRegistry.BUILT_IN_KEYWORDS)
    }

    @Test
    fun `katalog oferuje sensowny wybor dzialajacych komend`() {
        assertTrue(
            "za mało działających komend do wyboru: ${WakeWordRegistry.builtIn().size}",
            WakeWordRegistry.builtIn().size >= 8
        )
    }
}
