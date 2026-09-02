package pl.victor.app.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

/**
 * Sprawdza, czy telefon udźwignie dany model lokalny - RAM (twardy warunek,
 * [ActivityManager.MemoryInfo.totalMem]) i wolne miejsce na pobranie.
 *
 * Nie zaokrąglamy RAM do "typowych" wielkości (4/6/8GB) jak robi to
 * niektóre gotowe rozwiązania - porównujemy zgłoszoną wartość wprost do
 * [LocalModelCatalogEntry.minRamGb]. Zaokrąglanie w dół (np. zgłoszone
 * 3.6GB → "4GB" telefon zaokrąglony do koszyka "3.0") potrafi zablokować
 * model na telefonie, dla którego faktycznie jest przeznaczony.
 */
object DeviceCapability {

    private const val BYTES_PER_GB = 1_000_000_000.0

    data class Assessment(
        val supported: Boolean,
        val ramGb: Double,
        val freeStorageGb: Double,
        val blockers: List<String>
    )

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0.0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / BYTES_PER_GB
    }

    private fun freeStorageGb(context: Context): Double =
        StatFs(context.filesDir.absolutePath).availableBytes / BYTES_PER_GB

    fun assess(context: Context, entry: LocalModelCatalogEntry): Assessment {
        val ramGb = totalRamGb(context)
        val freeGb = freeStorageGb(context)
        val blockers = mutableListOf<String>()

        val abiSupported = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
        if (!abiSupported) blockers.add("Niewspierana architektura procesora (${Build.SUPPORTED_ABIS.firstOrNull()})")

        if (ramGb > 0.0 && ramGb < entry.minRamGb) {
            blockers.add("Za mało RAM: %.1fGB (potrzeba min. %.1fGB)".format(ramGb, entry.minRamGb))
        }

        val neededForDownload = (entry.sizeBytes / BYTES_PER_GB) + entry.minFreeStorageGb
        if (freeGb < neededForDownload) {
            blockers.add("Za mało wolnego miejsca: %.1fGB (potrzeba ok. %.1fGB)".format(freeGb, neededForDownload))
        }

        return Assessment(
            supported = blockers.isEmpty(),
            ramGb = ramGb,
            freeStorageGb = freeGb,
            blockers = blockers
        )
    }
}
