package org.peekit.opentimelapse.actuator

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.delay
import org.peekit.opentimelapse.accessibility.AccessibilityBridge
import org.peekit.opentimelapse.accessibility.NodeFinder
import org.peekit.opentimelapse.core.engine.BatteryReading
import org.peekit.opentimelapse.core.engine.CapturedMedia
import org.peekit.opentimelapse.core.engine.DeviceActuator
import org.peekit.opentimelapse.core.engine.ShutterResult
import org.peekit.opentimelapse.core.engine.ShutterStrategy
import org.peekit.opentimelapse.core.engine.StepResult
import org.peekit.opentimelapse.core.model.ShutterConfig
import org.peekit.opentimelapse.core.model.UnlockConfig
import org.peekit.opentimelapse.core.ui.ShutterFinder

/**
 * The Android half of the cycle: implements :core's [DeviceActuator] on top of the
 * accessibility service, the display, and MediaStore.
 *
 * It reports facts and never interprets them - no retries, no policy, no giving up. The
 * engine decides what a failure means.
 */
class AndroidDeviceActuator(
    private val context: Context,
    private val screen: ScreenState = ScreenState(context),
    private val waker: ScreenWaker = ScreenWaker(context, screen),
    private val media: MediaStoreWatcher = MediaStoreWatcher(context),
) : DeviceActuator {

    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)

    override suspend fun isScreenOn(): Boolean = screen.isOn()

    override suspend fun isKeyguardShowing(): Boolean = keyguard?.isKeyguardLocked ?: false

    override suspend fun isDeviceSecure(): Boolean = keyguard?.isDeviceSecure ?: false

    override suspend fun wakeScreen(): StepResult = waker.wake()

    override suspend fun swipeUnlock(config: UnlockConfig): StepResult {
        val service = AccessibilityBridge.awaitService()
            ?: return StepResult.fail("accessibility service is not connected")

        val dispatched = service.swipe(
            startXPercent = config.startXPercent,
            startYPercent = config.startYPercent,
            endXPercent = config.endXPercent,
            endYPercent = config.endYPercent,
            durationMs = config.durationMs,
        )
        // Reported only as a hint; the engine confirms against the keyguard itself.
        return if (dispatched) StepResult.Ok else StepResult.fail("gesture reported cancelled")
    }

    override suspend fun foregroundPackage(): String? =
        AccessibilityBridge.service?.foregroundPackage()

    override suspend fun launchCamera(packageName: String): StepResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return StepResult.fail("no launch intent for $packageName")

        // Deliberately no CLEAR_TASK/CLEAR_TOP: resuming the existing task is what preserves
        // the user's Pro mode settings, which is the whole point of driving the OEM app.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val launcher: Context = AccessibilityBridge.service ?: context
        return runCatching { launcher.startActivity(intent) }
            .fold(
                onSuccess = { StepResult.Ok },
                onFailure = { StepResult.fail("could not launch $packageName: ${it.message}") },
            )
    }

    override suspend fun awaitForegroundPackage(packageName: String, timeoutMs: Long): StepResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (foregroundPackage() == packageName) return StepResult.Ok
            delay(FOREGROUND_POLL_MS)
        }
        return StepResult.fail("$packageName did not reach the foreground within ${timeoutMs}ms")
    }

    override suspend fun clickShutter(config: ShutterConfig): ShutterResult {
        val service = AccessibilityBridge.awaitService()
            ?: return ShutterResult(false, ShutterStrategy.NONE, "accessibility service is not connected")

        val resolved = NodeFinder.resolve(service.rootInActiveWindow, config, service.screenBounds())
            ?: return ShutterResult(false, ShutterStrategy.NONE, "no shutter candidate found")

        // Refused, not pressed. The same node is a shutter in photo mode and a record button
        // in video mode; pressing it there records continuously and captures no frames.
        resolved.node?.let { node ->
            if (ShutterFinder.isVideoControl(node)) {
                return ShutterResult(
                    ok = false,
                    strategy = resolved.strategy,
                    detail = "resolved control is a video recorder (${node.contentDescription ?: node.viewId})",
                    videoControlDetected = true,
                )
            }
        }

        val how = service.clickAt(resolved.bounds)
        return ShutterResult(
            ok = true,
            strategy = resolved.strategy,
            detail = "${resolved.detail}; clicked via $how",
        )
    }

    override suspend fun awaitNewMedia(
        sinceMs: Long,
        timeoutMs: Long,
        quietMs: Long,
    ): List<CapturedMedia> = media.awaitNewMedia(sinceMs, timeoutMs, quietMs)

    override suspend fun battery(): BatteryReading = batteryNow()

    /**
     * The synchronous read behind [battery]. Exposed non-suspend so the control server can
     * call it from its own socket thread without a coroutine.
     *
     * Uses the sticky ACTION_BATTERY_CHANGED rather than BatteryManager properties: it is the
     * one source that carries level and charge status in the same snapshot, so the two cannot
     * disagree across a plug event.
     */
    fun batteryNow(): BatteryReading {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // Unreadable battery must not stop a session: report full and on the charger.
            ?: return BatteryReading(percent = 100, charging = true)

        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return BatteryReading(percent = 100, charging = true)

        val plugged = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return BatteryReading(
            percent = level * 100 / scale,
            charging = plugged == BatteryManager.BATTERY_STATUS_CHARGING ||
                plugged == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    override suspend fun lockScreen(): StepResult {
        val service = AccessibilityBridge.service
            ?: return StepResult.fail("accessibility service is not connected")
        return if (service.lockScreen()) StepResult.Ok else StepResult.fail("lock action refused")
    }

    private companion object {
        const val FOREGROUND_POLL_MS = 150L
    }
}
