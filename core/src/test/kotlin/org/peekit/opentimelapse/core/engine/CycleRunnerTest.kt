package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.CaptureConfig
import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.NamingConfig
import org.peekit.opentimelapse.core.model.ShutterConfig
import org.peekit.opentimelapse.core.model.TimelapseConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Arbitrary on purpose - the cycle treats the camera package as opaque config. */
private const val CAMERA = "com.example.camera"

class CycleRunnerTest {

    private val time = VirtualTime()
    private val events = RecordingEventSink()
    private val actuator = FakeActuator()
    private val store = FakeFrameStore()

    private fun runner() = CycleRunner(actuator, store, time, time, events)

    private fun config(
        mode: CycleMode = CycleMode.LOCK_CYCLE,
        naming: Boolean = true,
        verify: Boolean = true,
    ) = TimelapseConfig(
        mode = mode,
        shutter = ShutterConfig(packageName = CAMERA),
        naming = NamingConfig(enabled = naming, prefix = "shot"),
        capture = CaptureConfig(verifyViaMediaStore = verify),
    )

    @Test
    fun `locked device runs every step in order and consumes only the delays that ran`() = runTest {
        val startedAt = time.now

        val outcome = runner().run(config(), frameIndex = 1)

        assertContentEquals(
            listOf(
                CycleStep.WAKE,
                CycleStep.UNLOCK,
                CycleStep.CAMERA_FOREGROUND,
                CycleStep.SHUTTER,
                CycleStep.CONFIRM_CAPTURE,
                CycleStep.FILE_FRAME,
                CycleStep.LOCK,
            ),
            events.stepsStarted(),
        )
        assertTrue(outcome.captured)
        assertContentEquals(listOf("/DCIM/OpenTimelapse/test/shot00000001.jpg"), outcome.paths)
        // Every delay whose step ran, and nothing else: no afterShutter, since verification ran.
        val delays = config().delays
        assertEquals(
            delays.afterWakeMs + delays.afterUnlockMs + delays.afterCameraReadyMs,
            time.now - startedAt,
        )
    }

    @Test
    fun `awake mode with camera already up skips the repair steps, their delays, and the lock`() = runTest {
        actuator.screenOn = true
        actuator.keyguardShowing = false
        actuator.foreground = CAMERA
        val startedAt = time.now

        val outcome = runner().run(config(mode = CycleMode.AWAKE), frameIndex = 7)

        assertContentEquals(
            listOf(CycleStep.WAKE, CycleStep.UNLOCK, CycleStep.CAMERA_FOREGROUND),
            events.stepsSkipped(),
        )
        assertContentEquals(
            listOf(CycleStep.SHUTTER, CycleStep.CONFIRM_CAPTURE, CycleStep.FILE_FRAME),
            events.stepsStarted(),
        )
        assertTrue(outcome.captured)
        assertEquals(startedAt, time.now, "skipped steps must not consume their delays")
        assertFalse(actuator.calls.contains("lockScreen"))
    }

    @Test
    fun `awake mode repairs itself when the screen died between frames`() = runTest {
        actuator.screenOn = false
        actuator.keyguardShowing = true
        actuator.foreground = "com.android.launcher"

        val outcome = runner().run(config(mode = CycleMode.AWAKE), frameIndex = 1)

        assertTrue(outcome.captured)
        assertTrue(events.stepsStarted().containsAll(listOf(CycleStep.WAKE, CycleStep.UNLOCK, CycleStep.CAMERA_FOREGROUND)))
        assertFalse(actuator.calls.contains("lockScreen"), "awake mode must never lock")
    }

