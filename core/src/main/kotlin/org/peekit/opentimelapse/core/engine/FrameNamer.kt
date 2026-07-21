package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.NamingConfig

/**
 * Builds frame filenames. Pure - no filesystem, no MediaStore.
 *
 * The numbering must be contiguous: ffmpeg's image2 demuxer stops at the first gap in a
 * `%08d` sequence, so a render would silently truncate. Keeping the index contiguous is
 * the engine's job (it only advances the index on a confirmed, filed frame); this class
 * just formats it.
 */
class FrameNamer(private val config: NamingConfig) {

    fun baseName(index: Int): String =
        config.prefix + index.toString().padStart(config.padWidth, '0')

    /**
     * Name for one captured file at [index], preserving its type.
     *
     * The extension is lower-cased: the ffmpeg input pattern hard-codes one spelling, so
     * `.JPG` and `.jpg` in the same folder would break the sequence.
     */
    fun fileName(index: Int, originalName: String): String {
        val extension = originalName.substringAfterLast('.', "")
        return if (extension.isEmpty()) {
            baseName(index)
        } else {
            "${baseName(index)}.${extension.lowercase()}"
        }
    }

    /** Distinct lower-cased extensions in a set of captured files, e.g. `[jpg, dng]`. */
    fun extensionsOf(media: List<CapturedMedia>): List<String> =
        media.mapNotNull { it.displayName.substringAfterLast('.', "").takeIf(String::isNotEmpty) }
            .map(String::lowercase)
            .distinct()
}
