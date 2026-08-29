package pl.jarvis.app.camera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.jarvis.app.ai.CaptureMode
import pl.jarvis.app.ai.ImageResolution
import pl.jarvis.app.ble.JarvisManager
import pl.jarvis.app.storage.PhotoStorage

/**
 * Capture modes - każdy ma swoją strategię:
 *
 * - BURST_PHOTO: 5 zdjęć co 1s (5s total) - domyślny, kompatybilny
 * - HIGH_QUALITY_SINGLE: 1 zdjęcie HD - detale, OCR
 * - FAST_BURST: 5 zdjęć co 200ms (1s total) - gesty (statyczne)
 * - VIDEO_SHORT: 3s wideo 24 FPS - gesty (dynamiczne) [HeyCyan: 1080p MP4]
 * - VIDEO_LONG: 5s wideo 10 FPS - pełna obserwacja [HeyCyan: 1080p MP4]
 *
 * Zdjęcia pobierane są jako miniatury przez BLE (JarvisManager.capturePhoto) - ta ścieżka
 * nie wymaga Wi-Fi Direct, więc jest szybka i działa od razu po sparowaniu okularów.
 *
 * Wideo idzie przez Wi-Fi Direct (JarvisManager.downloadLatestVideo): telefon dołącza
 * do grupy okularów i pobiera plik po HTTP. Wymaga uprawnienia NEARBY_WIFI_DEVICES
 * na Androidzie 13+ (wcześniej ACCESS_FINE_LOCATION).
 */
class BurstCaptureManager(
    private val context: Context,
    private val photoStorage: PhotoStorage,
    private val heyCyan: JarvisManager
) {
    private val tag = "BurstCaptureManager"

    /** Górny limit zdjęć w serii - chroni przed zablokowaniem okularów. */
    private val MAX_BURST_COUNT = 10

    /**
     * Główna metoda - przechwytuje multimedia zgodnie z trybem.
     *
     * @return CaptureResult ze zdjęciami lub wideo
     */
    suspend fun capture(
        mode: CaptureMode,
        resolution: ImageResolution = mode.defaultResolution,
        countOverride: Int? = null,
        intervalMsOverride: Long? = null,
        onProgress: (Int) -> Unit = {}
    ): CaptureResult = withContext(Dispatchers.IO) {
        Log.i(tag, "Starting capture: mode=$mode, res=$resolution")

        when {
            // Wideo nagrywają okulary własnym firmware - rozdzielczości nie da
            // się z aplikacji ustawić, więc nie przekazujemy jej dalej, żeby nie
            // udawać, że coś robi.
            mode.requiresVideo -> captureVideo(mode, onProgress)
            else -> captureBurst(mode, resolution, countOverride, intervalMsOverride, onProgress)
        }
    }

    /**
     * Burst capture - N zdjęć z HeyCyan (po BLE/HTTP).
     */
    private suspend fun captureBurst(
        mode: CaptureMode,
        resolution: ImageResolution,
        countOverride: Int?,
        intervalMsOverride: Long?,
        onProgress: (Int) -> Unit
    ): CaptureResult {
        // Ustawienia użytkownika mają pierwszeństwo przed domyślnymi wartościami trybu.
        val count = (countOverride ?: mode.expectedImageCount).coerceIn(1, MAX_BURST_COUNT)
        val intervalMs = intervalMsOverride ?: mode.frameIntervalMs
        val images = mutableListOf<ByteArray>()

        // Sprawdź czy okulary połączone
        if (heyCyan.connectionState.value != pl.jarvis.app.ble.ConnectionState.READY) {
            Log.w(tag, "HeyCyan nie połączony - zwracam puste")
            return CaptureResult(mode, emptyList(), null, 0)
        }

        // Rozdzielczość trybu przekłada się na dwie rzeczy: jakość miniatury,
        // o którą prosimy okulary, i limity, do których dopasowujemy wynik.
        val thumbnailQuality = ImageScaler.thumbnailQualityFor(resolution)

        for (i in 0 until count) {
            Log.d(tag, "Zdjęcie ${i + 1}/$count (przez BLE, jakość $thumbnailQuality)")
            onProgress(i + 1)

            // Miniatura po BLE: jedna komenda robi zdjęcie i odsyła bajty JPEG.
            val photo = heyCyan.capturePhoto(thumbnailQuality)?.let {
                ImageScaler.fit(it, resolution)
            }
            if (photo != null) {
                images.add(photo)
                photoStorage.saveConversationPhoto(photo, "burst_${i + 1}")
            } else {
                Log.w(tag, "Nie udało się pobrać zdjęcia ${i + 1}/$count")
            }

            if (i < count - 1) {
                delay(intervalMs)
            }
        }

        if (images.isEmpty()) {
            Log.w(tag, "Nie pobrano żadnego zdjęcia z okularów")
        }

        onProgress(count)

        return CaptureResult(
            mode = mode,
            images = images,
            video = null,
            videoDurationMs = 0
        )
    }

    /**
     * Nagrywanie wideo (1080p MP4).
     *
     * Sterowanie idzie po BLE, a gotowy plik pobierany jest przez Wi-Fi Direct.
     * Zestawienie grupy P2P trwa kilkanaście sekund, więc pobranie wideo jest
     * wyraźnie wolniejsze niż zdjęcie po BLE.
     */
    private suspend fun captureVideo(
        mode: CaptureMode,
        onProgress: (Int) -> Unit
    ): CaptureResult {
        val durationMs = when (mode) {
            CaptureMode.VIDEO_SHORT -> 3_000L
            CaptureMode.VIDEO_LONG -> 5_000L
            else -> 3_000L
        }

        Log.i(tag, "HeyCyan video recording for ${durationMs}ms")
        onProgress(0)

        if (heyCyan.connectionState.value != pl.jarvis.app.ble.ConnectionState.READY) {
            Log.w(tag, "HeyCyan nie połączony - video niemożliwe")
            return CaptureResult(mode, emptyList(), null, 0)
        }

        // Start
        heyCyan.startVideoRecording()
        delay(durationMs)
        onProgress(50)

        // Stop
        heyCyan.stopVideoRecording()
        delay(500)  // daj czas na flush

        onProgress(75)

        // Pobierz najnowsze wideo przez HTTP
        val video = heyCyan.downloadLatestVideo()
        if (video != null) {
            photoStorage.saveVideo(video, "video_${System.currentTimeMillis()}.mp4")
            Log.i(tag, "Video downloaded: ${video.size} bytes")
        } else {
            Log.w(
                tag,
                "Nie pobrano wideo - sprawdź uprawnienie do Wi-Fi Direct i czy okulary " +
                    "weszły w tryb transferu. Nagranie pozostaje w ich pamięci."
            )
        }

        onProgress(100)

        return CaptureResult(
            mode = mode,
            images = emptyList(),
            video = video,
            videoDurationMs = durationMs
        )
    }
}

/**
 * Wynik przechwytywania.
 */
data class CaptureResult(
    val mode: CaptureMode,
    val images: List<ByteArray>,
    val video: ByteArray?,
    val videoDurationMs: Long
) {
    val isEmpty: Boolean get() = images.isEmpty() && (video == null || video.isEmpty())
}
