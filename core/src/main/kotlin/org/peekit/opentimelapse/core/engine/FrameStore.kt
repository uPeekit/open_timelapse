package org.peekit.opentimelapse.core.engine

/**
 * Files the media a shutter press produced under the session's naming scheme.
 * All files from one press share [index] and keep their own extensions.
 */
interface FrameStore {
    suspend fun fileFrame(media: List<CapturedMedia>, index: Int): FrameFileResult
}
