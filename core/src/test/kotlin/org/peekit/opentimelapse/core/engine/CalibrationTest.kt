package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.TimelapseConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationCalculatorTest {

    private val base = TimelapseConfig()

    private fun run(camera: Long, capture: Long, total: Long, captured: Boolean = true) =
        CycleMeasurement(cameraMs = camera, captureMs = capture, totalMs = total, captured = captured)

    @Test
    fun `capture timeout is set well above the slowest observation`() {
        // A Galaxy S20 registered a frame 3.9s after the shutter; 5s was marginal.
        val config = CalibrationCalculator.deriveConfig(
            base,
            listOf(run(camera = 1_000, capture = 3_900, total = 8_000)),
        )

        assertTrue(
            config.capture.captureTimeoutMs >= 3_900 * 2,
            "a timeout costs nothing when generous: ${config.capture.captureTimeoutMs}",
        )
    }

    @Test
    fun `the worst run wins, because the device must survive it`() {
        val config = CalibrationCalculator.deriveConfig(
            base,
            listOf(
                run(camera = 800, capture = 1_200, total = 6_000),
                run(camera = 4_500, capture = 6_000, total = 14_000),
                run(camera = 900, capture = 1_100, total = 6_200),
            ),
        )

        assertTrue(config.capture.captureTimeoutMs >= 6_000 * 2)
        assertTrue(config.delays.cameraForegroundTimeoutMs >= 4_500 * 2)
    }

    @Test
    fun `settle delays stay tight because they are paid every frame`() {
        val config = CalibrationCalculator.deriveConfig(
            base,
            listOf(run(camera = 2_000, capture = 1_000, total = 7_000)),
        )

        // A timeout may be 3x the observation; a settle delay may not.
        assertTrue(
            config.delays.afterCameraReadyMs < config.delays.cameraForegroundTimeoutMs,
            "settle ${config.delays.afterCameraReadyMs} vs timeout ${config.delays.cameraForegroundTimeoutMs}",
        )
        assertTrue(config.delays.afterCameraReadyMs <= 2_000 * 2)
    }

    @Test
    fun `failed cycles are ignored - their timings mean nothing`() {
        val onlyFailures = listOf(run(camera = 99_000, capture = 99_000, total = 99_000, captured = false))

        assertEquals(base, CalibrationCalculator.deriveConfig(base, onlyFailures))
        assertEquals(base, CalibrationCalculator.deriveConfig(base, emptyList()))
    }

    @Test
    fun `minimum interval leaves headroom over the slowest cycle`() {
        // The real failure this prevents: a 12s interval against a ~10s cycle dropped
        // four frames in six on a Galaxy S20.
        val minimum = CalibrationCalculator.minimumIntervalSeconds(
            listOf(run(camera = 2_000, capture = 4_000, total = 10_000)),
        )

        assertTrue(minimum >= 15, "a 10s cycle needs more than 12s of interval, got $minimum")
    }

    @Test
    fun `minimum interval has a sane floor for a fast device`() {
        val minimum = CalibrationCalculator.minimumIntervalSeconds(
            listOf(run(camera = 300, capture = 400, total = 1_200)),
        )
        assertTrue(minimum >= 5, "got $minimum")
    }

    @Test
    fun `settle probe escalates then gives up`() {
        var settle = 500L
        val tried = mutableListOf(settle)
        while (true) {
            settle = CalibrationCalculator.nextWakeSettleMs(settle) ?: break
            tried += settle
        }

        assertTrue(tried.size in 3..12, "escalation should be short: $tried")
        assertTrue(tried.last() <= CalibrationCalculator.MAX_WAKE_SETTLE_MS)
        // Stops rather than retrying forever - a secured lock screen never unlocks.
        assertNull(CalibrationCalculator.nextWakeSettleMs(CalibrationCalculator.MAX_WAKE_SETTLE_MS))
    }
}

class CalibrationCollectorTest {

    private val collector = CalibrationCollector()

    private fun step(step: CycleStep, from: Long, to: Long) {
        collector.emit(EngineEvent.StepStarted(from, step))
        collector.emit(EngineEvent.StepFinished(to, step, ok = true))
    }

    @Test
    fun `derives per-step timings from the engine's own events`() {
        step(CycleStep.WAKE, 1_000, 2_500)
        step(CycleStep.CAMERA_FOREGROUND, 2_500, 5_000)
        step(CycleStep.CONFIRM_CAPTURE, 5_100, 9_000)
        collector.emit(EngineEvent.FrameCaptured(9_100, index = 1, paths = listOf("/a.jpg")))

        val measured = collector.measurements.single()
        assertEquals(1_500, measured.wakeMs)
        assertEquals(2_500, measured.cameraMs)
        assertEquals(3_900, measured.captureMs)
        assertEquals(8_100, measured.totalMs, "whole cycle, first step to captured frame")
        assertTrue(measured.captured)
    }

    @Test
    fun `records a failed cycle without its timings polluting a later one`() {
        step(CycleStep.WAKE, 0, 500)
        collector.emit(EngineEvent.FrameFailed(9_000, CycleStep.CONFIRM_CAPTURE, "timeout"))

        step(CycleStep.WAKE, 10_000, 10_800)
        collector.emit(EngineEvent.FrameCaptured(12_000, index = 1, paths = emptyList()))

        val (failed, ok) = collector.measurements
        assertTrue(!failed.captured && ok.captured)
        assertEquals(800, ok.wakeMs, "the failed cycle's 500ms must not leak forward")
        assertEquals(2_000, ok.totalMs)
    }

    @Test
    fun `passes events through so calibration can still be logged`() {
        val seen = mutableListOf<EngineEvent>()
        val chained = CalibrationCollector { seen += it }

        chained.emit(EngineEvent.Message(1, "hello"))
        assertEquals(1, seen.size)
    }
}
