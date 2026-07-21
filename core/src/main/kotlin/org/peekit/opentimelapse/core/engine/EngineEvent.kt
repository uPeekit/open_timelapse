package org.peekit.opentimelapse.core.engine

/** The steps of one cycle, in order. Named so the Debug log reads like the spec's example. */
enum class CycleStep {
    WAKE,
    UNLOCK,
    CAMERA_FOREGROUND,
    SHUTTER,
    CONFIRM_CAPTURE,
    FILE_FRAME,
    LOCK,
}

/** Everything the engine reports. The UI's live log and the session manifest are both built from these. */
sealed interface EngineEvent {
    val atMs: Long

    data class SessionStarted(
        override val atMs: Long,
        val sessionId: String,
        val intervalSeconds: Int,
    ) : EngineEvent

    data class StepStarted(
        override val atMs: Long,
        val step: CycleStep,
    ) : EngineEvent

    data class StepFinished(
        override val atMs: Long,
        val step: CycleStep,
        val ok: Boolean,
        val detail: String? = null,
    ) : EngineEvent

    /** Emitted instead of Started/Finished when a step's precondition was already satisfied. */
    data class StepSkipped(
        override val atMs: Long,
        val step: CycleStep,
        val reason: String,
    ) : EngineEvent

    data class FrameCaptured(
        override val atMs: Long,
        val index: Int,
        val paths: List<String>,
    ) : EngineEvent

    /** A cycle ran but produced no usable frame. The index is not consumed. */
    data class FrameFailed(
        override val atMs: Long,
        val failedStep: CycleStep?,
        val detail: String?,
    ) : EngineEvent

    /** A scheduled slot passed while the previous cycle was still running. */
    data class SlotSkipped(
        override val atMs: Long,
        val slotAtMs: Long,
    ) : EngineEvent

    data class Message(
        override val atMs: Long,
        val text: String,
    ) : EngineEvent

    data class SessionEnded(
        override val atMs: Long,
        val summary: SessionSummary,
    ) : EngineEvent
}

fun interface EventSink {
    fun emit(event: EngineEvent)
}

/** Discards everything. Useful in tests that assert on state rather than the log. */
object NoopEventSink : EventSink {
    override fun emit(event: EngineEvent) = Unit
}
