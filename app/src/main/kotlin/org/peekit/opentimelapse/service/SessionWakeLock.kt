package org.peekit.opentimelapse.service

import android.content.Context
import android.os.PowerManager

/**
 * A partial wakelock held while the cycle is doing something.
 *
 * The CPU must stay up between waking the screen and locking it again, or the work is
 * suspended mid-cycle. It is deliberately *released* across long inter-frame waits, where
 * an alarm takes over - holding it for hours would drain the battery for nothing.
 */
class SessionWakeLock(context: Context) {

    private val lock: PowerManager.WakeLock? =
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            ?.apply { setReferenceCounted(false) }

    val isHeld: Boolean get() = lock?.isHeld == true

    fun acquire() {
        val held = lock ?: return
        // Bounded so a crash cannot leave the CPU pinned awake forever.
        if (!held.isHeld) held.acquire(MAX_HOLD_MS)
    }

    fun release() {
        val held = lock ?: return
        if (held.isHeld) runCatching { held.release() }
    }

    private companion object {
        const val TAG = "opentimelapse:session"
        const val MAX_HOLD_MS = 10 * 60 * 1000L
    }
}
