package org.peekit.opentimelapse.core.render

import org.peekit.opentimelapse.core.model.NamingConfig
import org.peekit.opentimelapse.core.model.SessionManifest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegCommandBuilderTest {

    private val renamed = SessionManifest(
        id = "s1",
        name = "sunset",
        folderPath = "/sdcard/DCIM/OpenTimelapse/sunset",
        cameraPackage = "com.oplus.camera",
        intervalSeconds = 30,
        startedAtMs = 0,
        naming = NamingConfig(enabled = true, prefix = "sunset", padWidth = 8, startIndex = 1),
        frameCount = 120,
        firstIndex = 1,
        lastIndex = 120,
    )

    @Test
    fun `numbered sequences are read with a printf pattern`() {
        val argv = FfmpegCommandBuilder.fromPattern(renamed, RenderSpec(), "/out.mp4")

        assertContains(argv, "/sdcard/DCIM/OpenTimelapse/sunset/sunset%08d.jpg")
        assertEquals("30", argv[argv.indexOf("-framerate") + 1])
        assertEquals("1", argv[argv.indexOf("-start_number") + 1])
    }

    @Test
    fun `a non-default start index is carried through`() {
        val argv = FfmpegCommandBuilder.fromPattern(
            renamed.copy(naming = renamed.naming.copy(startIndex = 500), firstIndex = 500),
            RenderSpec(),
            "/out.mp4",
        )
        assertEquals("500", argv[argv.indexOf("-start_number") + 1])
    }

    @Test
    fun `hardware encoding uses a bitrate because it has no CRF`() {
        val argv = FfmpegCommandBuilder.fromPattern(
            renamed,
            RenderSpec(encoder = Encoder.HARDWARE, bitrateMbps = 60),
            "/out.mp4",
        )

        assertEquals("h264_mediacodec", argv[argv.indexOf("-c:v") + 1])
        assertEquals("60M", argv[argv.indexOf("-b:v") + 1])
        assertFalse(argv.contains("-crf"))
    }

    @Test
    fun `x264 carries crf and preset so it matches a desktop command`() {
        val argv = FfmpegCommandBuilder.fromPattern(
            renamed,
            RenderSpec(encoder = Encoder.X264, crf = 18, preset = "slow"),
            "/out.mp4",
        )

        assertEquals("libx264", argv[argv.indexOf("-c:v") + 1])
        assertEquals("18", argv[argv.indexOf("-crf") + 1])
        assertEquals("slow", argv[argv.indexOf("-preset") + 1])
        assertFalse(argv.contains("-b:v"))
    }

    @Test
    fun `x264 is always given a memory ceiling`() {
        // Without these, x264 grew past 1.1GB rendering 53 frames and Samsung's low-memory
        // killer terminated the app mid-render. The defaults must stay bounded.
        val argv = FfmpegCommandBuilder.fromPattern(renamed, RenderSpec(), "/out.mp4")

        assertTrue(argv.contains("-threads"), "unbounded threads each hold frame buffers")
        assertTrue(
            argv[argv.indexOf("-threads") + 1].toInt() in 1..6,
            "thread cap should stay modest: ${argv[argv.indexOf("-threads") + 1]}",
        )
        val x264Params = argv[argv.indexOf("-x264-params") + 1]
        assertTrue(x264Params.contains("rc-lookahead="), "lookahead is the single biggest memory lever")
        assertTrue(
            x264Params.contains("sliced-threads=1"),
            "sliced threads share one frame buffer; without it a 4K encode peaked near 2GB and was killed",
        )
        assertEquals("medium", argv[argv.indexOf("-preset") + 1], "slow keeps a ~50 frame lookahead")
    }

    @Test
    fun `scaling fits the long edge in a box, scaling down with even dimensions`() {
        // The long edge (whichever it is) becomes 1920, not the width - a portrait frame must
        // not have its short edge blown up to 1920. force_divisible_by=2 keeps H.264 happy.
        val argv = FfmpegCommandBuilder.fromPattern(renamed, RenderSpec(longEdgePx = 1920), "/out.mp4")
        assertEquals(
            "scale=w=1920:h=1920:force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos",
            argv[argv.indexOf("-vf") + 1],
        )
    }

    @Test
    fun `deflicker is appended to the filter chain only when asked`() {
        val plain = FfmpegCommandBuilder.fromPattern(renamed, RenderSpec(), "/out.mp4")
        assertFalse(plain[plain.indexOf("-vf") + 1].contains("deflicker"))

        val smoothed = FfmpegCommandBuilder.fromPattern(
            renamed,
            RenderSpec(deflicker = true),
            "/out.mp4",
        )
        assertTrue(smoothed[smoothed.indexOf("-vf") + 1].endsWith(",deflicker"))
    }

    @Test
    fun `no filter graph is emitted when nothing needs filtering`() {
        val argv = FfmpegCommandBuilder.fromPattern(
            renamed,
            RenderSpec(longEdgePx = 0, deflicker = false),
            "/out.mp4",
        )
        assertFalse(argv.contains("-vf"))
    }

    @Test
    fun `unrenamed frames render through a concat list`() {
        // The reason renaming is optional: the list names exactly the session's files, so a
        // render cannot pick up unrelated photos sharing the folder.
        val argv = FfmpegCommandBuilder.fromConcatList("/data/frames.txt", RenderSpec(), "/out.mp4")

        assertEquals("concat", argv[argv.indexOf("-f") + 1])
        assertEquals("0", argv[argv.indexOf("-safe") + 1], "absolute paths need -safe 0")
        assertEquals("/data/frames.txt", argv[argv.indexOf("-i") + 1])
    }

    @Test
    fun `the concat list repeats the final frame so it is not dropped`() {
        val list = FfmpegCommandBuilder.concatList(listOf("/a/one.jpg", "/a/two.jpg"), fps = 30)

        assertTrue(list.startsWith("ffconcat version 1.0"))
        assertEquals(3, list.lines().count { it.startsWith("file ") })
        assertTrue(list.trimEnd().endsWith("file '/a/two.jpg'"))
    }

    @Test
    fun `concat durations follow the frame rate`() {
        val list = FfmpegCommandBuilder.concatList(listOf("/a/one.jpg"), fps = 25)
        assertContains(list, "duration 0.040000")
    }

    @Test
    fun `concat durations use a decimal point whatever the device locale`() {
        // A comma-decimal locale (German, Russian, ...) must not produce "duration 0,040000",
        // which ffmpeg's concat demuxer rejects - every render would fail on those phones.
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val list = FfmpegCommandBuilder.concatList(listOf("/a/one.jpg"), fps = 25)
            assertContains(list, "duration 0.040000")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `quotes in a path cannot break out of the concat entry`() {
        val list = FfmpegCommandBuilder.concatList(listOf("/a/it's here.jpg"), fps = 30)
        // The shell idiom for a quote inside a single-quoted string: close, escape, reopen.
        assertContains(list, """file '/a/it'\''s here.jpg'""")
    }

    @Test
    fun `renders as a pasteable desktop command`() {
        val argv = FfmpegCommandBuilder.fromPattern(renamed, RenderSpec(), "/out put.mp4")
        val command = FfmpegCommandBuilder.asShellCommand(argv)

        assertTrue(command.startsWith("ffmpeg -y "))
        assertContains(command, "\"/out put.mp4\"", message = "paths with spaces must be quoted")
    }
}
