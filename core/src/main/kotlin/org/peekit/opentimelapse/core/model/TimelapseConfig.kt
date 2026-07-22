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
) {
    /**
     * Resolves combinations that are individually valid but jointly meaningless.
     *
     * Renaming needs to know *which* files a shutter press produced, and MediaStore
     * observation is the only thing that knows. Call before every cycle.
     */
    fun normalized(): TimelapseConfig =
        if (naming.enabled && !capture.verifyViaMediaStore) {
            copy(capture = capture.copy(verifyViaMediaStore = true))
        } else {
            this
        }

    /** Coerced: a zero interval would spin the engine's slot-skipping loop forever. */
    val intervalMs: Long get() = intervalSeconds.coerceAtLeast(1) * 1000L
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
     * Measured on One UI: a swipe dispatched too soon after waking from Always-On Display
     * is silently swallowed, reproducibly. A second attempt costs one delay and rescues
     * the frame instead of dropping it.
     */
    val attempts: Int = 2,
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
     * OEM camera apps need time to open the lens and settle exposure after resuming.
     * Raised after One UI was still showing a transient dialog 4 s post-launch.
     */
    val afterCameraReadyMs: Long = 2_000L,
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
     * Measured on a Galaxy S20: MediaStore registered a frame 3.9s after the shutter, and
     * others took longer than 5s - photos that existed on disk were reported as failures.
     * Costs nothing to be generous, since the wait ends the moment the file appears, and
     * night mode or multi-second Pro exposures are legitimately slow.
     */
    val captureTimeoutMs: Long = 15_000L,
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
}
