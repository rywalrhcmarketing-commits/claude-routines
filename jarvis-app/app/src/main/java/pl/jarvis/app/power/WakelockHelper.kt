package pl.jarvis.app.power

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Helper do zarządzania WakeLock - tylko gdy naprawdę potrzebny.
 *
 * Główne zasady:
 * - Nigdy nie trzymaj WakeLock dłużej niż 10s
 * - Zwalniaj natychmiast po zakończeniu
 * - Nie używaj FULL_LOCK - tylko PARTIAL_WAKE_LOCK
 * - Tryb ECO wyłącza proactive wake lock
 */
class WakelockHelper(private val context: Context) {

    private val tag = "WakelockHelper"
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var currentLock: PowerManager.WakeLock? = null
    private var lockAcquiredAt: Long = 0L

    /**
     * Próbuje nabyć krótki WakeLock.
     * @param maxDurationMs auto-timeout (zabezpieczenie)
     * @return true jeśli udało się
     */
    fun acquireShortLock(maxDurationMs: Long = 10_000L, tag: String = "JarvisShort"): Boolean {
        if (currentLock?.isHeld == true) {
            Log.w(this.tag, "Lock already held, releasing first")
            release()
        }
        return try {
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jarvis:$tag"
            )
            lock.setReferenceCounted(false)
            lock.acquire(maxDurationMs)
            currentLock = lock
            lockAcquiredAt = System.currentTimeMillis()
            Log.d(this.tag, "WakeLock acquired: $tag (max ${maxDurationMs}ms)")
            true
        } catch (e: Exception) {
            Log.e(this.tag, "Failed to acquire WakeLock", e)
            false
        }
    }

    /**
     * Zwalnia aktywny WakeLock.
     */
    fun release() {
        val lock = currentLock ?: return
        if (lock.isHeld) {
            val heldMs = System.currentTimeMillis() - lockAcquiredAt
            lock.release()
            Log.d(tag, "WakeLock released after ${heldMs}ms")
        }
        currentLock = null
    }

    /**
     * Ile czasu już trzymamy locka.
     */
    fun heldDurationMs(): Long {
        if (currentLock?.isHeld != true) return 0
        return System.currentTimeMillis() - lockAcquiredAt
    }

    /**
     * Bezpieczny wrapper - automatycznie zwalnia po zakończeniu bloku.
     */
    inline fun <T> withWakeLock(maxDurationMs: Long = 10_000L, block: () -> T): T {
        acquireShortLock(maxDurationMs)
        try {
            return block()
        } finally {
            release()
        }
    }
}
