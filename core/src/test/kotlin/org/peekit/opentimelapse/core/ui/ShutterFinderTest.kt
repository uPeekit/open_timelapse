package org.peekit.opentimelapse.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Precondition for all of this: the finder only ever runs against the camera app's own
 * window, because the cycle verifies the foreground package first. It is not trying to
 * tell a camera from a launcher.
 */
class ShutterFinderTest {

    private val portrait = ShutterFinder.forScreen(1080, 2400)
    private val landscape = ShutterFinder.forScreen(2400, 1080)

    private fun node(
        left: Int, top: Int, right: Int, bottom: Int,
        viewId: String? = null,
        description: String? = null,
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
    ) = UiNode(
        bounds = NodeBounds(left, top, right, bottom),
        viewId = viewId,
        contentDescription = description,
        clickable = clickable,
        enabled = enabled,
        visible = visible,
    )

    private fun previewSurface() = node(0, 0, 1080, 1920, clickable = false)

    @Test
    fun `picks the shutter out of a typical camera bottom bar`() {
        val shutter = node(460, 2070, 620, 2230, viewId = "com.sec.android.app.camera:id/shutter_button")
        val thumbnail = node(60, 2090, 220, 2250, viewId = "com.sec.android.app.camera:id/thumbnail")
        val switchLens = node(860, 2090, 1020, 2250, description = "Switch camera")

        val best = portrait.best(listOf(previewSurface(), thumbnail, switchLens, shutter))

        assertEquals(shutter, best?.node)
    }

    @Test
    fun `works the same on a different manufacturer's ids`() {
        val shutter = node(450, 2060, 630, 2240, viewId = "com.google.android.GoogleCamera:id/shutter_button")
        val modes = node(100, 1900, 980, 1990, description = "Camera modes")

        assertEquals(shutter, portrait.best(listOf(previewSurface(), modes, shutter))?.node)
    }

    @Test
    fun `finds an unlabelled shutter in a language we cannot read`() {
        // No resource id, and a description no English pattern will match. Geometry alone
        // has to carry this - which is the whole point of being device-agnostic.
        val shutter = node(450, 2050, 630, 2230, description = "Затвор")
        val settings = node(40, 80, 160, 200, description = "Настройки")

        val best = portrait.best(listOf(previewSurface(), settings, shutter))

        assertEquals(shutter, best?.node)
        assertTrue(best!!.score >= ShutterFinder.MIN_SCORE)
    }

    @Test
    fun `finds the shutter on the right hand side in landscape`() {
        val shutter = node(2150, 460, 2310, 620)
        val thumbnail = node(2160, 100, 2300, 240)

        assertEquals(shutter, landscape.best(listOf(thumbnail, shutter))?.node)
    }

    @Test
    fun `never selects the preview surface`() {
        // Some camera apps mark the preview clickable for tap-to-focus.
        val preview = node(0, 0, 1080, 1920, clickable = true)

        assertNull(portrait.best(listOf(preview)))
    }

    @Test
    fun `ignores nodes that cannot be clicked`() {
        val decoration = node(450, 2050, 630, 2230, viewId = "x:id/shutter_ring", clickable = false)

        assertNull(portrait.best(listOf(decoration)))
    }

    @Test
    fun `ignores disabled and invisible nodes`() {
        val disabled = node(450, 2050, 630, 2230, viewId = "x:id/shutter_button", enabled = false)
        val invisible = node(450, 2050, 630, 2230, viewId = "x:id/shutter_button", visible = false)

        assertNull(portrait.best(listOf(disabled, invisible)))
    }

    @Test
    fun `returns nothing when no candidate is convincing`() {
        val topBar = node(40, 80, 160, 200, description = "Settings")
        val flash = node(200, 80, 320, 200, description = "Flash")

        assertNull(portrait.best(listOf(previewSurface(), topBar, flash)))
    }

    @Test
    fun `ranks candidates and explains why, for the calibration picker`() {
        val shutter = node(460, 2070, 620, 2230, viewId = "x:id/shutter_button", description = "Shutter")
        val thumbnail = node(60, 2090, 220, 2250)

        val ranked = portrait.rank(listOf(previewSurface(), thumbnail, shutter))

        assertEquals(shutter, ranked.first().node)
        assertTrue(ranked.first().score > ranked[1].score)
        assertTrue(ranked.first().reasons.isNotEmpty())
        assertTrue(ranked.none { it.node.bounds.area > 1080L * 2400 * 0.25 })
    }

    @Test
    fun `never clicks a consent dialog's full-width button`() {
        // Real geometry from ColorOS's "Statement of Use" dialog on a 720x1612 device.
        // It scored 0.424 against a 0.45 threshold on position and size alone - far too
        // close to auto-accepting a legal agreement on the user's behalf.
        val small = ShutterFinder.forScreen(720, 1612)
        val agreeAndContinue = UiNode(
            bounds = NodeBounds(140, 1374, 580, 1462),
            viewId = "com.oplus.camera:id/btn_confirm",
            clickable = true,
        )
        val exit = UiNode(
            bounds = NodeBounds(312, 1486, 407, 1540),
            viewId = "com.oplus.camera:id/txt_exit",
            clickable = true,
        )

        assertNull(small.best(listOf(agreeAndContinue, exit)))
        assertTrue(small.rank(listOf(agreeAndContinue)).isEmpty(), "a 5:1 bar is not a shutter")
    }

    @Test
    fun `finds real shutters measured on three different phones`() {
        // Regression corpus: if a change to the scoring breaks any of these, it breaks a
        // phone we have actually verified.
        val oneplus = ShutterFinder.forScreen(1080, 2400).best(
            listOf(node(423, 1944, 657, 2178, viewId = "com.oplus.camera:id/shutter_button", description = "Shutter"))
        )
        val samsung = ShutterFinder.forScreen(1080, 2400).best(
            // Note the view id matches no shutter pattern - description and shape carry it.
            listOf(node(396, 1896, 684, 2184, viewId = "com.sec.android.app.camera:id/center_button_container", description = "Take picture"))
        )

        assertTrue(oneplus != null && oneplus.score > 0.9, "OnePlus shutter: ${oneplus?.score}")
        assertTrue(samsung != null && samsung.score > 0.6, "Samsung shutter: ${samsung?.score}")
    }

    @Test
    fun `recognises a video control by the description that changes with camera mode`() {
        // Measured on ColorOS: the very same node, same id and bounds, is described
        // differently per mode. Pressing it in video mode records instead of shooting.
        val videoMode = UiNode(
            bounds = NodeBounds(282, 1280, 438, 1436),
            viewId = "com.oplus.camera:id/shutter_button",
            contentDescription = "Video Recording Button",
            clickable = true,
        )
        val photoMode = videoMode.copy(contentDescription = "\"Shutter\" button")

        assertTrue(ShutterFinder.isVideoControl(videoMode))
        assertFalse(ShutterFinder.isVideoControl(photoMode))
        assertFalse(ShutterFinder.isVideoControl(photoMode.copy(contentDescription = "Take picture")))
        assertTrue(ShutterFinder.isVideoControl(photoMode.copy(viewId = "x:id/record_button", contentDescription = null)))
    }

    @Test
    fun `a centred bottom button beats an off-centre one of the same size`() {
        val centred = node(460, 2070, 620, 2230)
        val offCentre = node(60, 2070, 220, 2230)

        val ranked = portrait.rank(listOf(centred, offCentre))

        assertEquals(centred, ranked.first().node)
    }
}
