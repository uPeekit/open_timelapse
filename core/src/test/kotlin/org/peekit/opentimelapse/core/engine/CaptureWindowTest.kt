package org.peekit.opentimelapse.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureWindowTest {

    @Test
    fun `nothing observed yet leaves the configured window alone`() {
        assertEquals(30_000L, CaptureWindow.timeoutFor(configuredMs = 30_000, longestObservedMs = 0))
    }

    @Test
    fun `a window is never narrower than the one configured`() {
        assertEquals(30_000L, CaptureWindow.timeoutFor(configuredMs = 30_000, longestObservedMs = 1_000))
    }

    @Test
    fun `a capture slower than the configured window widens it`() {
        // The case this exists for: a sunset timelapse in auto mode, where exposures get
        // longer as the light goes. The window has to grow ahead of them or the last frames
        // of the shoot - the good ones - are the ones that fail.
        val widened = CaptureWindow.timeoutFor(configuredMs = 30_000, longestObservedMs = 20_000)

        assertTrue(widened >= 20_000 * 2, "needs real headroom over the observation: $widened")
    }

    @Test
    fun `growth is capped so a stuck capture cannot stall the session forever`() {
        val widened = CaptureWindow.timeoutFor(configuredMs = 30_000, longestObservedMs = 600_000)

        assertTrue(widened <= CaptureWindow.CEILING_MS, "got $widened")
    }

    @Test
    fun `a deliberately huge configured window is respected above the cap`() {
        // The cap governs what the app infers, never what the user asked for.
        assertEquals(
            300_000L,
            CaptureWindow.timeoutFor(configuredMs = 300_000, longestObservedMs = 1_000),
        )
    }
}
