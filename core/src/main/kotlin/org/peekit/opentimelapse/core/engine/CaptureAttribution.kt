package org.peekit.opentimelapse.core.engine

/**
 * Decides which of the files that appeared after a shutter press actually belong to it.
 *
 * The capture window is a time filter, and time alone misattributes: a messenger
 * auto-downloading a picture mid-cycle lands in the same window, gets counted as the
 * frame, renamed into the session - and deleted with it later. Ownership is the missing
 * signal.
 *
 * Prefer-then-fallback rather than a hard filter: some OEMs write rows without
 * OWNER_PACKAGE_NAME, and refusing those would fail every capture on such a device. So
 * camera-owned files win when any exist, and everything is kept otherwise - the old
 * behaviour, no worse.
 */
object CaptureAttribution {

    fun preferOwnedBy(media: List<CapturedMedia>, cameraPackage: String): List<CapturedMedia> {
        if (cameraPackage.isBlank()) return media
        val owned = media.filter { it.ownerPackage == cameraPackage }
        return owned.ifEmpty { media }
    }
}
