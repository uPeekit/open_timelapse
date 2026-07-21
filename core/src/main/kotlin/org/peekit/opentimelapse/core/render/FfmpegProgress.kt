package org.peekit.opentimelapse.core.render

data class RenderProgress(
    val frame: Int = 0,
    val fps: Double = 0.0,
    val timeMs: Long = 0L,
    val speed: Double = 0.0,
    val finished: Boolean = false,
) {
    /** Null when the total is unknown; ffmpeg cannot know it for an image sequence. */
    fun percentOf(totalFrames: Int): Int? =
        if (totalFrames <= 0) null else ((frame * 100L) / totalFrames).toInt().coerceIn(0, 100)
}

/**
 * Parses ffmpeg's machine-readable progress stream (`-progress pipe:1`).
 *
 * That format is used rather than scraping the human status line, which is written with
 * carriage returns, no newlines, and a layout that changes between builds and encoders.
 * The progress stream is stable key=value text, one field per line, each block terminated
 * by `progress=continue` or `progress=end`.
 */
class FfmpegProgressParser {

    private var frame = 0
    private var fps = 0.0
    private var timeMs = 0L
    private var speed = 0.0

    /** Returns a snapshot only when a block ends, so callers update once per block. */
    fun onLine(rawLine: String): RenderProgress? {
        val line = rawLine.trim()
        val separator = line.indexOf('=')
        if (separator <= 0) return null

        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()

        when (key) {
            "frame" -> frame = value.toIntOrNull() ?: frame
            "fps" -> fps = value.toDoubleOrNull() ?: fps
            // Microseconds despite the name; older builds emit out_time_us instead.
            "out_time_ms", "out_time_us" -> timeMs = (value.toLongOrNull() ?: 0L) / 1000
            "speed" -> speed = value.removeSuffix("x").toDoubleOrNull() ?: speed
            "progress" -> return RenderProgress(frame, fps, timeMs, speed, finished = value == "end")
        }
        return null
    }
}
