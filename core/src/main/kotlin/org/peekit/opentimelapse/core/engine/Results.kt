package org.peekit.opentimelapse.core.engine

/** Outcome of a single device action. Every actuator call reports one - nothing is fire-and-forget. */
data class StepResult(
    val ok: Boolean,
    val detail: String? = null,
) {
    fun describe(): String = detail ?: if (ok) "ok" else "failure"

    companion object {
        val Ok = StepResult(true)
        fun ok(detail: String) = StepResult(true, detail)
        fun fail(detail: String) = StepResult(false, detail)
    }
}

/** Which lookup actually found the shutter, so the log can tell the user their config is stale. */
enum class ShutterStrategy { VIEW_ID, CONTENT_DESCRIPTION, COORDINATES, NONE }

data class ShutterResult(
    val ok: Boolean,
    val strategy: ShutterStrategy,
    val detail: String? = null,
    /**
     * The resolved control starts a video recording rather than taking a still. The
     * actuator must report this *instead of* clicking - see [org.peekit.opentimelapse.core.ui.ShutterFinder.isVideoControl].
     */
    val videoControlDetected: Boolean = false,
)

/** A file the camera app produced. One shutter press yields several of these in RAW mode. */
data class CapturedMedia(
    val uri: String,
    /**
     * Absolute filesystem path, when MediaStore exposes one. Needed because a content://
     * URI is useless to ffmpeg - a rendered session must reference real files.
     */
    val path: String? = null,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAtMs: Long,
)

data class FrameFileResult(
    val ok: Boolean,
    /** Final paths after move + rename. */
    val paths: List<String> = emptyList(),
    val detail: String? = null,
)
