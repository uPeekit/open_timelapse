package org.peekit.opentimelapse

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import org.peekit.opentimelapse.spike.SpikeLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Wakes the screen and dismisses itself. Shows nothing.
 *
 * Whether this can be started at all from the background is exactly what Phase 0
 * probe 1 measures - a blocked launch does not throw, so [lastStartedAtMs] is the
 * only reliable evidence it actually ran.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        lastStartedAtMs.set(System.currentTimeMillis())
        SpikeLog.log("WakeActivity.onCreate ran")
        finish()
    }

    companion object {
        /** 0 means it has never run. Read by the probes to detect a silently blocked launch. */
        val lastStartedAtMs = AtomicLong(0L)

        fun startedSince(sinceMs: Long): Boolean = lastStartedAtMs.get() >= sinceMs
    }
}
