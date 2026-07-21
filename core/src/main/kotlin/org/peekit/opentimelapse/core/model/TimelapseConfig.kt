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
    val cameraForegroundTimeoutMs: Long = 4_000L,
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
    /** Generous: night mode and multi-second Pro exposures are legitimate. */
    val captureTimeoutMs: Long = 5_000L,
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
    val endMode: EndMode = EndMode.MANUAL,
    val durationMinutes: Int = 60,
    val endAtEpochMs: Long = 0L,
)

enum class EndMode { MANUAL, AFTER_DURATION, AT_TIME }
