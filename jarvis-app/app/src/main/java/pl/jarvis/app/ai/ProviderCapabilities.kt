package pl.jarvis.app.ai

/**
 * Możliwości providera AI.
 * Decydują jak możemy wysłać multimedia do modelu.
 */
data class ProviderCapabilities(
    /** Czy przyjmuje obrazy (bytes) */
    val supportsImages: Boolean = true,
    /** Czy przyjmuje wideo (bytes mp4/webm) */
    val supportsVideo: Boolean = false,
    /** Czy przyjmuje audio (bytes) */
    val supportsAudio: Boolean = false,
    /** Ile obrazów max w jednym requeście */
    val maxImagesPerRequest: Int = 10,
    /** Max rozmiar pliku wideo (bytes) */
    val maxVideoBytes: Long = 20L * 1024 * 1024,  // 20MB default
    /** Max rozmiar obrazu (bytes) */
    val maxImageBytes: Long = 4L * 1024 * 1024,   // 4MB default
    /** Rekomendowana rozdzielczość obrazu */
    val recommendedImageResolution: ImageResolution = ImageResolution.MEDIUM,
    /** Wspiera streaming SSE */
    val supportsStreaming: Boolean = true,
    /** Wspiera function calling (akcje) */
    val supportsFunctionCalling: Boolean = false
) {
    /**
     * Czy provider obsługuje dany tryb capture.
     */
    fun supportsMode(mode: CaptureMode): Boolean = when (mode) {
        CaptureMode.BURST_PHOTO -> supportsImages && maxImagesPerRequest >= 5
        CaptureMode.HIGH_QUALITY_SINGLE -> supportsImages
        CaptureMode.FAST_BURST -> supportsImages && maxImagesPerRequest >= 5
        CaptureMode.VIDEO_SHORT -> supportsVideo
        CaptureMode.VIDEO_LONG -> supportsVideo
    }
}

/**
 * Rozszerzone możliwości - hardware glasses (HeyCyan).
 * Jeśli mamy HeyCyan podłączone, zawsze wspieramy wideo (nagrywa 1080p MP4)
 * niezależnie od tego czy AI provider to obsługuje - i tak wysyłamy jako VIDEO.
 */
data class HardwareCapabilities(
    val glassesConnected: Boolean = false,
    val glassesSupportsVideo: Boolean = false,  // HeyCyan: 1080p MP4
    val glassesSupportsPhoto: Boolean = true,
    val glassesResolution: String = "1080p"
)

/**
 * Rozdzielczość obrazu.
 */
enum class ImageResolution(val maxWidth: Int, val maxHeight: Int, val jpegQuality: Int) {
    LOW(640, 480, 70),       // gesty, szybka analiza
    MEDIUM(1280, 720, 80),   // domyślna dla burst
    HIGH(1920, 1080, 90),    // szczegółowa analiza pojedyncza
    ULTRA(2560, 1440, 95)    // maksymalna (np. tablica, menu)
}

/**
 * Tryby przechwytywania - różne scenariusze użycia.
 */
enum class CaptureMode(val displayName: String, val emoji: String, val description: String) {
    /**
     * 5 zdjęć co 1 sekundę (5s total).
     * Najlepsze dla: ogólna analiza, multi-frame context.
     * Dla providerów którzy nie obsługują wideo.
     */
    BURST_PHOTO(
        "Burst 5 zdjęć",
        "📸",
        "5 zdjęć co 1 sekundę - multi-frame context"
    ),

    /**
     * 1 zdjęcie wysokiej rozdzielczości.
     * Najlepsze dla: czytanie tekstu, detale, OCR.
     */
    HIGH_QUALITY_SINGLE(
        "1 zdjęcie HD",
        "📷",
        "Pojedyncze zdjęcie wysokiej jakości"
    ),

    /**
     * 5 zdjęć co 200ms (1s total).
     * Najlepsze dla: gesty, szybka akcja.
     * Wymaga szybkiego shutter.
     */
    FAST_BURST(
        "Fast burst",
        "⚡",
        "5 zdjęć w 1 sekundę - gesty"
    ),

    /**
     * Krótkie wideo (3s, 24 FPS).
     * Najlepsze dla: gesty, krótka sekwencja.
     * Wymaga providera z obsługą wideo.
     */
    VIDEO_SHORT(
        "Wideo 3s",
        "🎬",
        "3 sekundy wideo (24 FPS) - gesty, sekwencja"
    ),

    /**
     * Dłuższe wideo (5s, 10 FPS).
     * Najlepsze dla: pełna obserwacja, demo.
     * Wymaga providera z obsługą wideo.
     */
    VIDEO_LONG(
        "Wideo 5s",
        "🎞️",
        "5 sekund wideo - pełna sekwencja"
    );

    /**
     * Czy wymaga wideo (nie zdjęć).
     */
    val requiresVideo: Boolean
        get() = this == VIDEO_SHORT || this == VIDEO_LONG

    /**
     * Ile zdjęć zwraca (0 dla wideo).
     */
    val expectedImageCount: Int
        get() = when (this) {
            BURST_PHOTO -> 5
            HIGH_QUALITY_SINGLE -> 1
            FAST_BURST -> 5
            VIDEO_SHORT -> 0
            VIDEO_LONG -> 0
        }

    /**
     * Domyślna rozdzielczość dla tego trybu.
     */
    val defaultResolution: ImageResolution
        get() = when (this) {
            BURST_PHOTO -> ImageResolution.MEDIUM
            HIGH_QUALITY_SINGLE -> ImageResolution.ULTRA
            FAST_BURST -> ImageResolution.LOW
            VIDEO_SHORT -> ImageResolution.MEDIUM
            VIDEO_LONG -> ImageResolution.LOW
        }

    /**
     * Szybkość przechwytywania (ms między klatkami).
     * 0 = pojedyncze zdjęcie.
     */
    val frameIntervalMs: Long
        get() = when (this) {
            BURST_PHOTO -> 1_000L       // 5 zdjęć co 1s = 5s
            HIGH_QUALITY_SINGLE -> 0L   // 1 zdjęcie
            FAST_BURST -> 200L          // 5 zdjęć co 0.2s = 1s
            VIDEO_SHORT -> 42L          // 24 FPS (1000/24)
            VIDEO_LONG -> 100L          // 10 FPS
        }
}

/**
 * Decyzja o trybie capture - na bazie capabilities + preferencji.
 */
data class CaptureDecision(
    val mode: CaptureMode,
    val resolution: ImageResolution,
    val reason: String
)
