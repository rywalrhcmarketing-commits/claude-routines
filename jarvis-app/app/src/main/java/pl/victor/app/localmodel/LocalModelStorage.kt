package pl.victor.app.localmodel

import android.content.Context
import java.io.File

/**
 * Gdzie leży plik modelu na dysku - i czy jest gotowy do użycia.
 *
 * Katalog ma dziś jeden wpis, więc "czy model jest pobrany" to po prostu
 * "czy plik istnieje i ma sensowny rozmiar" - bez osobnego rejestru
 * metadanych jak w wieloModelowych rozwiązaniach. Gdy katalog urośnie,
 * to pierwsze miejsce do rozbudowy.
 */
object LocalModelStorage {

    private const val MODELS_DIR = "local_models"
    /** Plik uznajemy za uszkodzony/nieukończony poniżej tego ułamka oczekiwanego rozmiaru. */
    private const val MIN_COMPLETE_FRACTION = 0.98

    fun modelsDir(context: Context): File =
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun targetFile(context: Context, entry: LocalModelCatalogEntry): File =
        File(modelsDir(context), entry.expectedFilename)

    fun tempFile(context: Context, entry: LocalModelCatalogEntry): File =
        File(modelsDir(context), "${entry.expectedFilename}.part")

    fun isDownloaded(context: Context, entry: LocalModelCatalogEntry): Boolean {
        val file = targetFile(context, entry)
        if (!file.exists() || !file.isFile) return false
        return file.length() >= (entry.sizeBytes * MIN_COMPLETE_FRACTION).toLong()
    }

    fun delete(context: Context, entry: LocalModelCatalogEntry) {
        targetFile(context, entry).delete()
        tempFile(context, entry).delete()
    }
}
