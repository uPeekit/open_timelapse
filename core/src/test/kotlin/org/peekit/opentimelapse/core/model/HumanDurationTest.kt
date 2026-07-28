package org.peekit.opentimelapse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class HumanDurationTest {

    @Test
    fun `seconds only under a minute`() {
        assertEquals("45s", humanDuration(45))
        assertEquals("0s", humanDuration(0))
    }

    @Test
    fun `minutes and seconds under an hour`() {
        assertEquals("5m 20s", humanDuration(320))
        assertEquals("1m 0s", humanDuration(60))
    }

    @Test
    fun `hours minutes seconds, the render-log case`() {
        // 8372s is the unreadable number that motivated this.
        assertEquals("2h 19m 32s", humanDuration(8372))
    }

    @Test
    fun `negative is clamped to zero`() {
        assertEquals("0s", humanDuration(-5))
    }
}
