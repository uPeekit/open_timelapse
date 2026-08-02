package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.TimelapseConfig
import kotlin.math.roundToLong

// Note: cameraMs measures only how long the camera took to become ready. The deliberate
// settle that follows is spent outside that step, so a derived settle can never be fed
// back into the next measurement of itself.

/** How long each step of one cycle actually took on this device. */
data class CycleMeasurement(
    val wakeMs: Long = 0,
    val unlockMs: Long = 0,
    val cameraMs: Long = 0,
    val shutterMs: Long = 0,
    val captureMs: Long = 0,
    val lockMs: Long = 0,
    val totalMs: Long = 0,
    val captured: Boolean = false,
)

/**
 * Turns observed cycle timings into a config that fits the device.
 *
 * Measurement rather than search: the cycle already reports when each step started and
 * finished, so the only thing that has to be probed is the settle delay between "the
 * screen says it is on" and "the screen accepts injected touches" - which is invisible to
 * every API. Everything else is read off the event stream.
 */
object CalibrationCalculator {

    /**
     * Applies the measurements, keeping generous margins.
     *
     * Timeouts cost nothing when they are too large - each one ends the moment its
     * condition is met - so they are set well above the worst observation. Settle delays
     * are paid on every frame, so they stay close to what was measured.
     */
    fun deriveConfig(base: TimelapseConfig, runs: List<CycleMeasurement>): TimelapseConfig {
        val captured = runs.filter { it.captured }
        if (captured.isEmpty()) return base

        // Ceilings count every run, the failures included. A step that hit its limit did not
        // take *less* time than that limit, and throwing those away is what let the timeout
        // ratchet downward: on a Galaxy S20 the cycles that landed took under 1.5s while two
        // others timed out at 8.2s, so fitting to the winners alone walked the capture window
        // from 15s to 5s - which would have made a long exposure impossible to record at all.
        val capture = worst(runs) { it.captureMs }
        val camera = worst(runs) { it.cameraMs }
        // A settle is paid on every single frame, so it follows only what actually worked.
        val cameraSettle = worst(captured) { it.cameraMs }

        return base.copy(
            delays = base.delays.copy(
                // A settle time, not a timeout: keep it tight but never below what worked.
                afterCameraReadyMs = (cameraSettle * CAMERA_SETTLE_FACTOR)
                    .roundToLong()
                    .coerceIn(MIN_CAMERA_SETTLE_MS, MAX_CAMERA_SETTLE_MS),
                // Never narrowed: a ceiling that is too wide costs nothing, because the wait
                // ends the moment its condition is met. A quick recalibration must not undo
                // what a slow one learned.
                cameraForegroundTimeoutMs = maxOf(
                    (camera * TIMEOUT_FACTOR).roundToLong()
                        .coerceIn(MIN_CAMERA_TIMEOUT_MS, MAX_CAMERA_TIMEOUT_MS),
                    base.delays.cameraForegroundTimeoutMs,
                ),
            ),
            capture = base.capture.copy(
                captureTimeoutMs = maxOf(
                    (capture * TIMEOUT_FACTOR).roundToLong()
                        .coerceIn(MIN_CAPTURE_TIMEOUT_MS, MAX_CAPTURE_TIMEOUT_MS),
                    base.capture.captureTimeoutMs,
                ),
            ),
        )
    }

    /**
     * The shortest interval this device can actually sustain.
     *
     * Measured on a Galaxy S20: a 12s interval against a ~10s cycle silently dropped four
     * frames in six, because each cycle overran its slot. Configuring an interval below
     * this is not a preference, it is a guarantee of lost frames.
     */
    fun minimumIntervalSeconds(runs: List<CycleMeasurement>): Int {
        val captured = runs.filter { it.captured }
        if (captured.isEmpty()) return DEFAULT_MIN_INTERVAL_SECONDS

        val slowest = worst(captured) { it.totalMs }
        val withHeadroom = slowest * INTERVAL_HEADROOM
        return Math.ceil(withHeadroom / 1000.0).toInt().coerceAtLeast(MIN_INTERVAL_FLOOR_SECONDS)
    }

