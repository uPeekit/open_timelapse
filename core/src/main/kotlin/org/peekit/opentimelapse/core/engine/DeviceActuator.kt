package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.ShutterConfig
import org.peekit.opentimelapse.core.model.UnlockConfig

/**
 * Everything the engine needs from the device. The Android implementation sits behind
 * this so the whole cycle can be exercised on the JVM with a fake.
 *
 * Contract: implementations never throw for expected failures - they return a failed
 * [StepResult]. Exceptions mean a programming error.
 */
interface DeviceActuator {

    suspend fun isScreenOn(): Boolean

    suspend fun isKeyguardShowing(): Boolean

    /** True when the lock screen needs a PIN/pattern/password, which a swipe cannot pass. */
    suspend fun isDeviceSecure(): Boolean

    suspend fun wakeScreen(): StepResult

    suspend fun swipeUnlock(config: UnlockConfig): StepResult

    suspend fun foregroundPackage(): String?

    /** Resumes the existing camera task where possible, so Pro mode settings survive. */
    suspend fun launchCamera(packageName: String): StepResult

    suspend fun awaitForegroundPackage(packageName: String, timeoutMs: Long): StepResult

    /**
     * Waits for a real shutter control to appear in the camera's UI.
     *
     * This is the observable version of "the camera is ready": the package being in the
     * foreground says nothing about whether it has finished opening the lens and drawing
     * its controls. Fails when nothing recognisable appeared, which is not fatal - the
     * coordinate fallback in [clickShutter] exists for camera apps that expose no usable
     * node at all.
     */
    suspend fun awaitShutterReady(config: ShutterConfig, timeoutMs: Long): StepResult

    suspend fun clickShutter(config: ShutterConfig): ShutterResult

    /**
     * Waits for files the camera wrote after [sinceMs], returning once one has appeared and
     * then no further sibling arrives for [quietMs]. Empty list means nothing was captured
     * within [timeoutMs].
     *
     * [expectedOwner] is the camera package; files it owns are preferred over anything else
     * that happened to land in the window - see [CaptureAttribution].
     */
    suspend fun awaitNewMedia(
        sinceMs: Long,
        timeoutMs: Long,
        quietMs: Long,
        expectedOwner: String,
    ): List<CapturedMedia>

    suspend fun lockScreen(): StepResult

    /** Battery level and whether it is on a charger, for the floor and the webhooks. */
    suspend fun battery(): BatteryReading
}
