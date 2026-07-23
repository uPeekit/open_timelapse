package org.peekit.opentimelapse.core.engine

enum class StopReason {
    MANUAL,
    DURATION_REACHED,
    END_TIME_REACHED,
    /** Refused before shooting anything - see [StartRejection]. */
    REJECTED,

    /** Stopped mid-session by a fault that retrying cannot fix. */
    ABORTED,

    /** Stopped to leave the phone with some charge rather than shooting until it died. */
    BATTERY_LOW,
}

/** Why a session could not start. Surfaced in the UI with a fix action, not just logged. */
enum class StartRejection {
    /** Lock screen requires a PIN/pattern/password; a swipe cannot get past it. */
    DEVICE_SECURE_LOCK,
}

data class SessionSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val cyclesRun: Int,
    val framesCaptured: Int,
    val framesFailed: Int,
    val slotsSkipped: Int,
    val firstIndex: Int,
    val lastIndex: Int,
    val stopReason: StopReason,
    val rejection: StartRejection? = null,
) {
    val durationMs: Long get() = endedAtMs - startedAtMs
}
