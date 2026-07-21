package org.peekit.opentimelapse.core.model

import kotlinx.serialization.Serializable

/**
 * What the app shot, written to the session folder so a session survives a crash or a
 * flat battery and stays renderable. This - not a gallery heuristic - is how sessions
 * are discovered.
 */
@Serializable
data class SessionManifest(
    val id: String,
    val name: String,
    /** Absolute path of the session folder, or the camera's own folder when naming is off. */
    val folderPath: String,
    val cameraPackage: String,
    val intervalSeconds: Int,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val naming: NamingConfig = NamingConfig(),
    val frameCount: Int = 0,
    val firstIndex: Int = 0,
    val lastIndex: Int = 0,
) {
    /**
     * The ffmpeg input pattern for this session, e.g. `timelapse%08d.jpg`.
     * Null when frames kept their original camera filenames - there is no pattern then.
     */
    fun inputPattern(extension: String = "jpg"): String? =
        if (naming.enabled) "${naming.prefix}%0${naming.padWidth}d.$extension" else null

    val isRenderable: Boolean get() = naming.enabled && frameCount > 0
}
