package org.peekit.opentimelapse.core.render

import org.peekit.opentimelapse.core.model.SessionManifest

/**
 * Builds the ffmpeg invocation for a session. Pure, so the exact argv is unit-tested
 * rather than debugged on a phone.
 *
 * Two input styles, because a session's frames may or may not have been renamed:
 *
 *  - renamed frames are a numbered sequence, read with `-i prefix%08d.jpg`
 *  - untouched frames keep the camera's own names, read through a concat list
 *
 * The concat list is what makes renaming optional: it names exactly the files belonging to
 * the session, so a render never picks up unrelated photos sitting in the same folder.
 */
object FfmpegCommandBuilder {

    /**
     * Contents of the concat list file. ffmpeg requires forward slashes and escapes single
     * quotes as `'\''`.
     */
    fun concatList(framePaths: List<String>, fps: Int): String {
        val duration = 1.0 / fps.coerceAtLeast(1)
        // Locale.ROOT: a comma-decimal device locale would emit "0,033333", which the
        // concat demuxer rejects - every render failed on phones set to German or Russian.
        val durationLine = "duration %.6f".format(java.util.Locale.ROOT, duration)
        return buildString {
            appendLine("ffconcat version 1.0")
            framePaths.forEach { path ->
                appendLine("file '${escape(path)}'")
                appendLine(durationLine)
            }
            // The last frame needs repeating; without it ffmpeg drops it.
            framePaths.lastOrNull()?.let { appendLine("file '${escape(it)}'") }
        }
    }

    /** Argv for a session whose frames were renamed into a numbered sequence. */
    fun fromPattern(
        manifest: SessionManifest,
        spec: RenderSpec,
        outputPath: String,
        extension: String = "jpg",
    ): List<String> {
        val pattern = manifest.inputPattern(extension)
            ?: error("session ${manifest.id} has no numbered sequence")
        return buildList {
            add("-y")
            add("-framerate"); add(spec.fps.toString())
            add("-start_number"); add(manifest.firstIndex.toString())
            add("-i"); add("${manifest.folderPath}/$pattern")
            addAll(videoArguments(spec))
            add(outputPath)
        }
    }

    /** Argv for a session read through a concat list, whatever the frames are called. */
    fun fromConcatList(listPath: String, spec: RenderSpec, outputPath: String): List<String> =
        buildList {
            add("-y")
            add("-f"); add("concat")
            // The list holds absolute paths, which ffmpeg refuses without this.
            add("-safe"); add("0")
            add("-i"); add(listPath)
            addAll(videoArguments(spec))
            add(outputPath)
        }

    /**
     * Splits a hand-written command into argv.
     *
     * Handles quoted sections, because paths contain spaces and filter graphs contain
     * commas and colons that must survive as one argument. A leading "ffmpeg" is dropped so
     * a command copied from the app, or from a desktop, can be pasted straight back.
     */
    fun parseCommand(command: String): List<String> {
        val argv = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null

        for (character in command.trim()) {
            when {
                quote != null && character == quote -> quote = null
                quote != null -> current.append(character)
                character == '\'' || character == '"' -> quote = character
                character.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        argv += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) argv += current.toString()

        return argv.drop(if (argv.firstOrNull()?.endsWith("ffmpeg") == true) 1 else 0)
    }

    /**
     * Puts the real output path into a hand-edited command.
     *
     * The user never types the destination: a render has to land where the app can then
     * publish it, and an arbitrary path would be unreachable or unwritable. The last
     * argument is the output by ffmpeg's own convention.
     */
    fun withOutput(argv: List<String>, outputPath: String): List<String> =
        if (argv.isEmpty()) listOf(outputPath) else argv.dropLast(1) + outputPath

    /** The same command as a line the user can paste on a desktop. */
    fun asShellCommand(argv: List<String>): String =
        (listOf("ffmpeg") + argv).joinToString(" ") { token ->
            if (token.any { it.isWhitespace() }) "\"$token\"" else token
        }

    private fun videoArguments(spec: RenderSpec): List<String> = buildList {
        filterGraph(spec)?.let { add("-vf"); add(it) }

        when (spec.encoder) {
            Encoder.HARDWARE -> {
                add("-c:v"); add("h264_mediacodec")
                add("-b:v"); add("${spec.bitrateMbps}M")
            }

            Encoder.X264 -> {
                add("-c:v"); add("libx264")
                add("-crf"); add(spec.crf.toString())
                add("-preset"); add(spec.preset)
                // Memory ceiling, not a speed knob. Frame threading gives every thread its own
                // buffer of the frame, so at 4K a 4-thread encode peaked near 2GB and the
                // phone's low-memory killer took the render (silently - no ffmpeg error, just a
                // dead process). sliced-threads shares one frame across the threads instead, so
                // memory stops scaling with the thread count; rc-lookahead bounds the rest.
                add("-threads"); add(spec.threads.coerceAtLeast(1).toString())
                add("-x264-params")
                add("sliced-threads=1:rc-lookahead=${spec.lookahead.coerceAtLeast(1)}")
            }
        }

        add("-pix_fmt"); add("yuv420p")
        // Lets the result start playing before it has fully downloaded when shared.
        add("-movflags"); add("+faststart")
        add("-r"); add(spec.fps.toString())
    }

    private fun filterGraph(spec: RenderSpec): String? {
        val filters = mutableListOf<String>()
        if (spec.longEdgePx > 0) {
            // Fit the frame inside a longEdge x longEdge box: the *long* edge becomes longEdgePx,
            // scaling down only. The old `scale=W:-2` set the *width*, so a portrait frame's
            // short edge was blown up to 3840 - a 3840x5120 (20 MP) frame that peaked ffmpeg
            // near 2 GB and got the render killed. force_divisible_by=2 keeps H.264's even dims.
            filters += "scale=w=${spec.longEdgePx}:h=${spec.longEdgePx}:" +
                "force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos"
        }
        if (spec.deflicker) filters += "deflicker"
        return filters.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun escape(path: String): String = path.replace("'", "'\\''")
}
