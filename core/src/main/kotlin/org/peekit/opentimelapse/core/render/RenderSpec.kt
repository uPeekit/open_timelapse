package org.peekit.opentimelapse.core.render

import kotlinx.serialization.Serializable

@Serializable
data class RenderSpec(
    val fps: Int = 30,
    /** Long edge of the output; frames are scaled down to fit. 0 keeps the source size. */
    val longEdgePx: Int = 3840,
    val encoder: Encoder = Encoder.X264,
    /** Used by [Encoder.HARDWARE], which has no CRF equivalent. */
    val bitrateMbps: Int = 60,
    /** Used by [Encoder.X264]. Lower is better quality. */
    val crf: Int = 18,
    /**
     * "slow" was the default and cost over a gigabyte: it keeps a ~50 frame lookahead, and
     * at 1920x2560 each buffered frame is ~7MB, multiplied again by x264's thread count.
     * Samsung's low-memory killer terminated the app mid-render. "medium" is a fraction of
     * that for a barely visible quality difference on a timelapse.
     */
    val preset: String = "medium",
    /**
     * x264 defaults to about 1.5 threads per core, each holding its own frame buffers.
     * Capping it trades a little speed for a memory ceiling the phone can survive.
     */
    val threads: Int = 4,
    /** Frames x264 buffers for rate control. The single biggest memory lever. */
    val lookahead: Int = 12,
    /** Smooths the exposure flicker between frames that plagues automatic-exposure timelapses. */
    val deflicker: Boolean = false,
    /**
     * A hand-edited command, used verbatim instead of the generated one.
     *
     * Bundling ffmpeg was a bet on flexibility, and every other field here is a guess at
     * what someone might want. This is the escape hatch: two-pass, a crop, an unusual
     * filter chain, a codec nobody anticipated. Blank means the generated command is used,
     * so the fields above keep working until someone deliberately takes over.
     */
    val customCommand: String = "",
) {
    val usesCustomCommand: Boolean get() = customCommand.isNotBlank()
}

enum class Encoder {
    /**
     * MediaCodec via ffmpeg - **does not work from the bundled binary**.
     *
     * ffmpeg reaches MediaCodec through JNI, which needs a JavaVM handed to it by the
     * hosting app. A standalone executable launched with ProcessBuilder has no JVM, and
     * the encoder dies with "stack corruption detected" rather than failing cleanly.
     * Measured on a Galaxy S20.
     *
     * Kept only so the command builder stays testable for both forms; hardware encoding
     * would need Android's MediaCodec API driven from Kotlin, not ffmpeg.
     */
    HARDWARE,

    /**
     * libx264: the only encoder that actually works here. Software, so it is slow -
     * roughly 6.5s per 1920x2560 frame on a Galaxy S20 - but it supports -crf and matches
     * a desktop ffmpeg command exactly.
     */
    X264,
}
