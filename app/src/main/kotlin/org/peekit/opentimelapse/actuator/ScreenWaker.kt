package org.peekit.opentimelapse.actuator

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.delay
import org.peekit.opentimelapse.WakeActivity
import org.peekit.opentimelapse.accessibility.AccessibilityBridge
import org.peekit.opentimelapse.core.engine.StepResult

/**
 * Wakes the screen using both available mechanisms, then waits for the display to actually
 * reach STATE_ON.
 *
 * Neither mechanism alone is enough across devices, which is why both always run:
 * on ColorOS the transparent [WakeActivity] wakes the screen and the wakelock is redundant;
 * on One UI the activity launched successfully but left the screen off, and the deprecated
 * FULL_WAKE_LOCK - supposedly ignored on modern Android - is what actually woke it.
 */
class ScreenWaker(
    private val context: Context,
    private val screen: ScreenState,
) {

    suspend fun wake(timeoutMs: Long = 3_000): StepResult {
        if (screen.isOn()) return StepResult.ok("screen was already on")

        val startedFrom = screen.describe()
        launchWakeActivity()
        acquireWakeLock()

        // Polled rather than slept: the wake is asynchronous and its latency varies by
        // device, so waiting a fixed time either wastes it or gives up too early.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (screen.isOn()) {
                return StepResult.ok("woke from $startedFrom")
            }
            delay(POLL_INTERVAL_MS)
        }
        return StepResult.fail("display still ${screen.describe()} after ${timeoutMs}ms")
    }

    private fun launchWakeActivity() {
        // Started from the accessibility service where possible: it is system-bound, which
        // is what exempts it from the background-activity-launch restrictions.
        val launcher: Context = AccessibilityBridge.service ?: context
        val intent = Intent(context, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { launcher.startActivity(intent) }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        runCatching {
            power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG,
            ).acquire(WAKE_LOCK_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val WAKE_LOCK_MS = 5_000L
        const val WAKE_LOCK_TAG = "opentimelapse:wake"
    }
}
