package org.peekit.opentimelapse.core.render

import kotlinx.serialization.Serializable

@Serializable
data class RenderSpec(
    val fps: Int = 30,
    /** Long edge of the output; frames are scaled down to fit. 0 keeps the source size. */
    val longEdgePx: Int = 3840,
    val encoder: Encoder = Encoder.HARDWARE,
    /** Used by [Encoder.HARDWARE], which has no CRF equivalent. */
    val bitrateMbps: Int = 60,
    /** Used by [Encoder.X264]. Lower is better quality. */
    val crf: Int = 18,
    val preset: String = "slow",
    /** Smooths the exposure flicker between frames that plagues automatic-exposure timelapses. */
    val deflicker: Boolean = false,
)

enum class Encoder {
    /** MediaCodec via ffmpeg: fast, stays cool, bitrate-controlled. */
    HARDWARE,

    /** libx264: matches a desktop command exactly, including -crf. Software, slow and hot. */
    X264,
}
