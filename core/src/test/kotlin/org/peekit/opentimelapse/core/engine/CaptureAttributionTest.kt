package org.peekit.opentimelapse.core.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals

class CaptureAttributionTest {

    private val camera = "com.example.camera"

    private fun owned(name: String) = jpeg(name).copy(ownerPackage = camera)
    private fun foreign(name: String) = jpeg(name).copy(ownerPackage = "com.whatsapp")

    @Test
    fun `camera-owned files win over a foreign image that arrived in the same window`() {
        // The failure this prevents: a messenger auto-downloading a picture mid-cycle being
        // counted as the frame, renamed into the session, and later deleted with it.
        val frame = owned("cam.jpg")
        val stray = foreign("stray.jpg")

        assertContentEquals(
            listOf(frame),
            CaptureAttribution.preferOwnedBy(listOf(stray, frame), camera),
        )
    }

    @Test
    fun `falls back to everything when no file is attributed to the camera`() {
        // Some OEMs write rows without ownership; refusing them would fail every capture.
        val unattributed = jpeg("cam.jpg") // ownerPackage = null
        assertContentEquals(
            listOf(unattributed),
            CaptureAttribution.preferOwnedBy(listOf(unattributed), camera),
        )
    }

    @Test
    fun `a raw sibling owned by the camera is kept with its jpeg`() {
        val shot = listOf(owned("a.jpg"), dng("a.dng").copy(ownerPackage = camera))
        assertContentEquals(shot, CaptureAttribution.preferOwnedBy(shot + foreign("x.jpg"), camera))
    }

    @Test
    fun `a blank camera package filters nothing`() {
        val mixed = listOf(owned("a.jpg"), foreign("b.jpg"))
        assertContentEquals(mixed, CaptureAttribution.preferOwnedBy(mixed, ""))
    }
}
