package org.peekit.opentimelapse.core.engine

import org.peekit.opentimelapse.core.model.NamingConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FrameNamerTest {

    private val namer = FrameNamer(NamingConfig(enabled = true, prefix = "mytimelapse", padWidth = 8))

    @Test
    fun `pads the index to the configured width`() {
        assertEquals("mytimelapse00000001", namer.baseName(1))
        assertEquals("mytimelapse00000042", namer.baseName(42))
        assertEquals("mytimelapse12345678", namer.baseName(12_345_678))
    }

    @Test
    fun `an index wider than the pad width is not truncated`() {
        assertEquals("mytimelapse123456789", namer.baseName(123_456_789))
    }

    @Test
    fun `keeps the file type but normalises the case`() {
        // The ffmpeg input pattern hard-codes one spelling; mixed case would split the sequence.
        assertEquals("mytimelapse00000007.jpg", namer.fileName(7, "IMG_1234.JPG"))
        assertEquals("mytimelapse00000007.dng", namer.fileName(7, "IMG_1234.dng"))
    }

    @Test
    fun `handles a name with no extension`() {
        assertEquals("mytimelapse00000003", namer.fileName(3, "IMG_1234"))
    }

    @Test
    fun `handles a name with several dots`() {
        assertEquals("mytimelapse00000003.jpg", namer.fileName(3, "2026.07.21_12.00.00.jpg"))
    }

    @Test
    fun `siblings from one shutter press share the index`() {
        val names = listOf(jpeg(), dng()).map { namer.fileName(9, it.displayName) }
        assertContentEquals(listOf("mytimelapse00000009.jpg", "mytimelapse00000009.dng"), names)
    }

    @Test
    fun `reports the distinct extensions in a capture`() {
        assertContentEquals(listOf("jpg", "dng"), namer.extensionsOf(listOf(jpeg(), dng())))
        assertContentEquals(listOf("jpg"), namer.extensionsOf(listOf(jpeg("a.jpg"), jpeg("b.JPG"))))
    }

    @Test
    fun `a narrower pad width still produces a valid pattern`() {
        val four = FrameNamer(NamingConfig(enabled = true, prefix = "tl", padWidth = 4))
        assertEquals("tl0001.jpg", four.fileName(1, "x.jpg"))
    }
}
