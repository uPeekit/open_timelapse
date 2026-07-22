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

    /**
     * Acquires, or re-arms the timeout if already held.
     *
     * The re-arming is the point. The lock is bounded so that a crash cannot pin the CPU
     * awake forever, but it used to be taken once at session start and never refreshed -
     * so every session quietly lost its wakelock after [MAX_HOLD_MS]. Intervals shorter
     * than the alarm threshold wait with delay(), which stops being a timer the moment the
     * CPU is allowed to sleep, and frames started drifting late roughly ten minutes in.
     *
     * Call it on every cycle; acquiring an already-held, non-reference-counted lock simply
     * restarts its timeout.
     */
    fun acquire() {
        val held = lock ?: return
        held.acquire(MAX_HOLD_MS)
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