    @Test
    fun `wake reporting success while the screen stays off fails the cycle`() = runTest {
        actuator.wakeLeavesScreenOff = true

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.WAKE, outcome.failedStep)
        assertFalse(actuator.calls.contains("clickShutter"))
    }

    @Test
    fun `swipe that leaves the keyguard up fails the cycle`() = runTest {
        actuator.swipeLeavesKeyguardUp = true

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.UNLOCK, outcome.failedStep)
    }

    @Test
    fun `unlock succeeds when the gesture worked but the callback reported failure`() = runTest {
        // Observed on Android 15: the keyguard consumes the touch stream to run its dismiss
        // animation, which cancels the accessibility gesture even though the swipe landed.
        actuator.swipeResult = StepResult.fail("gesture cancelled")
        actuator.swipeLeavesKeyguardUp = false

        val outcome = runner().run(config(), frameIndex = 1)

        assertTrue(outcome.captured, "a lying dispatch callback must not fail the cycle")
        assertTrue(actuator.calls.contains("clickShutter"))
    }

    @Test
    fun `a swallowed first swipe is retried rather than dropping the frame`() = runTest {
        // One UI swallows a swipe dispatched too soon after waking from AOD, reproducibly.
        actuator.unlocksOnAttempt = 2

        val outcome = runner().run(config(), frameIndex = 1)

        assertTrue(outcome.captured)
        assertEquals(2, actuator.calls.count { it == "swipeUnlock" })
    }

    @Test
    fun `unlock gives up once the configured attempts are exhausted`() = runTest {
        actuator.unlocksOnAttempt = 5 // more than UnlockConfig.attempts

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.UNLOCK, outcome.failedStep)
        assertEquals(2, actuator.calls.count { it == "swipeUnlock" })
    }

    @Test
    fun `unlock still fails when the keyguard survives a dispatch that claimed success`() = runTest {
        actuator.swipeResult = StepResult.Ok
        actuator.swipeLeavesKeyguardUp = true

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.UNLOCK, outcome.failedStep)
    }

    @Test
    fun `a shutter dispatch that reports failure defers to capture verification`() = runTest {
        actuator.shutterResult = ShutterResult(ok = false, strategy = ShutterStrategy.NONE, detail = "cancelled")
        actuator.media = listOf(jpeg())

        val outcome = runner().run(config(verify = true), frameIndex = 1)

        assertTrue(outcome.captured, "MediaStore saw the file, which outranks the dispatch result")
    }

    @Test
    fun `a shutter dispatch failure aborts when there is no verification to defer to`() = runTest {
        actuator.shutterResult = ShutterResult(ok = false, strategy = ShutterStrategy.NONE, detail = "cancelled")

        val outcome = runner().run(config(naming = false, verify = false), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.SHUTTER, outcome.failedStep)
    }

    @Test
    fun `a video-mode shutter is never pressed and aborts the session`() = runTest {
        actuator.shutterResult = ShutterResult(
            ok = false,
            strategy = ShutterStrategy.VIEW_ID,
            videoControlDetected = true,
        )

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertTrue(outcome.fatal, "retrying would just toggle a recording")
        assertEquals(CycleStep.SHUTTER, outcome.failedStep)
        assertFalse(actuator.calls.contains("awaitNewMedia"), "must not fall through to verification")
    }

    @Test
    fun `camera never reaching the foreground fails the cycle`() = runTest {
        actuator.awaitForegroundResult = StepResult.fail("timeout")

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.CAMERA_FOREGROUND, outcome.failedStep)
        assertFalse(actuator.calls.contains("clickShutter"))
    }

    @Test
    fun `a failed cycle still locks the screen so the interval is not spent awake`() = runTest {
        actuator.awaitForegroundResult = StepResult.fail("timeout")

        runner().run(config(), frameIndex = 1)

        assertTrue(actuator.calls.contains("lockScreen"))
        assertFalse(actuator.screenOn)
    }

    @Test
    fun `shutter not found and nothing captured fails at the verification step`() = runTest {
        // With verification on, the authoritative failure is "no file appeared" - the
        // dispatch result is only a hint, so the cycle must not blame the shutter step.
        actuator.shutterResult = ShutterResult(ok = false, strategy = ShutterStrategy.NONE, detail = "no node")
        actuator.media = emptyList()

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.CONFIRM_CAPTURE, outcome.failedStep)
    }

    @Test
    fun `coordinate fallback counts as a successful shutter`() = runTest {
        actuator.shutterResult = ShutterResult(ok = true, strategy = ShutterStrategy.COORDINATES)

        val outcome = runner().run(config(), frameIndex = 1)

        assertTrue(outcome.captured)
    }

    @Test
    fun `capture that never lands is unconfirmed and does not reach the frame store`() = runTest {
        actuator.media = emptyList()

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.CONFIRM_CAPTURE, outcome.failedStep)
        assertTrue(store.filed.isEmpty())
    }

    @Test
    fun `capture verification is told which app owns the expected file`() = runTest {
        runner().run(config(), frameIndex = 1)

        assertEquals(CAMERA, actuator.lastMediaOwner, "the watcher needs the camera package to reject stray images")
    }

    @Test
    fun `raw plus jpeg from one press share the frame index`() = runTest {
        actuator.media = listOf(jpeg(), dng())

        val outcome = runner().run(config(), frameIndex = 42)

        assertTrue(outcome.captured)
        assertContentEquals(
            listOf(
                "/DCIM/OpenTimelapse/test/shot00000042.jpg",
                "/DCIM/OpenTimelapse/test/shot00000042.dng",
            ),
            outcome.paths,
        )
    }

    @Test
    fun `with verification off the after-shutter delay is used instead`() = runTest {
        actuator.screenOn = true
        actuator.keyguardShowing = false
        actuator.foreground = CAMERA
        val startedAt = time.now

        val outcome = runner().run(config(naming = false, verify = false), frameIndex = 1)

        assertTrue(outcome.captured)
        assertTrue(events.stepsSkipped().containsAll(listOf(CycleStep.CONFIRM_CAPTURE, CycleStep.FILE_FRAME)))
        assertEquals(300L, time.now - startedAt)
        assertFalse(actuator.calls.contains("awaitNewMedia"))
    }

    @Test
    fun `naming disabled captures the frame and reports the original uris`() = runTest {
        val outcome = runner().run(config(naming = false), frameIndex = 1)

        assertTrue(outcome.captured)
        assertTrue(store.filed.isEmpty())
        assertContentEquals(actuator.media.map { it.uri }, outcome.paths)
    }

    @Test
    fun `a frame that cannot be filed is not counted as captured`() = runTest {
        store.failWith = "destination exists"

        val outcome = runner().run(config(), frameIndex = 1)

        assertFalse(outcome.captured)
        assertEquals(CycleStep.FILE_FRAME, outcome.failedStep)
    }
}
