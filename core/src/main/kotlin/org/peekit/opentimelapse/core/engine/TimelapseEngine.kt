package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.CycleMode
import org.peekit.opentimelapse.core.model.EndMode
import org.peekit.opentimelapse.core.model.TimelapseConfig

/**
 * Read once per cycle, so edits in Settings take effect on the *next* frame and never
 * mutate a cycle that is already running.
 */
fun interface ConfigSource {
    fun current(): TimelapseConfig
}

/**
 * Owns the session: scheduling, drift correction, stop conditions and frame numbering.
 * Knows nothing about Android - see [DeviceActuator], [FrameStore], [Clock], [Waiter].
 */
class TimelapseEngine(
    private val cycleRunner: CycleExecutor,
    private val actuator: DeviceActuator,
    private val clock: Clock,
    private val waiter: Waiter,
    private val events: EventSink,
) {

    @Volatile
    private var stopRequested = false

    /** Graceful stop: the current cycle finishes, then the session ends. */
    fun requestStop() {
        stopRequested = true
    }

    /**
     * Checked before shooting anything. A swipe cannot pass a PIN/pattern/password, so
     * LOCK_CYCLE on a secured device would fail every single frame.
     */
    suspend fun preflight(config: TimelapseConfig): StartRejection? =
        if (config.mode == CycleMode.LOCK_CYCLE && actuator.isDeviceSecure()) {
            StartRejection.DEVICE_SECURE_LOCK
        } else {
            null
        }

    /** One cycle, for the Debug screen. */
    suspend fun runSingleCycle(config: TimelapseConfig, frameIndex: Int = 1): CycleOutcome =
        cycleRunner.run(config.normalized(), frameIndex)

    suspend fun runSession(sessionId: String, configSource: ConfigSource): SessionSummary {
        stopRequested = false

        val initial = configSource.current().normalized()
        val startedAtMs = clock.nowMs()
        val firstIndex = initial.naming.startIndex

        preflight(initial)?.let { rejection ->
            return finish(
                startedAtMs = startedAtMs,
                cycles = 0, captured = 0, failed = 0, skipped = 0,
                firstIndex = firstIndex, nextIndex = firstIndex,
                reason = StopReason.REJECTED, rejection = rejection,
            )
        }

        events.emit(EngineEvent.SessionStarted(startedAtMs, sessionId, initial.intervalSeconds))

        var slot = 0L
        var index = firstIndex
        var cycles = 0
        var captured = 0
        var failed = 0
        var skipped = 0
        var reason = StopReason.MANUAL

        while (!stopRequested) {
            val config = configSource.current().normalized()

            // Slots are absolute: start + n * interval. Never cumulative, so a slow cycle
            // cannot make every later frame drift later.
            val slotAtMs = startedAtMs + slot * config.intervalMs

            stopReasonFor(config, startedAtMs, slotAtMs)?.let {
                reason = it
                break
            }

            // Checked before shooting, not after: the point is to leave charge in the
            // phone, and a frame taken at the floor defeats that.
            val floor = config.session.stopBelowBatteryPercent
            if (floor > 0) {
                val battery = actuator.battery()
                if (!battery.charging && battery.percent <= floor) {
                    events.emit(
                        EngineEvent.Message(
                            clock.nowMs(),
                            "Stopping at ${battery.percent}% to leave the phone some charge",
                        )
                    )
                    reason = StopReason.BATTERY_LOW
                    break
                }
            }

            if (clock.nowMs() < slotAtMs) waiter.awaitUntil(slotAtMs)
            if (stopRequested) break

            val outcome = cycleRunner.run(config, index)
            cycles++

            if (outcome.fatal) {
                events.emit(EngineEvent.FrameFailed(clock.nowMs(), outcome.failedStep, outcome.detail))
                failed++
                reason = StopReason.ABORTED
                break
            }

            if (outcome.captured) {
                events.emit(EngineEvent.FrameCaptured(clock.nowMs(), index, outcome.paths))
                index++
                captured++
            } else {
                // The index is deliberately not consumed: a gap would truncate an
                // ffmpeg %0Nd render at the missing number.
                events.emit(EngineEvent.FrameFailed(clock.nowMs(), outcome.failedStep, outcome.detail))
                failed++
            }

            slot++
            val now = clock.nowMs()
            while (startedAtMs + slot * config.intervalMs <= now) {
                events.emit(EngineEvent.SlotSkipped(now, startedAtMs + slot * config.intervalMs))
                slot++
                skipped++
            }
        }

        return finish(
            startedAtMs = startedAtMs,
            cycles = cycles, captured = captured, failed = failed, skipped = skipped,
            firstIndex = firstIndex, nextIndex = index,
            reason = reason, rejection = null,
        )
    }

    private fun stopReasonFor(
        config: TimelapseConfig,
        startedAtMs: Long,
        slotAtMs: Long,
    ): StopReason? = when (config.session.endMode) {
        EndMode.MANUAL -> null

        EndMode.AFTER_DURATION ->
            if (slotAtMs >= startedAtMs + config.session.durationMinutes * 60_000L) {
                StopReason.DURATION_REACHED
            } else {
                null
            }

        EndMode.AT_TIME ->
            if (slotAtMs >= config.session.endAtEpochMs) StopReason.END_TIME_REACHED else null
    }

    private fun finish(
        startedAtMs: Long,
        cycles: Int,
        captured: Int,
        failed: Int,
        skipped: Int,
        firstIndex: Int,
        nextIndex: Int,
        reason: StopReason,
        rejection: StartRejection?,
    ): SessionSummary {
        val endedAtMs = clock.nowMs()
        val summary = SessionSummary(
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            cyclesRun = cycles,
            framesCaptured = captured,
            framesFailed = failed,
            slotsSkipped = skipped,
            firstIndex = firstIndex,
            lastIndex = nextIndex - 1,
            stopReason = reason,
            rejection = rejection,
        )
        events.emit(EngineEvent.SessionEnded(endedAtMs, summary))
        return summary
    }
}
