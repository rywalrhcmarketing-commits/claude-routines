package pl.victor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tu łatwo o pomyłkę w OBIE strony: ukryty działający model to funkcja, której
 * nikt nie znajdzie, a pokazany nieistniejący to błąd przy pierwszym pytaniu.
 */
class ModelCatalogTest {

    private val known = ModelRegistry.forProvider("openai").map { it.id }

    @Test
    fun `brak odpowiedzi z API pokazuje katalog`() {
        // Bez klucza albo bez sieci lista przychodzi pusta. Pusty wybór
        // zostawiłby użytkownika bez możliwości cokolwiek ustawić.
        val list = ModelCatalog.forPicker("openai", emptyList())
        assertTrue(list.isNotEmpty())
    }

    @Test
    fun `model ktorego API nie zwraca wypada`() {
        val list = ModelCatalog.forPicker("openai", listOf(known.first()))
        assertEquals(1, list.size)
        assertEquals(known.first(), list.first().id)
    }

    @Test
    fun `nieznany model z API wchodzi z surowym ID`() {
        val list = ModelCatalog.forPicker("deepseek", listOf("deepseek-cos-nowego"))
        val found = list.single { it.id == "deepseek-cos-nowego" }
        assertEquals("deepseek-cos-nowego", found.displayName)
        assertTrue(found.description.contains("Wykryty"))
    }

    @Test
    fun `opisane modele ida przed wykrytymi`() {
        val list = ModelCatalog.forPicker("openai", listOf("zzz-nieznany", known.first()))
        assertEquals(known.first(), list.first().id)
    }

    @Test
    fun `wizja jest zgadywana tylko z jednoznacznej nazwy`() {
        // Fałszywe "obsługuje obrazy" kończy się wysłaniem zdjęcia do modelu
        // tekstowego i błędem zamiast odpowiedzi.
        val vision = ModelCatalog.forPicker("deepseek", listOf("deepseek-vl2-small")).single()
        assertTrue(vision.supportsVision)
        val text = ModelCatalog.forPicker("deepseek", listOf("deepseek-chat")).single()
        assertFalse(text.supportsVision)
    }

    @Test
    fun `zniknięty wybor uzytkownika wraca jako null`() {
        val available = ModelCatalog.forPicker("deepseek", listOf("deepseek-chat"))
        assertEquals("deepseek-chat", ModelCatalog.keepIfStillThere("deepseek-chat", available))
        assertNull(ModelCatalog.keepIfStillThere("model-ktory-zniknal", available))
        assertNull(ModelCatalog.keepIfStillThere(null, available))
    }

    @Test
    fun `puste i powtorzone ID z API sa czyszczone`() {
        val list = ModelCatalog.forPicker("deepseek", listOf("deepseek-chat", " ", "deepseek-chat"))
        assertEquals(1, list.size)
    }
}
