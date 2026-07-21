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

    suspend fun clickShutter(config: ShutterConfig): ShutterResult

    /**
     * Waits for files the camera wrote after [sinceMs], returning once one has appeared and
     * then no further sibling arrives for [quietMs]. Empty list means nothing was captured
     * within [timeoutMs].
     */
    suspend fun awaitNewMedia(sinceMs: Long, timeoutMs: Long, quietMs: Long): List<CapturedMedia>

    suspend fun lockScreen(): StepResult
}
