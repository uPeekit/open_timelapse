package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.NamingConfig
import org.peekit.opentimelapse.core.model.ShutterConfig
import org.peekit.opentimelapse.core.model.UnlockConfig

/**
 * Clock and Waiter in one. Waiting advances the clock instead of blocking, so a
 * six-hour session is asserted in microseconds.
 */
class VirtualTime(start: Long = 1_000_000L) : Clock, Waiter {
    var now: Long = start
        private set

    override fun nowMs(): Long = now

    override suspend fun sleep(durationMs: Long) {
        now += durationMs.coerceAtLeast(0L)
    }

    override suspend fun awaitUntil(epochMs: Long) {
        if (epochMs > now) now = epochMs
    }

    /** Simulates time passing outside the engine's control (e.g. a slow camera). */
    fun advance(durationMs: Long) {
        now += durationMs
    }
}

class RecordingEventSink : EventSink {
    val events = mutableListOf<EngineEvent>()

    override fun emit(event: EngineEvent) {
        events += event
    }

    fun stepsStarted(): List<CycleStep> = events.filterIsInstance<EngineEvent.StepStarted>().map { it.step }

    fun stepsSkipped(): List<CycleStep> = events.filterIsInstance<EngineEvent.StepSkipped>().map { it.step }

    fun capturedIndices(): List<Int> = events.filterIsInstance<EngineEvent.FrameCaptured>().map { it.index }
}

fun jpeg(name: String = "20260721_120000.jpg", addedAtMs: Long = 0L) = CapturedMedia(
    uri = "content://media/external/images/media/$name",
    displayName = name,
    mimeType = "image/jpeg",
    sizeBytes = 4_000_000L,
    addedAtMs = addedAtMs,
)

fun dng(name: String = "20260721_120000.dng", addedAtMs: Long = 0L) = CapturedMedia(
    uri = "content://media/external/images/media/$name",
    displayName = name,
    mimeType = "image/x-adobe-dng",
    sizeBytes = 24_000_000L,
    addedAtMs = addedAtMs,
)

/**
 * Device stand-in. Defaults describe the hard case: screen off, keyguard up, camera not
 * running - so a test that wants the easy case has to say so.
 */
class FakeActuator(
    var screenOn: Boolean = false,
    var keyguardShowing: Boolean = true,
    var deviceSecure: Boolean = false,
    var foreground: String? = "com.android.launcher",
) : DeviceActuator {

    val calls = mutableListOf<String>()

    var wakeResult: StepResult = StepResult.Ok
    var swipeResult: StepResult = StepResult.Ok
    var launchResult: StepResult = StepResult.Ok
    var awaitForegroundResult: StepResult = StepResult.Ok
    var shutterResult: ShutterResult = ShutterResult(true, ShutterStrategy.VIEW_ID)
    var lockResult: StepResult = StepResult.Ok

    /** Reports success but the screen stays off - the failure mode the WAKE verify catches. */
    var wakeLeavesScreenOff: Boolean = false

    /** Reports success but the keyguard stays up - what a secured lock screen looks like. */
    var swipeLeavesKeyguardUp: Boolean = false

    /** Which swipe attempt finally lands. 1 = the first, as on a well-behaved device. */
    var unlocksOnAttempt: Int = 1
    private var swipeAttempts = 0

    var media: List<CapturedMedia> = listOf(jpeg())

    override suspend fun isScreenOn(): Boolean = screenOn

    override suspend fun isKeyguardShowing(): Boolean = keyguardShowing

    override suspend fun isDeviceSecure(): Boolean = deviceSecure

    override suspend fun wakeScreen(): StepResult {
        calls += "wakeScreen"
        if (wakeResult.ok && !wakeLeavesScreenOff) screenOn = true
        return wakeResult
    }

    override suspend fun swipeUnlock(config: UnlockConfig): StepResult {
        calls += "swipeUnlock"
        // Whether the keyguard clears is deliberately independent of [swipeResult]: on a
        // real device the gesture callback frequently reports cancellation for a swipe that
        // worked, so the two must be controllable separately.
        swipeAttempts++
        if (!swipeLeavesKeyguardUp && swipeAttempts >= unlocksOnAttempt) keyguardShowing = false
        return swipeResult
    }

    override suspend fun foregroundPackage(): String? = foreground

    override suspend fun launchCamera(packageName: String): StepResult {
        calls += "launchCamera($packageName)"
        return launchResult
    }

    override suspend fun awaitForegroundPackage(packageName: String, timeoutMs: Long): StepResult {
        calls += "awaitForegroundPackage($packageName)"
        if (awaitForegroundResult.ok) foreground = packageName
        return awaitForegroundResult
    }

    override suspend fun clickShutter(config: ShutterConfig): ShutterResult {
        calls += "clickShutter"
        return shutterResult
    }

    override suspend fun awaitNewMedia(
        sinceMs: Long,
        timeoutMs: Long,
        quietMs: Long,
    ): List<CapturedMedia> {
        calls += "awaitNewMedia"
        return media
    }

    var batteryPercent: Int = 100
    var batteryCharging: Boolean = true

    override suspend fun battery(): BatteryReading = BatteryReading(batteryPercent, batteryCharging)

    override suspend fun lockScreen(): StepResult {
        calls += "lockScreen"
        if (lockResult.ok) {
            screenOn = false
            keyguardShowing = true
        }
        return lockResult
    }
}

class FakeFrameStore(
    private val naming: NamingConfig = NamingConfig(enabled = true, prefix = "shot"),
) : FrameStore {

    var failWith: String? = null
    val filed = mutableListOf<Pair<Int, List<String>>>()

    override suspend fun fileFrame(media: List<CapturedMedia>, index: Int): FrameFileResult {
        failWith?.let { return FrameFileResult(ok = false, detail = it) }

        val namer = FrameNamer(naming)
        val paths = media.map { "/DCIM/OpenTimelapse/test/" + namer.fileName(index, it.displayName) }
        filed += index to paths
        return FrameFileResult(ok = true, paths = paths)
    }
}