    /**
     * The next settle delay to try after an unlock failed.
     *
     * Returns null once the ceiling is reached, so a device that simply cannot be unlocked
     * by a swipe - a secured lock screen - stops rather than retrying forever.
     */
    fun nextWakeSettleMs(current: Long): Long? =
        (current + WAKE_SETTLE_STEP_MS).takeIf { it <= MAX_WAKE_SETTLE_MS }

    /** The worst case seen, which is what the device must be configured to survive. */
    private fun worst(runs: List<CycleMeasurement>, of: (CycleMeasurement) -> Long): Long =
        runs.maxOf(of).coerceAtLeast(0)

    private const val TIMEOUT_FACTOR = 3.0
    private const val CAMERA_SETTLE_FACTOR = 1.2
    private const val INTERVAL_HEADROOM = 1.5

    private const val MIN_CAMERA_SETTLE_MS = 500L
    private const val MAX_CAMERA_SETTLE_MS = 5_000L
    private const val MIN_CAMERA_TIMEOUT_MS = 4_000L
    private const val MAX_CAMERA_TIMEOUT_MS = 30_000L
    /** Floored well clear of a fast daylight capture: the same camera is far slower at night. */
    private const val MIN_CAPTURE_TIMEOUT_MS = 15_000L
    private const val MAX_CAPTURE_TIMEOUT_MS = 60_000L

    const val WAKE_SETTLE_STEP_MS = 500L
    const val MAX_WAKE_SETTLE_MS = 4_000L

    private const val DEFAULT_MIN_INTERVAL_SECONDS = 15
    private const val MIN_INTERVAL_FLOOR_SECONDS = 5
}

/**
 * Derives [CycleMeasurement]s from the engine's own event stream.
 *
 * Nothing extra is instrumented on the device: every step already reports when it started
 * and finished, so a calibration run is just a normal session being listened to.
 */
class CalibrationCollector(private val downstream: EventSink = NoopEventSink) : EventSink {

    private val runs = mutableListOf<CycleMeasurement>()
    private val startedAt = mutableMapOf<CycleStep, Long>()
    private var current = CycleMeasurement()
    private var cycleStartedAt: Long? = null

    val measurements: List<CycleMeasurement> get() = runs.toList()

    override fun emit(event: EngineEvent) {
        downstream.emit(event)
        when (event) {
            is EngineEvent.StepStarted -> {
                startedAt[event.step] = event.atMs
                if (cycleStartedAt == null) cycleStartedAt = event.atMs
            }

            is EngineEvent.StepFinished -> record(event.step, event.atMs)

            is EngineEvent.FrameCaptured -> finishCycle(event.atMs, captured = true)

            is EngineEvent.FrameFailed -> finishCycle(event.atMs, captured = false)

            else -> Unit
        }
    }

    private fun record(step: CycleStep, finishedAt: Long) {
        val began = startedAt.remove(step) ?: return
        val elapsed = (finishedAt - began).coerceAtLeast(0)
        current = when (step) {
            CycleStep.WAKE -> current.copy(wakeMs = elapsed)
            CycleStep.UNLOCK -> current.copy(unlockMs = elapsed)
            CycleStep.CAMERA_FOREGROUND -> current.copy(cameraMs = elapsed)
            CycleStep.SHUTTER -> current.copy(shutterMs = elapsed)
            CycleStep.CONFIRM_CAPTURE -> current.copy(captureMs = elapsed)
            CycleStep.LOCK -> current.copy(lockMs = elapsed)
            CycleStep.FILE_FRAME -> current
        }
    }

    /**
     * Closes a cycle explicitly.
     *
     * Needed because calibration drives [CycleRunner] directly, and the per-frame events
     * that would otherwise close a cycle are emitted by [TimelapseEngine], one level up.
     */
    fun endCycle(atMs: Long, captured: Boolean) = finishCycle(atMs, captured)

    private fun finishCycle(atMs: Long, captured: Boolean) {
        val began = cycleStartedAt ?: atMs
        runs += current.copy(totalMs = (atMs - began).coerceAtLeast(0), captured = captured)
        current = CycleMeasurement()
        startedAt.clear()
        cycleStartedAt = null
    }
}
