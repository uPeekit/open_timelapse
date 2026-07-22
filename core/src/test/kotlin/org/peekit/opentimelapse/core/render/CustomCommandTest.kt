package org.peekit.opentimelapse.core.render

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomCommandTest {

    @Test
    fun `splits a plain command`() {
        assertContentEquals(
            listOf("-y", "-framerate", "30", "-i", "in%08d.jpg", "out.mp4"),
            FfmpegCommandBuilder.parseCommand("-y -framerate 30 -i in%08d.jpg out.mp4"),
        )
    }

    @Test
    fun `drops a leading ffmpeg so a copied command pastes straight back`() {
        val argv = FfmpegCommandBuilder.parseCommand("ffmpeg -y -i in.jpg out.mp4")
        assertEquals("-y", argv.first())

        val fullPath = FfmpegCommandBuilder.parseCommand("/usr/bin/ffmpeg -y -i in.jpg out.mp4")
        assertEquals("-y", fullPath.first())
    }

    @Test
    fun `keeps a quoted path with spaces as one argument`() {
        val argv = FfmpegCommandBuilder.parseCommand("""-i "/sdcard/My Photos/a%03d.jpg" out.mp4""")
        assertContentEquals(listOf("-i", "/sdcard/My Photos/a%03d.jpg", "out.mp4"), argv)
    }

    @Test
    fun `keeps a filter graph intact`() {
        // Commas and colons must survive as a single argument or the graph is destroyed.
        val argv = FfmpegCommandBuilder.parseCommand("-vf scale=1920:-2,deflicker -c:v libx264")
        assertContains(argv, "scale=1920:-2,deflicker")
        assertContains(argv, "libx264")
    }

    @Test
    fun `handles single quotes too`() {
        val argv = FfmpegCommandBuilder.parseCommand("-vf 'drawtext=text=hello world' out.mp4")
        assertContains(argv, "drawtext=text=hello world")
    }

    @Test
    fun `tolerates ragged spacing and an empty command`() {
        assertContentEquals(listOf("-y", "out.mp4"), FfmpegCommandBuilder.parseCommand("  -y    out.mp4  "))
        assertTrue(FfmpegCommandBuilder.parseCommand("   ").isEmpty())
    }

    @Test
    fun `the output path is substituted, never taken from the user`() {
        // A hand-typed destination could be unwritable or somewhere the app cannot publish
        // from, so the app always decides where the file lands.
        val argv = FfmpegCommandBuilder.parseCommand("-y -i in%08d.jpg whatever-they-typed.mp4")

        val fixed = FfmpegCommandBuilder.withOutput(argv, "/sdcard/Movies/OpenTimelapse/real.mp4")

        assertEquals("/sdcard/Movies/OpenTimelapse/real.mp4", fixed.last())
        assertFalse(fixed.contains("whatever-they-typed.mp4"))
        assertContains(fixed, "in%08d.jpg")
    }

    @Test
    fun `substituting into an empty command still yields an output`() {
        assertContentEquals(listOf("/out.mp4"), FfmpegCommandBuilder.withOutput(emptyList(), "/out.mp4"))
    }

    @Test
    fun `a blank custom command means the generated one is used`() {
        assertFalse(RenderSpec().usesCustomCommand)
        assertFalse(RenderSpec(customCommand = "   ").usesCustomCommand)
        assertTrue(RenderSpec(customCommand = "-y -i a.jpg out.mp4").usesCustomCommand)
    }

    private fun assertContains(argv: List<String>, value: String) =
        assertTrue(argv.contains(value), "expected $value in $argv")
}
