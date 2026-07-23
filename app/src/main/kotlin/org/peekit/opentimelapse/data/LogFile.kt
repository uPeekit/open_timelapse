package org.peekit.opentimelapse.data

import android.content.Context
import java.io.File

/**
 * The log, on disk. The in-memory [LogRepository] dies with the process and is wiped at the
 * start of each session; this survives both, so that after an unattended crash or a flat
 * battery there is still a record of what happened - including the crash itself.
 *
 * Each line is appended and flushed immediately (not buffered): a buffered tail would be the
 * first thing lost in the crash it most needs to capture. The volume is a few lines a second,
 * so the per-line open/close costs nothing that matters.
 *
 * Two files, rotated: when the current one passes [MAX_BYTES] it becomes the previous and a
 * fresh one starts, so history is bounded but a recent crash always has context before it.
 */
class LogFile(context: Context) {

    private val dir = File(context.getExternalFilesDir(null), "logs")
    private val current = File(dir, "session.log")
    private val previous = File(dir, "session-prev.log")

    @Synchronized
    fun append(line: String) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            if (current.length() > MAX_BYTES) {
                current.copyTo(previous, overwrite = true)
                current.writeText("")
            }
            current.appendText(line + "\n")
        }
    }

    /** The most recent lines, oldest first, for showing history when the app is reopened. */
    @Synchronized
    fun tail(maxLines: Int): List<String> =
        runCatching {
            (readLines(previous) + readLines(current)).takeLast(maxLines)
        }.getOrDefault(emptyList())

    /**
     * A single combined file for sharing off the device: previous then current, so a reader
     * sees the whole history in order. Written fresh each time it is requested.
     */
    @Synchronized
    fun exportFile(): File {
        val combined = File(dir, "opentimelapse-log.txt")
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            combined.writeText(readLines(previous).joinToString("\n") + "\n" + readLines(current).joinToString("\n"))
        }
        return combined
    }

    private fun readLines(file: File): List<String> =
        if (file.exists()) file.readLines() else emptyList()

    private companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
