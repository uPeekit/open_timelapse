package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.EndMode
import org.peekit.opentimelapse.core.model.NamingConfig
import org.peekit.opentimelapse.core.model.SessionConfig
import org.peekit.opentimelapse.core.model.TimelapseConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replays a scripted list of outcomes and can consume virtual time, standing in for a real cycle. */
private class ScriptedExecutor(
    private val time: VirtualTime,
    private val cycleDurationMs: Long = 0L,
    private val outcomes: List<CycleOutcome> = emptyList(),
) : CycleExecutor {

    val seenIndices = mutableListOf<Int>()
    val seenIntervals = mutableListOf<Int>()
    val ranAtMs = mutableListOf<Long>()
    var onCycle: ((Int) -> Unit)? = null

    private var call = 0

    override suspend fun run(config: TimelapseConfig, frameIndex: Int): CycleOutcome {
        ranAtMs += time.nowMs()
        seenIndices += frameIndex
        seenIntervals += config.intervalSeconds
        time.advance(cycleDurationMs)
        onCycle?.invoke(call)
        val outcome = outcomes.getOrElse(call) { CycleOutcome(captured = true) }
        call++
        return outcome
    }
}

class TimelapseEngineTest {

    private val time = VirtualTime()
    private val events = RecordingEventSink()
    private val actuator = FakeActuator()

    private fun engine(executor: CycleExecutor) =
        TimelapseEngine(executor, actuator, time, time, events)

    private fun config(
        intervalSeconds: Int = 30,
        endMode: EndMode = EndMode.MANUAL,
        durationMinutes: Int = 60,
        endAtEpochMs: Long = 0L,
        mode: CycleMode = CycleMode.LOCK_CYCLE,
    ) = TimelapseConfig(
        mode = mode,
        intervalSeconds = intervalSeconds,
        naming = NamingConfig(enabled = true, prefix = "shot", startIndex = 1),
        session = SessionConfig(
            endMode = endMode,
            durationMinutes = durationMinutes,
            endAtEpochMs = endAtEpochMs,
        ),
    )

    @Test
    fun `a secure lock screen is rejected before a single frame is attempted`() = runTest {
        actuator.deviceSecure = true
        val executor = ScriptedExecutor(time)

        val summary = engine(executor).runSession("s1") { config() }

        assertEquals(StopReason.REJECTED, summary.stopReason)
        assertEquals(StartRejection.DEVICE_SECURE_LOCK, summary.rejection)
        assertEquals(0, summary.cyclesRun)
        assertTrue(executor.seenIndices.isEmpty())
        assertTrue(events.events.none { it is EngineEvent.SessionStarted })
    }

    @Test
    fun `awake mode runs on a secure device because it never has to unlock`() = runTest {
        actuator.deviceSecure = true
        val executor = ScriptedExecutor(time)

        val summary = engine(executor).runSession("s1") {
            config(mode = CycleMode.AWAKE, endMode = EndMode.AFTER_DURATION, durationMinutes = 1)
        }

        assertNull(summary.rejection)
        assertEquals(StopReason.DURATION_REACHED, summary.stopReason)
        assertEquals(2, summary.cyclesRun) // slots at 0s and 30s; 60s is the end
    }

    @Test
    fun `a failed frame does not consume its index, so the sequence stays contiguous`() = runTest {
        val executor = ScriptedExecutor(
            time = time,
            outcomes = listOf(
                CycleOutcome(captured = true),
                CycleOutcome(captured = false, failedStep = CycleStep.CONFIRM_CAPTURE, detail = "timeout"),
                CycleOutcome(captured = true),
                CycleOutcome(captured = true),
            ),
        )

        val summary = engine(executor).runSession("s1") {
            config(intervalSeconds = 30, endMode = EndMode.AFTER_DURATION, durationMinutes = 2)
        }

        assertEquals(4, summary.cyclesRun)
        assertEquals(3, summary.framesCaptured)
        assertEquals(1, summary.framesFailed)
        // The retried cycle reuses index 2 rather than leaving a hole at 2.
        assertContentEquals(listOf(1, 2, 2, 3), executor.seenIndices)
        assertContentEquals(listOf(1, 2, 3), events.capturedIndices())
        assertEquals(1, summary.firstIndex)
        assertEquals(3, summary.lastIndex)
    }

