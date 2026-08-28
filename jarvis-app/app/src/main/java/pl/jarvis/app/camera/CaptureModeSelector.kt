package pl.jarvis.app.camera

import pl.jarvis.app.ai.CaptureDecision
import pl.jarvis.app.ai.CaptureMode
import pl.jarvis.app.ai.ImageResolution
import pl.jarvis.app.ai.ProviderCapabilities

/**
 * Decyduje jaki tryb capture zastosować.
 *
 * Strategia:
 * 1. User wybiera tryb w Settings (preferredMode)
 * 2. Sprawdzamy capabilities providera
 * 3. Jeśli preferredMode nie jest obsługiwany - degradujemy
 * 4. Zwraca CaptureDecision z trybem + rozdzielczością + powodem
 */
class CaptureModeSelector {

    /**
     * Wybiera optymalny tryb capture.
     *
     * @param preferred Tryb preferowany przez usera (z Settings)
     * @param capabilities Co provider obsługuje
     * @param overrideForGesture Czy user wymusił tryb gestów
     */
    fun select(
        preferred: CaptureMode,
        capabilities: ProviderCapabilities,
        overrideForGesture: Boolean = false
    ): CaptureDecision {
        // 1. Specjalny przypadek: gesty
        if (overrideForGesture) {
            return when {
                capabilities.supportsMode(CaptureMode.VIDEO_SHORT) -> CaptureDecision(
                    mode = CaptureMode.VIDEO_SHORT,
                    resolution = ImageResolution.MEDIUM,
                    reason = "Tryb gestów: krótkie wideo (24 FPS)"
                )
                capabilities.supportsMode(CaptureMode.FAST_BURST) -> CaptureDecision(
                    mode = CaptureMode.FAST_BURST,
                    resolution = ImageResolution.LOW,
                    reason = "Tryb gestów: szybki burst (5 zdjęć w 1s)"
                )
                else -> CaptureDecision(
                    mode = CaptureMode.BURST_PHOTO,
                    resolution = ImageResolution.LOW,
                    reason = "Tryb gestów fallback: standard burst (provider nie obsługuje wideo)"
                )
            }
        }

        // 2. Jeśli preferred jest obsługiwany - użyj go
        if (capabilities.supportsMode(preferred)) {
            return CaptureDecision(
                mode = preferred,
                resolution = preferred.defaultResolution,
                reason = "Preferowany tryb ${preferred.displayName} jest obsługiwany"
            )
        }

        // 3. Degraduj - jeśli user chciał wideo ale provider nie obsługuje
        if (preferred.requiresVideo) {
            return if (capabilities.supportsMode(CaptureMode.FAST_BURST)) {
                CaptureDecision(
                    mode = CaptureMode.FAST_BURST,
                    resolution = ImageResolution.LOW,
                    reason = "Provider nie obsługuje wideo → degradacja do FAST_BURST"
                )
            } else {
                CaptureDecision(
                    mode = CaptureMode.BURST_PHOTO,
                    resolution = ImageResolution.LOW,
                    reason = "Provider nie obsługuje wideo → degradacja do BURST_PHOTO"
                )
            }
        }

        // 4. Fallback do BURST_PHOTO
        return CaptureDecision(
            mode = CaptureMode.BURST_PHOTO,
            resolution = preferred.defaultResolution,
            reason = "Fallback do domyślnego trybu"
        )
    }

    /**
     * Sugeruje najlepszy tryb dla danego scenariusza.
     */
    fun suggest(scenario: CaptureScenario, capabilities: ProviderCapabilities): CaptureMode {
        return when (scenario) {
            CaptureScenario.GENERAL -> CaptureMode.BURST_PHOTO
            CaptureScenario.READ_TEXT -> CaptureMode.HIGH_QUALITY_SINGLE
            CaptureScenario.GESTURE -> if (capabilities.supportsMode(CaptureMode.VIDEO_SHORT))
                CaptureMode.VIDEO_SHORT else CaptureMode.FAST_BURST
            CaptureScenario.QR_CODE -> CaptureMode.HIGH_QUALITY_SINGLE
            CaptureScenario.LONG_OBSERVATION -> if (capabilities.supportsMode(CaptureMode.VIDEO_LONG))
                CaptureMode.VIDEO_LONG else CaptureMode.BURST_PHOTO
            CaptureScenario.LOW_BATTERY -> CaptureMode.HIGH_QUALITY_SINGLE
        }
    }
}

/**
 * Scenariusz użycia.
 */
enum class CaptureScenario(val displayName: String) {
    GENERAL("Ogólne pytanie"),
    READ_TEXT("Czytanie tekstu"),
    GESTURE("Rozpoznawanie gestów"),
    QR_CODE("Skanowanie QR"),
    LONG_OBSERVATION("Dłuższa obserwacja"),
    LOW_BATTERY("Oszczędzanie baterii")
}
