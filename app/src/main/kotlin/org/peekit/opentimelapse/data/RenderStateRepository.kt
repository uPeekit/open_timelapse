package org.peekit.opentimelapse.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RenderPhase { IDLE, RUNNING, SUCCESS, FAILED }

/**
 * The live render, published so the session list can show progress under the row being
 * rendered - not only in the notification shade - and so the app can open the finished video
 * when asked.
 */
data class RenderState(
    val sessionId: String? = null,
    val phase: RenderPhase = RenderPhase.IDLE,
    /** Null early on, before ffmpeg has emitted enough to estimate against the frame count. */
    val percent: Int? = null,
    val frame: Int = 0,
    /** A viewable content URI for the finished video, for "open when rendered". */
    val outputUri: String? = null,
    val message: String? = null,
    /**
     * Bumped once per finished render. A one-shot side effect (opening the video) keys off
     * this so it fires exactly once per completion, not on every recomposition.
     */
    val completionId: Long = 0,
)

class RenderStateRepository {

    private val _state = MutableStateFlow(RenderState())
    val state: StateFlow<RenderState> = _state.asStateFlow()

    fun starting(sessionId: String) {
        _state.value = _state.value.copy(
            sessionId = sessionId,
            phase = RenderPhase.RUNNING,
            percent = 0,
            frame = 0,
            outputUri = null,
            message = null,
        )
    }

    fun progress(percent: Int?, frame: Int) {
        _state.value = _state.value.copy(percent = percent, frame = frame)
    }

    fun success(outputUri: String?, message: String) {
        _state.value = _state.value.copy(
            phase = RenderPhase.SUCCESS,
            percent = 100,
            outputUri = outputUri,
            message = message,
            completionId = _state.value.completionId + 1,
        )
    }

    fun failed(message: String) {
        _state.value = _state.value.copy(
            phase = RenderPhase.FAILED,
            message = message,
            completionId = _state.value.completionId + 1,
        )
    }
}
