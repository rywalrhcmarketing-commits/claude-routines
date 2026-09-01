package pl.victor.app.ble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Rysuje sceny testowe dla [GlassesSimulator] przez Android Canvas.
 *
 * Zdjęcia z symulatora nie mają udawać prawdziwego świata - mają być czytelne
 * dla modelu i dla człowieka, więc każda scena to plansza z dużym napisem,
 * kilkoma kształtami i tekstem do odczytania przez OCR. Dzięki temu widać
 * po odpowiedzi asystenta, czy obraz naprawdę doszedł do modelu, czy model
 * zmyśla.
 *
 * Kolejne wywołania cyklicznie zmieniają scenę, żeby wielokrotne pytania
 * nie dostawały ciągle tego samego obrazu.
 */
class CanvasPhotoSource(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val quality: Int = DEFAULT_QUALITY
) : SimulatedPhotoSource {

    override fun photoBytes(index: Int): ByteArray {
        val scene = SCENES[index.mod(SCENES.size)]
        return try {
            render(scene, index)
        } catch (e: Throwable) {
            // W testach jednostkowych Canvas nie istnieje - wtedy osadzony JPEG wystarczy.
            Log.w(TAG, "Canvas niedostępny, używam osadzonego JPEG", e)
            EmbeddedPhotoSource.photoBytes(index)
        }
    }

    private fun render(scene: Scene, index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(scene.background)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Prostokąt "przedmiotu" - coś, o czym model może powiedzieć, że widzi.
        paint.color = scene.accent
        canvas.drawRoundRect(
            RectF(width * 0.12f, height * 0.30f, width * 0.48f, height * 0.72f),
            width * 0.03f,
            width * 0.03f,
            paint
        )
        canvas.drawCircle(width * 0.70f, height * 0.50f, width * 0.13f, paint)

        // Duży tytuł sceny - to czyta OCR i to opisuje model.
        paint.color = scene.foreground
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = height * 0.10f
        canvas.drawText(scene.title, width * 0.06f, height * 0.19f, paint)

        // Podpis: numer klatki - widać po nim, że kolejne zdjęcia są różne.
        paint.typeface = Typeface.DEFAULT
        paint.textSize = height * 0.055f
        canvas.drawText(scene.caption, width * 0.06f, height * 0.86f, paint)
        canvas.drawText(
            "SYMULACJA - klatka ${index + 1}",
            width * 0.06f,
            height * 0.94f,
            paint
        )

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private data class Scene(
        val title: String,
        val caption: String,
        val background: Int,
        val foreground: Int,
        val accent: Int
    )

    companion object {
        private const val TAG = "CanvasPhotoSource"

        /** Zbliżone do miniatur, jakie odsyłają okulary. */
        private const val DEFAULT_WIDTH = 640
        private const val DEFAULT_HEIGHT = 480
        private const val DEFAULT_QUALITY = 85

        private val SCENES = listOf(
            Scene(
                title = "KUBEK KAWY",
                caption = "Biurko, kubek i laptop",
                background = Color.rgb(0xF5, 0xEF, 0xE6),
                foreground = Color.rgb(0x2B, 0x20, 0x18),
                accent = Color.rgb(0x8B, 0x5A, 0x2B)
            ),
            Scene(
                title = "TABLICZKA: WYJSCIE",
                caption = "Korytarz, znak ewakuacyjny po prawej",
                background = Color.rgb(0xE8, 0xF2, 0xE8),
                foreground = Color.rgb(0x10, 0x30, 0x18),
                accent = Color.rgb(0x1E, 0x8E, 0x3E)
            ),
            Scene(
                title = "PARAGON 24,90 PLN",
                caption = "Paragon ze sklepu, data 2026-08-28",
                background = Color.rgb(0xFA, 0xFA, 0xFA),
                foreground = Color.rgb(0x1A, 0x1A, 0x1A),
                accent = Color.rgb(0x9E, 0x9E, 0x9E)
            ),
            Scene(
                title = "PRZEJSCIE DLA PIESZYCH",
                caption = "Ulica, pasy, sygnalizacja zielona",
                background = Color.rgb(0xE3, 0xEA, 0xF5),
                foreground = Color.rgb(0x10, 0x1A, 0x30),
                accent = Color.rgb(0x1A, 0x73, 0xE8)
            ),
            Scene(
                title = "ROSLINA W DONICY",
                caption = "Parapet, monstera, swiatlo z okna",
                background = Color.rgb(0xEF, 0xF6, 0xEA),
                foreground = Color.rgb(0x1B, 0x2E, 0x14),
                accent = Color.rgb(0x33, 0x8A, 0x3A)
            )
        )
    }
}
