package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.TimelapseConfig

data class CycleOutcome(
    /** True only when the frame is confirmed and filed - i.e. the index was consumed. */
    val captured: Boolean,
    val paths: List<String> = emptyList(),
    val failedStep: CycleStep? = null,
    val detail: String? = null,
    /** Retrying will not help; the session must stop and tell the user. */
    val fatal: Boolean = false,
)

/** Lets [TimelapseEngine]'s scheduling be tested without driving a whole fake device. */
fun interface CycleExecutor {
    suspend fun run(config: TimelapseConfig, frameIndex: Int): CycleOutcome
}

/**
 * Runs one capture cycle.
 *
 * The steps are *conditional*, not a fixed script: each one checks whether its goal is
 * already met and no-ops if so (emitting [EngineEvent.StepSkipped]), and its delay is
 * skipped with it. That is what lets a single pipeline serve both cycle modes - AWAKE
 * normally executes only shutter/confirm/file, but repairs itself through wake, unlock
 * and camera-launch if the screen died in the meantime.
 */
class CycleRunner(
    private val actuator: DeviceActuator,
    private val frameStore: FrameStore,
    private val clock: Clock,
    private val waiter: Waiter,
    private val events: EventSink,
) : CycleExecutor {

    /**
     * The slowest confirmed capture so far, for [CaptureWindow]. Session-scoped: a runner is
     * built per session, so a night shoot's long exposures do not widen a later daylight one.
     */
    private var longestCaptureMs = 0L

    override suspend fun run(config: TimelapseConfig, frameIndex: Int): CycleOutcome {
        val outcome = runSteps(config, frameIndex)

        if (config.mode == CycleMode.LOCK_CYCLE) {
            // Attempted even after a failed cycle: an unlocked screen left on would burn
            // the battery for the whole interval.
            runStep(CycleStep.LOCK) { actuator.lockScreen() }
        }
        return outcome
    }

    private suspend fun runSteps(config: TimelapseConfig, frameIndex: Int): CycleOutcome {
        val delays = config.delays

        if (actuator.isScreenOn()) {
            skip(CycleStep.WAKE, "screen already on")
        } else {
            val result = runStep(CycleStep.WAKE) {
                val wake = actuator.wakeScreen()
                if (!wake.ok) {
                    wake
                } else {
                    waiter.sleep(delays.afterWakeMs)
                    if (actuator.isScreenOn()) StepResult.Ok
                    else StepResult.fail("screen still off ${delays.afterWakeMs}ms after wake")
                }
            }
            if (!result.ok) return failed(CycleStep.WAKE, result.detail)
        }

        if (!actuator.isKeyguardShowing()) {
            skip(CycleStep.UNLOCK, "keyguard not showing")
        } else {
            val result = runStep(CycleStep.UNLOCK) {
                // The gesture callback is NOT authoritative. Measured on Android 15: when a
                // swipe actually dismisses the keyguard, the system grabs the touch stream to
                // run its own dismiss animation, and the accessibility gesture is reported
                // cancelled - the same call reported "completed" on a later identical run.
                // Device state is the only reliable signal, and a swallowed first swipe is
                // common enough (One UI, waking from AOD) to be worth retrying.
                var outcome = StepResult.fail("no unlock attempt was made")
                for (attempt in 1..config.unlock.attempts.coerceAtLeast(1)) {
                    val swipe = actuator.swipeUnlock(config.unlock)
                    waiter.sleep(delays.afterUnlockMs)

                    if (!actuator.isKeyguardShowing()) {
                        outcome = if (attempt == 1 && swipe.ok) {
                            StepResult.Ok
                        } else {
                            StepResult.ok("unlocked on attempt $attempt (dispatch: ${swipe.describe()})")
                        }
                        break
                    }
                    outcome = StepResult.fail(
                        "keyguard still showing after $attempt attempt(s) (dispatch: ${swipe.describe()})"
                    )
                }
                outcome
            }
            if (!result.ok) return failed(CycleStep.UNLOCK, result.detail)
        }

        val cameraPackage = config.shutter.packageName
        if (actuator.foregroundPackage() == cameraPackage) {
            skip(CycleStep.CAMERA_FOREGROUND, "$cameraPackage already in foreground")
        } else {
            val result = runStep(CycleStep.CAMERA_FOREGROUND) {
                val launch = actuator.launchCamera(cameraPackage)
                if (!launch.ok) {
                    launch
                } else {
                    val appeared =
                        actuator.awaitForegroundPackage(cameraPackage, delays.cameraForegroundTimeoutMs)
                    if (!appeared.ok) {
                        appeared
                    } else {
                        // Awaited, not guessed: the package being in front says nothing about
                        // whether the camera has drawn its controls yet, and a cold start is
                        // far slower than a resume. A miss is not fatal - some camera apps
                        // expose no usable node and the coordinate fallback handles them - so
                        // the settle below is still paid either way.
                        // Bounded well under the camera timeout: controls appear shortly
                        // after the app does, so one that is not there within a few seconds
                        // is not coming. Some camera apps expose no usable node at all, and
                        // waiting the full timeout for them would be paid on every frame.
                        val ready = actuator.awaitShutterReady(
                            config.shutter,
                            minOf(delays.cameraForegroundTimeoutMs, MAX_SHUTTER_WAIT_MS),
                        )
                        if (ready.ok) StepResult.Ok else StepResult.ok(ready.describe())
                    }
                }
            }
            if (!result.ok) return failed(CycleStep.CAMERA_FOREGROUND, result.detail)

            // Paid outside the measured step, deliberately. Inside it, calibration derived the
            // next settle from a duration that already contained the last one - 1.2x itself
            // every run - which climbed until it pinned at the ceiling and cost seconds on
            // every frame. Measuring only how long the camera took keeps that honest.
            waiter.sleep(delays.afterCameraReadyMs)
        }

        // Recorded before the click so capture detection cannot miss a fast write.
        val shutterAtMs = clock.nowMs()
        events.emit(EngineEvent.StepStarted(shutterAtMs, CycleStep.SHUTTER))
        val shutter = actuator.clickShutter(config.shutter)
        events.emit(
            EngineEvent.StepFinished(
                atMs = clock.nowMs(),
                step = CycleStep.SHUTTER,
                ok = shutter.ok,
                detail = shutter.detail ?: "matched by ${shutter.strategy}",
            )
        )
        if (shutter.videoControlDetected) {
            // Never retried: every attempt would toggle a recording, and an unattended
            // session would fill the device instead of shooting frames.
            return CycleOutcome(
                captured = false,
                failedStep = CycleStep.SHUTTER,
                detail = "the camera is in video mode - switch it to photo mode",
                fatal = true,
            )
        }

        if (!shutter.ok) {
            // Same lesson as the unlock swipe: a dispatch can report failure and still have
            // landed. When MediaStore verification is on we have a signal that cannot lie,
            // so defer to it instead of aborting on the weaker one.
            if (!config.capture.verifyViaMediaStore) {
                return failed(CycleStep.SHUTTER, shutter.detail ?: "shutter not found")
            }
            events.emit(
                EngineEvent.Message(
                    clock.nowMs(),
                    "shutter dispatch reported failure; deferring to capture verification",
                )
            )
        }

        val media: List<CapturedMedia>
        if (config.capture.verifyViaMediaStore) {
            events.emit(EngineEvent.StepStarted(clock.nowMs(), CycleStep.CONFIRM_CAPTURE))
            val window = CaptureWindow.timeoutFor(
                configuredMs = config.capture.captureTimeoutMs,
                longestObservedMs = longestCaptureMs,
            )
            media = actuator.awaitNewMedia(
                sinceMs = shutterAtMs,
                timeoutMs = window,
                quietMs = config.capture.siblingQuietMs,
                expectedOwner = config.shutter.packageName,
            )
            val confirmed = media.isNotEmpty()
            if (confirmed) {
                // Remembered so the window can grow ahead of a scene whose exposures are
                // getting longer. Includes the sibling quiet period, which only makes the
                // measurement more conservative.
                longestCaptureMs = maxOf(longestCaptureMs, clock.nowMs() - shutterAtMs)
            }
            events.emit(
                EngineEvent.StepFinished(
                    atMs = clock.nowMs(),
                    step = CycleStep.CONFIRM_CAPTURE,
                    ok = confirmed,
                    detail = if (confirmed) media.joinToString { it.displayName }
                    else "no new file within ${window}ms",
                )
            )
            if (!confirmed) return failed(CycleStep.CONFIRM_CAPTURE, "capture not confirmed")
        } else {
            skip(CycleStep.CONFIRM_CAPTURE, "verification disabled")
            waiter.sleep(delays.afterShutterMs)
            media = emptyList()
        }

        if (!config.naming.enabled) {
            skip(CycleStep.FILE_FRAME, "naming disabled")
            // Prefer the filesystem path: it is what a renderer needs, since a content://
            // URI cannot be opened by ffmpeg. Falls back to the URI only when no path is known.
            return CycleOutcome(captured = true, paths = media.map { it.path ?: it.uri })
        }

        events.emit(EngineEvent.StepStarted(clock.nowMs(), CycleStep.FILE_FRAME))
        val filed = frameStore.fileFrame(media, frameIndex)
        events.emit(
            EngineEvent.StepFinished(
                atMs = clock.nowMs(),
                step = CycleStep.FILE_FRAME,
                ok = filed.ok,
                detail = filed.detail ?: filed.paths.joinToString(),
            )
        )
        if (!filed.ok) return failed(CycleStep.FILE_FRAME, filed.detail ?: "could not file frame")

        return CycleOutcome(captured = true, paths = filed.paths)
    }

    private suspend fun runStep(step: CycleStep, block: suspend () -> StepResult): StepResult {
        events.emit(EngineEvent.StepStarted(clock.nowMs(), step))
        val result = block()
        events.emit(EngineEvent.StepFinished(clock.nowMs(), step, result.ok, result.detail))
        return result
    }

    private fun skip(step: CycleStep, reason: String) {
        events.emit(EngineEvent.StepSkipped(clock.nowMs(), step, reason))
    }

    private fun failed(step: CycleStep, detail: String?) =
        CycleOutcome(captured = false, failedStep = step, detail = detail)

    private companion object {
        /** Long enough for a cold camera to draw its controls, short enough to give up cheaply. */
        const val MAX_SHUTTER_WAIT_MS = 6_000L
    }
}
