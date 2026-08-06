package org.peekit.opentimelapse.core.model

import kotlinx.serialization.Serializable

/**
 * The complete user configuration. Persisted as a single JSON blob; every field has a
 * default so an older stored config decodes cleanly after new fields are added.
 *
 * Defaults name no manufacturer: anything device-specific is detected at setup, so the
 * same defaults work on any Android device.
 */
@Serializable
data class TimelapseConfig(
    val mode: CycleMode = CycleMode.LOCK_CYCLE,
    val intervalSeconds: Int = 30,
    val unlock: UnlockConfig = UnlockConfig(),
    val delays: DelayConfig = DelayConfig(),
    val shutter: ShutterConfig = ShutterConfig(),
    val capture: CaptureConfig = CaptureConfig(),
    val naming: NamingConfig = NamingConfig(),
    val session: SessionConfig = SessionConfig(),
    val calibration: CalibrationState = CalibrationState(),
    val charging: ChargingConfig = ChargingConfig(),
    val network: NetworkConfig = NetworkConfig(),
    /**
     * A hand-edited ffmpeg command reused for every render, blank until someone takes over.
     * Kept in config rather than per session: a command that worked for one shoot is
     * almost always the one wanted for the next.
     */
    val customRenderCommand: String = "",
    /** Open the finished video automatically, so a render can be checked at a glance. */
    val openVideoAfterRender: Boolean = false,
) {
    /**
     * Resolves combinations that are individually valid but jointly meaningless.
     *
     * Renaming needs to know *which* files a shutter press produced, and MediaStore
     * observation is the only thing that knows. Call before every cycle.
     */
    fun normalized(): TimelapseConfig {
        var resolved = this
        if (naming.enabled && !capture.verifyViaMediaStore) {
            resolved = resolved.copy(capture = resolved.capture.copy(verifyViaMediaStore = true))
        }
        // Repairs a window narrowed by the timeout ratchet in builds before 0.5.1, which
        // could walk it down to 5s - too short for night mode or any long exposure, and
        // never a number the user chose: the app derived it.
        if (resolved.capture.captureTimeoutMs < MIN_SAFE_CAPTURE_TIMEOUT_MS) {
            resolved = resolved.copy(
                capture = resolved.capture.copy(captureTimeoutMs = MIN_SAFE_CAPTURE_TIMEOUT_MS),
            )
        }
        return resolved
    }

    /** Coerced: a zero interval would spin the engine's slot-skipping loop forever. */
    val intervalMs: Long get() = intervalSeconds.coerceAtLeast(1) * 1000L

    companion object {
        /** No camera on any tested device confirmed a frame reliably below this. */
        const val MIN_SAFE_CAPTURE_TIMEOUT_MS = 15_000L
    }
}

enum class CycleMode {
    /** Wake, unlock, raise the camera, shoot, lock again. Saves battery, more moving parts. */
    LOCK_CYCLE,

    /** Leave the camera up and the screen on; only shoot. Robust, but drains the battery. */
    AWAKE,
}

/**
 * Unlock swipe path. Positions are fractions of the screen (0.0-1.0) so one config
 * survives a change of device or resolution.
 */
@Serializable
data class UnlockConfig(
    val startXPercent: Float = 0.5f,
    val startYPercent: Float = 0.8f,
    val endXPercent: Float = 0.5f,
    val endYPercent: Float = 0.3f,
    val durationMs: Long = 300L,
    /**
     * Measured on One UI: a swipe dispatched too soon after waking from Always-On Display is
     * silently swallowed, reproducibly. Each retry is verified against the keyguard and costs
     * nothing once it has worked, so the cap is set where a genuinely stuck lock screen gives
     * up rather than where a swallowed swipe would - two was low enough to lose frames.
     */
    val attempts: Int = 4,
)

@Serializable
data class DelayConfig(
    /**
     * 500 ms was measured to be too short: on One UI a swipe 800 ms after wake failed
     * 2/2 times, while ~1.5 s succeeded every time. The screen reports itself on before
     * it will actually accept injected touches.
     */
    val afterWakeMs: Long = 1_500L,
    val afterUnlockMs: Long = 400L,
    /**
     * The residual settle after the shutter control has actually been seen on screen.
     *
     * Most of this wait used to be guesswork about how long the camera takes to draw itself,
     * which is now awaited instead - see [DeviceActuator.awaitShutterReady]. What is left is
     * the part still not observable: exposure and focus converging once the controls are up.
     */
    val afterCameraReadyMs: Long = 800L,
    /** Only used when capture verification is off; otherwise we wait for the real file. */
    val afterShutterMs: Long = 300L,
    /**
     * A cold camera start is far slower than a resume: 4s was enough when the app was
     * already warm in recents and timed out every cycle when it was not.
     */
    val cameraForegroundTimeoutMs: Long = 12_000L,
)

/**
 * How to find the shutter button. Nothing here is hardcoded to a manufacturer: the
 * package is resolved from whatever app the device answers IMAGE_CAPTURE with, and the
 * button itself is found by [org.peekit.opentimelapse.core.ui.ShutterFinder] when no view id
 * has been calibrated.
 */
@Serializable
data class ShutterConfig(
    val mode: ShutterMode = ShutterMode.AUTO,
    /** Empty until resolved from the device's default camera app. */
    val packageName: String = "",
    val viewId: String = "",
    val contentDescription: String = "",
    val fallbackXPercent: Float = 0.5f,
    val fallbackYPercent: Float = 0.85f,
)

enum class ShutterMode {
    /** View id, then content description, then geometric detection, then coordinates. */
    AUTO,
    ACCESSIBILITY_ID,
    CONTENT_DESCRIPTION,
    COORDINATES,
}

