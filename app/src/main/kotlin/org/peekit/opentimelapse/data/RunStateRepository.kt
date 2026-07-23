package org.peekit.opentimelapse.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.peekit.opentimelapse.core.net.ServerState

/**
 * The live session, published for anything outside the service that needs to read it - the
 * control server's `/status`, and later the UI.
 *
 * The service already knew all of this, but only inside itself, pushed out through the
 * notification text. A second reader (a laptop over the LAN) needs it as data, so it lives
 * here as one small immutable snapshot updated in place.
 */
data class RunState(
    val serverState: ServerState = ServerState.IDLE,
    val sessionName: String? = null,
    val framesCaptured: Int = 0,
    val intervalSeconds: Int = 0,
    val startedAtMs: Long = 0,
    /** Absolute time the next frame is scheduled for; 0 when idle. */
    val nextFrameAtMs: Long = 0,
    /** The most recent confirmed frame's file path, for the preview endpoint. */
    val lastFramePath: String? = null,
)

class RunStateRepository {

    private val _state = MutableStateFlow(RunState())

    val state: StateFlow<RunState> = _state.asStateFlow()

    fun update(transform: (RunState) -> RunState) {
        _state.value = transform(_state.value)
    }

    /** Back to idle when a session ends, keeping nothing that only made sense while running. */
    fun reset() {
        _state.value = RunState()
    }
}
