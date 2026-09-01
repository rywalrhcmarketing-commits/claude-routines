package pl.victor.app.data

import android.util.Log

/**
 * Inteligentny resolver modeli - wybiera najlepszy model na podstawie:
 * 1. Preferencji użytkownika (zapisany w SettingsRepository)
 * 2. Walidacji u providera (RemoteModelValidator)
 * 3. Statusu deprecated (z ModelRegistry)
 * 4. Domyślnego modelu (fallback)
 *
 * Strategia:
 * - Jeśli zapisany model istnieje i nie jest deprecated → użyj go
 * - Jeśli zapisany model jest deprecated → użyj jego replacement
 * - Jeśli zapisany model nie istnieje u providera → użyj domyślny
 * - Jeśli nie da się sprawdzić → użyj zapisany (ostateczność)
 */
class SmartModelResolver(
    private val registry: ModelRegistry = ModelRegistry
) {
    private val tag = "SmartModelResolver"

    /**
     * Rozwiązuje najlepszy model do użycia.
     *
     * @param providerId provider ("gemini", "openai", ...)
     * @param preferredModelId model zapisany przez użytkownika (lub null)
     * @param availableFromProvider lista modeli z RemoteModelValidator (pusta = nie sprawdzono)
     */
    fun resolve(
        providerId: String,
        preferredModelId: String?,
        availableFromProvider: List<String> = emptyList()
    ): ModelResolution {
        val defaultId = ModelRegistry.DEFAULT_MODELS[providerId]
        val preferredInfo = preferredModelId?.let { registry.findById(it) }
        val defaultInfo = defaultId?.let { registry.findById(it) }

        // === Przypadek 1: Brak preferencji - użyj domyślnego ===
        if (preferredModelId == null) {
            Log.d(tag, "No preferred model, using default: $defaultId")
            return ModelResolution(
                modelId = defaultId ?: return ModelResolution.failed("Brak domyślnego modelu dla $providerId"),
                source = ModelSource.DEFAULT,
                warning = null
            )
        }

        // === Przypadek 2: Model nieznany w naszym katalogu ===
        if (preferredInfo == null) {
            Log.w(tag, "Model $preferredModelId unknown in registry")
            // Może to być nowy model którego jeszcze nie dodaliśmy
            // Sprawdź czy provider go zna
            val providerHasIt = availableFromProvider.isEmpty() || preferredModelId in availableFromProvider
            return if (providerHasIt) {
                ModelResolution(
                    modelId = preferredModelId,
                    source = ModelSource.CUSTOM,
                    warning = ModelWarning.UnknownModel(preferredModelId)
                )
            } else {
                // Ani u nas, ani u providera - fallback
                ModelResolution(
                    modelId = defaultId ?: preferredModelId,
                    source = ModelSource.FALLBACK,
                    warning = ModelWarning.ModelNotFound(preferredModelId, defaultId)
                )
            }
        }

        // === Przypadek 3: Model znany ale deprecated ===
        if (preferredInfo.deprecated) {
            val replacementId = preferredInfo.replacementId
            val replacementInfo = replacementId?.let { registry.findById(it) }

            // Sprawdź czy provider też go wycofał
            val providerHasIt = availableFromProvider.isEmpty() || preferredModelId in availableFromProvider

            return if (providerHasIt) {
                // Provider jeszcze ma stary model, ale my rekomendujemy nowy
                ModelResolution(
                    modelId = preferredModelId,  // jeszcze działa
                    source = ModelSource.DEPRECATED,
                    warning = ModelWarning.Deprecated(
                        oldModelId = preferredModelId,
                        newModelId = replacementId ?: defaultId,
                        deprecationDate = preferredInfo.deprecationDate
                    )
                )
            } else {
                // Provider już wycofał - użyj nowego
                Log.w(tag, "$preferredModelId deprecated and removed by provider, using $replacementId")
                ModelResolution(
                    modelId = replacementId ?: defaultId ?: preferredModelId,
                    source = ModelSource.AUTO_MIGRATED,
                    warning = ModelWarning.AutoMigrated(
                        oldModelId = preferredModelId,
                        newModelId = replacementId ?: defaultId ?: preferredModelId
                    )
                )
            }
        }

        // === Przypadek 4: Model znany i aktywny ===
        // Sprawdź czy provider go ma
        val providerHasIt = availableFromProvider.isEmpty() || preferredModelId in availableFromProvider

        return if (providerHasIt) {
            ModelResolution(
                modelId = preferredModelId,
                source = ModelSource.PREFERRED,
                warning = null
            )
        } else {
            // U nas aktywny, ale provider go nie ma (coś nowego u providera?)
            Log.w(tag, "$preferredModelId in registry but not at provider")
            ModelResolution(
                modelId = defaultId ?: preferredModelId,
                source = ModelSource.FALLBACK,
                warning = ModelWarning.ModelNotFound(preferredModelId, defaultId)
            )
        }
    }
}

/**
 * Wynik rozwiązania - jaki model użyć i dlaczego.
 */
data class ModelResolution(
    val modelId: String,
    val source: ModelSource,
    val warning: ModelWarning?
) {
    companion object {
        fun failed(reason: String) = ModelResolution(
            modelId = "",
            source = ModelSource.FAILED,
            warning = ModelWarning.Fatal(reason)
        )
    }
}

/**
 * Skąd pochodzi wybrany model.
 */
enum class ModelSource {
    PREFERRED,    // użytkownik wybrał, wszystko OK
    DEPRECATED,   // użytkownik wybrał deprecated, ale jeszcze działa
    AUTO_MIGRATED,// automatycznie przeniesiony na nowy
    DEFAULT,      // brak preferencji, użyty domyślny
    FALLBACK,     // model nie istnieje, użyty fallback
    CUSTOM,       // model nieznany w naszym katalogu ale provider go ma
    FAILED        // nie udało się znaleźć żadnego modelu
}

/**
 * Ostrzeżenia do pokazania użytkownikowi.
 */
sealed class ModelWarning {
    data class Deprecated(
        val oldModelId: String,
        val newModelId: String?,
        val deprecationDate: String?
    ) : ModelWarning()

    data class AutoMigrated(
        val oldModelId: String,
        val newModelId: String
    ) : ModelWarning()

    data class ModelNotFound(
        val modelId: String,
        val fallbackId: String?
    ) : ModelWarning()

    data class UnknownModel(
        val modelId: String
    ) : ModelWarning()

    data class Fatal(
        val reason: String
    ) : ModelWarning()

    /**
     * User-friendly message.
     */
    fun toUserMessage(): String = when (this) {
        is Deprecated -> "Model $oldModelId jest przestarzały" +
                (deprecationDate?.let { " (wycofywany od $it)" } ?: "") +
                ". Przejdź na $newModelId."
        is AutoMigrated -> "Model $oldModelId został wycofany. Automatycznie używam $newModelId."
        is ModelNotFound -> "Model $modelId nie istnieje" +
                (fallbackId?.let { ". Używam $it" } ?: "") + "."
        is UnknownModel -> "Model $modelId nie jest w naszej bazie - używam go, ale może nie być optymalny."
        is Fatal -> "Błąd: $reason"
    }
}