@Serializable
data class CaptureConfig(
    val verifyViaMediaStore: Boolean = true,
    /**
     * A ceiling, not a cost: the wait ends the moment the file appears, so being generous
     * is free and being tight is not. Measured on a Galaxy S20, MediaStore registered a
     * frame 3.9s after the shutter and others took longer than 8s - photos that existed on
     * disk were reported as failures. Night mode and a multi-second Pro exposure are
     * legitimately slower again, and [org.peekit.opentimelapse.core.engine.CaptureWindow]
     * widens this further if it sees one.
     */
    val captureTimeoutMs: Long = 30_000L,
    /** After the first new file, how long to keep collecting siblings (the DNG next to the JPEG). */
    val siblingQuietMs: Long = 700L,
)

/**
 * Renaming frames to `<prefix><zero-padded index>` so the series feeds `ffmpeg -i name%08d.jpg`
 * directly. When disabled the camera's own filenames and metadata are left untouched.
 */
@Serializable
data class NamingConfig(
    val enabled: Boolean = false,
    val prefix: String = "timelapse",
    val padWidth: Int = 8,
    val startIndex: Int = 1,
)

@Serializable
data class SessionConfig(
    val startTrigger: StartTrigger = StartTrigger.FIRST_MANUAL_SHOT,
    /**
     * Grace period before the first frame when [StartTrigger.TIMER] is used. The app
     * resumes whatever mode the camera was left in - it never changes it - so Pro, RAW or
     * Night all work, but only if the user gets to choose first.
     */
    val startDelaySeconds: Int = 20,
    /** How long to wait for that first manual photo before giving up. */
    val manualShotTimeoutMinutes: Int = 10,
    val endMode: EndMode = EndMode.MANUAL,
    val durationMinutes: Int = 60,
    val endAtEpochMs: Long = 0L,
    /**
     * Stop rather than flatten the phone. Zero disables it.
     *
     * Needs no network, which is the point: charging webhooks are best effort - the socket
     * may be unplugged, the hub down, the wifi gone - and this is what actually protects
     * the battery when they fail.
     */
    val stopBelowBatteryPercent: Int = 15,
)

enum class EndMode { MANUAL, AFTER_DURATION, AT_TIME }

/** What begins the interval schedule. */
enum class StartTrigger {
    /**
     * The user takes one photo by hand and that becomes frame 1.
     *
     * Better than a countdown because a countdown is a guess at how long setting up takes:
     * too short and it starts before Pro mode is dialled in, too long and the user waits
     * for nothing. A manual shot happens exactly when they are ready, and the first frame
     * is one they framed. Detection is free - it is the same MediaStore wait that confirms
     * every other frame.
     */
    FIRST_MANUAL_SHOT,

    /** Shoot after a fixed grace period. Kept for unattended restarts. */
    TIMER,
}

/**
 * What calibration learned about this device.
 *
 * Timings vary enough between phones to lose most of a timelapse - a 12s interval against
 * a ~10s cycle dropped four frames in six on one device - so they are measured rather than
 * guessed, and [minIntervalSeconds] is what stops the user configuring a guaranteed loss.
 */
@Serializable
data class CalibrationState(
    val completed: Boolean = false,
    val declined: Boolean = false,
    val atMs: Long = 0L,
    /** Derived from the slowest measured cycle; 0 until calibration has run. */
    val minIntervalSeconds: Int = 0,
    /** Which camera app the measurements belong to; they do not transfer to another. */
    val cameraPackage: String = "",
) {
    fun appliesTo(camera: String): Boolean = completed && cameraPackage == camera

    /**
     * Folds a fresh measurement in, keeping the slowest one seen for this camera app.
     *
     * Cycle time swings with whether the camera cold-starts: the same Galaxy S20 measured 18s
     * and then 9s minutes apart. Taking the newest would let one lucky run erase the knowledge
     * that this phone can be slow, and the whole point of the figure is to warn before frames
     * are lost. A different camera app carries nothing over - its timings are unrelated.
     */
    fun advanced(minIntervalSeconds: Int, camera: String, atMs: Long): CalibrationState {
        val floor = if (appliesTo(camera)) {
            maxOf(minIntervalSeconds, this.minIntervalSeconds)
        } else {
            minIntervalSeconds
        }
        return copy(
            completed = true,
            atMs = atMs,
            minIntervalSeconds = floor,
            cameraPackage = camera,
        )
    }
}

/**
 * Switching a smart socket when the battery crosses a threshold.
 *
 * The app deliberately does nothing clever: it calls a URL the user configures, so it works
 * with Home Assistant, Node-RED, IFTTT or anything else, and the orchestration stays out of
 * a timelapse app.
 *
 * Active only while a session runs. That avoids a permanently running battery watcher and
 * lets the listener share the lifetime of the foreground service that already exists.
 *
 * The 40-80 band is not arbitrary: holding a lithium cell at 100% while it sits on a
 * charger for days is what wears it out, and a phone shooting timelapses on a windowsill is
 * exactly that case.
 */
@Serializable
data class ChargingConfig(
    val enabled: Boolean = false,
    val lowPercent: Int = 40,
    val highPercent: Int = 80,
    /**
     * Method and body are per-URL, not shared: the on and off endpoints are frequently built
     * differently - a Shelly plug toggles with two different GET URLs and no body, while Home
     * Assistant wants a POST with a JSON payload that differs for on and off. One shared verb
     * and body could not express that.
     */
    val startChargingUrl: String = "",
    val startMethod: String = "POST",
    val startBody: String = "",
    val stopChargingUrl: String = "",
    val stopMethod: String = "POST",
    val stopBody: String = "",
)