    @Test
    fun `a fatal cycle stops the session instead of repeating the fault`() = runTest {
        // Video mode: 200+ MB of footage was produced on a real device before this guard.
        val executor = ScriptedExecutor(
            time = time,
            outcomes = listOf(
                CycleOutcome(captured = false, failedStep = CycleStep.SHUTTER, detail = "video mode", fatal = true),
            ),
        )

        val summary = engine(executor).runSession("s1") { config() }

        assertEquals(1, summary.cyclesRun, "must not attempt a second frame")
        assertEquals(StopReason.ABORTED, summary.stopReason)
        assertEquals(0, summary.framesCaptured)
    }

    @Test
    fun `an overrunning cycle drops slots instead of shifting the whole series`() = runTest {
        val startedAt = time.now
        // Each cycle takes 70s against a 30s interval.
        val executor = ScriptedExecutor(time, cycleDurationMs = 70_000L)

        val summary = engine(executor).runSession("s1") {
            config(intervalSeconds = 30, endMode = EndMode.AFTER_DURATION, durationMinutes = 5)
        }

        assertEquals(4, summary.cyclesRun)
        assertEquals(8, summary.slotsSkipped)
        // Every frame still starts on an exact multiple of the interval.
        assertContentEquals(
            listOf(0L, 90_000L, 180_000L, 270_000L),
            executor.ranAtMs.map { it - startedAt },
        )
    }

    @Test
    fun `after-duration stops at the last slot inside the window`() = runTest {
        val executor = ScriptedExecutor(time)

        val summary = engine(executor).runSession("s1") {
            config(intervalSeconds = 60, endMode = EndMode.AFTER_DURATION, durationMinutes = 5)
        }

        // Slots at 0, 60, 120, 180, 240s run; 300s is the boundary and does not.
        assertEquals(5, summary.cyclesRun)
        assertEquals(StopReason.DURATION_REACHED, summary.stopReason)
    }

    @Test
    fun `an end time in the past stops before shooting anything`() = runTest {
        val executor = ScriptedExecutor(time)

        val summary = engine(executor).runSession("s1") {
            config(endMode = EndMode.AT_TIME, endAtEpochMs = time.now - 1)
        }

        assertEquals(0, summary.cyclesRun)
        assertEquals(StopReason.END_TIME_REACHED, summary.stopReason)
    }

    @Test
    fun `at-time stops at the wall clock boundary`() = runTest {
        val executor = ScriptedExecutor(time)
        val endAt = time.now + 100_000L

        val summary = engine(executor).runSession("s1") {
            config(intervalSeconds = 30, endMode = EndMode.AT_TIME, endAtEpochMs = endAt)
        }

        // Slots at 0, 30, 60, 90s run; 120s is past the end.
        assertEquals(4, summary.cyclesRun)
        assertEquals(StopReason.END_TIME_REACHED, summary.stopReason)
    }

    @Test
    fun `a config edit takes effect on the next cycle, never the running one`() = runTest {
        val executor = ScriptedExecutor(time)
        var interval = 30
        // Edited while the second cycle is mid-flight, as if the user moved the slider.
        executor.onCycle = { call -> if (call == 1) interval = 10 }

        val summary = engine(executor).runSession("s1") {
            config(intervalSeconds = interval, endMode = EndMode.AFTER_DURATION, durationMinutes = 1)
        }

        assertTrue(summary.cyclesRun >= 3)
        // The cycle that was running keeps 30; only the one after it sees 10.
        assertContentEquals(listOf(30, 30, 10), executor.seenIntervals.take(3))
    }

    @Test
    fun `requestStop ends the session after the cycle in flight`() = runTest {
        val executor = ScriptedExecutor(time)
        val sut = engine(executor)
        executor.onCycle = { call -> if (call == 2) sut.requestStop() }

        val summary = sut.runSession("s1") { config() }

        assertEquals(3, summary.cyclesRun)
        assertEquals(StopReason.MANUAL, summary.stopReason)
        assertTrue(events.events.last() is EngineEvent.SessionEnded)
    }
}
