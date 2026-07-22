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
    val preset: String = "slow",
    /** Smooths the exposure flicker between frames that plagues automatic-exposure timelapses. */
    val deflicker: Boolean = false,
)

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
