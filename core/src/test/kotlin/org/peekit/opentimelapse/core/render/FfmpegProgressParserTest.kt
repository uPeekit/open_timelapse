package org.peekit.opentimelapse.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegProgressParserTest {

    private val parser = FfmpegProgressParser()

    private fun feed(block: String): RenderProgress? =
        block.trimIndent().lines().firstNotNullOfOrNull { parser.onLine(it) }

    @Test
    fun `emits nothing until a block terminates`() {
        assertNull(parser.onLine("frame=12"))
        assertNull(parser.onLine("fps=30.00"))
        assertNull(parser.onLine("speed=1.5x"))
    }

    @Test
    fun `reads a real progress block`() {
        val progress = feed(
            """
            frame=120
            fps=48.30
            stream_0_0_q=23.0
            bitrate=2048.1kbits/s
            out_time_ms=4000000
            speed=1.61x
            progress=continue
            """
        )

        assertEquals(120, progress?.frame)
        assertEquals(48.30, progress?.fps)
        assertEquals(4_000L, progress?.timeMs, "out_time_ms is microseconds despite its name")
        assertEquals(1.61, progress?.speed)
        assertFalse(progress!!.finished)
    }

    @Test
    fun `recognises the end of the render`() {
        val progress = feed(
            """
            frame=500
            out_time_ms=16666000
            progress=end
            """
        )
        assertTrue(progress!!.finished)
        assertEquals(500, progress.frame)
    }

    @Test
    fun `carries values forward when a later block omits them`() {
        feed(
            """
            frame=100
            fps=30.0
            progress=continue
            """
        )
        val second = feed(
            """
            frame=200
            progress=continue
            """
        )

        assertEquals(200, second?.frame)
        assertEquals(30.0, second?.fps, "fps was not repeated but should not reset to zero")
    }

    @Test
    fun `tolerates the older out_time_us field name`() {
        val progress = feed(
            """
            out_time_us=2500000
            progress=continue
            """
        )
        assertEquals(2_500L, progress?.timeMs)
    }

    @Test
    fun `ignores noise and malformed lines`() {
        assertNull(parser.onLine(""))
        assertNull(parser.onLine("=novalue"))
        assertNull(parser.onLine("Press [q] to stop"))
        assertNull(parser.onLine("frame"))
    }

    @Test
    fun `percentage needs a known total`() {
        val progress = RenderProgress(frame = 50)
        assertEquals(50, progress.percentOf(100))
        assertNull(progress.percentOf(0), "ffmpeg cannot know the total for an image sequence")
        assertEquals(100, progress.percentOf(10), "must not exceed 100 when ffmpeg overruns")
    }
}
