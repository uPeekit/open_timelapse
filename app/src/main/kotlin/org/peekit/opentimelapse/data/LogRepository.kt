package org.peekit.opentimelapse.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.peekit.opentimelapse.core.engine.EngineEvent
import org.peekit.opentimelapse.core.engine.EventSink
import org.peekit.opentimelapse.Logcat

data class LogEntry(
    val atMs: Long,
    val text: String,
    val ok: Boolean? = null,
) {
    fun format(): String {
        val mark = when (ok) {
            true -> "OK  "
            false -> "FAIL"
            null -> "    "
        }
        return "[${TIME.format(Date(atMs))}] $mark $text"
    }

    private companion object {
        val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Renders engine events into a readable log and keeps the recent tail.
 *
 * Bounded on purpose: an overnight session at 10s intervals produces thousands of events,
 * and the Debug screen only ever shows the end of it.
 */
class LogRepository : EventSink {

    private val entries = MutableStateFlow<List<LogEntry>>(emptyList())

    val log: StateFlow<List<LogEntry>> = entries.asStateFlow()

    override fun emit(event: EngineEvent) {
        append(LogEntry(event.atMs, describe(event), okOf(event)))
    }

    fun message(text: String) {
        append(LogEntry(System.currentTimeMillis(), text))
    }

    fun clear() {
        entries.value = emptyList()
    }

    private fun append(entry: LogEntry) {
        entries.value = (entries.value + entry).takeLast(MAX_ENTRIES)
        // Mirrored to logcat so a running session can be watched over adb without the UI.
        Logcat.i(entry.format())
    }

    private fun okOf(event: EngineEvent): Boolean? = when (event) {
        is EngineEvent.StepFinished -> event.ok
        is EngineEvent.FrameCaptured -> true
        is EngineEvent.FrameFailed -> false
        else -> null
    }

    private fun describe(event: EngineEvent): String = when (event) {
        is EngineEvent.SessionStarted ->
            "Session ${event.sessionId} started, every ${event.intervalSeconds}s"

        is EngineEvent.StepStarted -> "${event.step}..."

        is EngineEvent.StepFinished ->
            "${event.step}" + (event.detail?.let { ": $it" } ?: "")

        is EngineEvent.StepSkipped -> "${event.step} skipped (${event.reason})"

        is EngineEvent.FrameCaptured ->
            "Frame ${event.index} captured" +
                (event.paths.takeIf { it.isNotEmpty() }?.let { " -> ${it.joinToString()}" } ?: "")

        is EngineEvent.FrameFailed ->
            "Frame dropped at ${event.failedStep ?: "unknown step"}" +
                (event.detail?.let { ": $it" } ?: "")

        is EngineEvent.SlotSkipped -> "Slot missed - the previous cycle overran"

        is EngineEvent.Message -> event.text

        is EngineEvent.SessionEnded -> with(event.summary) {
            "Session ended (${stopReason}): $framesCaptured captured, " +
                "$framesFailed failed, $slotsSkipped slots missed"
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
