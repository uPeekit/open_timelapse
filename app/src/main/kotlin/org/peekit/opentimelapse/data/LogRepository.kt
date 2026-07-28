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
    /** True for lines read back from the persisted file, which are already formatted. */
    val preformatted: Boolean = false,
) {
    fun format(): String {
        if (preformatted) return text
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
 *
 * The in-memory buffer is the live view; [file] is the durable record that outlives the
 * process, so a crash or a flat battery still leaves something to read afterwards.
 */
class LogRepository(private val file: LogFile? = null) : EventSink {

    // Seeded from the persisted tail so reopening after a crash shows what happened, rather
    // than an empty log.
    private val entries = MutableStateFlow(
        file?.tail(MAX_ENTRIES).orEmpty().map { LogEntry(0L, it, preformatted = true) },
    )

    val log: StateFlow<List<LogEntry>> = entries.asStateFlow()

    private var frames = 0

    /**
     * Only failures and unexpected events are logged; a healthy cycle is silent apart from a
     * heartbeat every [FRAME_HEARTBEAT] frames. A line per step per frame was thousands of
     * "OK WAKE / OK SHUTTER" over a long run - pure noise that buried the one line that
     * mattered and rotated the persisted log's useful history out of the file.
     */
    override fun emit(event: EngineEvent) {
        when (event) {
            is EngineEvent.StepStarted -> return                       // "WAKE..." progress
            is EngineEvent.StepSkipped -> return                       // normal: already satisfied
            is EngineEvent.StepFinished -> if (event.ok) return        // keep only failed steps
            is EngineEvent.FrameCaptured -> {
                frames++
                if (frames % FRAME_HEARTBEAT != 0) return
                append(LogEntry(event.atMs, "$frames frames captured", ok = true))
                return
            }
            else -> Unit                                              // failures, slots, messages, session
        }
        append(LogEntry(event.atMs, describe(event), okOf(event)))
    }

    fun message(text: String) {
        append(LogEntry(System.currentTimeMillis(), text))
    }

    /**
     * Clears the live view at the start of a session, but writes a boundary to the file
     * rather than erasing it - the point of the file is to survive across sessions.
     */
    fun clear() {
        entries.value = emptyList()
        frames = 0
        file?.append("──────── new session ────────")
    }

    /** The whole log as a single file, for sharing off the device. */
    fun exportFile(): java.io.File? = file?.exportFile()

    private fun append(entry: LogEntry) {
        entries.value = (entries.value + entry).takeLast(MAX_ENTRIES)
        val line = entry.format()
        // Mirrored to logcat so a running session can be watched over adb without the UI.
        Logcat.i(line)
        // And to disk, flushed immediately, so the record survives the process.
        file?.append(line)
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
        const val FRAME_HEARTBEAT = 25
    }
}
