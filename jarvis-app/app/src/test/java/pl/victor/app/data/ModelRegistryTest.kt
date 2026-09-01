package pl.victor.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Testy dla ModelRegistry - integralność katalogu modeli.
 */
class ModelRegistryTest {

    @Test
    fun `every model has unique id`() {
        val all = ModelRegistry.ALL_MODELS
        val ids = all.map { it.id }
        assertEquals("Duplicate model IDs found", ids.size, ids.toSet().size)
    }

    @Test
    fun `every model has displayName`() {
        ModelRegistry.ALL_MODELS.forEach { model ->
            assertNotNull("Model ${model.id} has no displayName", model.displayName)
            assertTrue("Model ${model.id} has blank displayName",
                model.displayName.isNotBlank())
        }
    }

    @Test
    fun `every model has valid providerId`() {
        val validProviders = setOf("gemini", "openai", "claude", "minimax")
        ModelRegistry.ALL_MODELS.forEach { model ->
            assertTrue("Model ${model.id} has invalid provider: ${model.providerId}",
                model.providerId in validProviders)
        }
    }

    @Test
    fun `every default model exists in ALL_MODELS`() {
        ModelRegistry.DEFAULT_MODELS.forEach { (provider, modelId) ->
            assertTrue(
                "Default model $modelId for $provider not in ALL_MODELS",
                ModelRegistry.isKnown(modelId)
            )
        }
    }

    @Test
    fun `release date format is YYYY-MM or null`() {
        val regex = Regex("""^\d{4}-\d{2}$""")
        ModelRegistry.ALL_MODELS.forEach { model ->
            model.releaseDate?.let { date ->
                assertTrue("Model ${model.id} has invalid date format: $date",
                    regex.matches(date))
            }
        }
    }

    @Test
    fun `deprecated flag is consistent with replacement`() {
        val deprecated = ModelRegistry.ALL_MODELS.filter { it.deprecated }
        deprecated.forEach { model ->
            assertTrue("Deprecated model ${model.id} should have replacement",
                !model.replacementId.isNullOrBlank())
        }
    }

    @Test
    fun `active models do not have replacement`() {
        val active = ModelRegistry.ALL_MODELS.filter { !it.deprecated }
        active.forEach { model ->
            assertNull("Active model ${model.id} should not have replacement",
                model.replacementId)
        }
    }

    @Test
    fun `every provider has at least one active model`() {
        val providers = setOf("gemini", "openai", "claude", "minimax")
        providers.forEach { provider ->
            val active = ModelRegistry.activeForProvider(provider)
            assertTrue("Provider $provider has no active models",
                active.isNotEmpty())
        }
    }
}
