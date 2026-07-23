package org.peekit.opentimelapse.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrCodeTest {

    /**
     * The canonical Reed-Solomon vector from the QR spec / Thonky tutorial: the 16 data
     * codewords for "HELLO WORLD" (version 1-M, byte mode) and their 10 error-correction
     * codewords. If GF(256) arithmetic or the generator polynomial were wrong, this breaks.
     */
    @Test
    fun `reed-solomon matches the known HELLO WORLD vector`() {
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        val expected = intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23)

        // reedSolomon is private; exercise it through the same path the encoder uses.
        val ec = callReedSolomon(data, 10)
        assertTrue(expected.toList() == ec.toList(), "got ${ec.toList()}")
    }

    @Test
    fun `a version 1 code is 21 modules square with three finder patterns`() {
        val m = QrCode.encode("hi")
        assertEquals(21, m.size)
        assertEquals(21, m[0].size)
        assertFinder(m, 0, 0)
        assertFinder(m, 0, 21 - 7)
        assertFinder(m, 21 - 7, 0)
        // The always-dark module sits at (row 8, col size-8), by the top-right format strip.
        assertTrue(m[8][21 - 8])
    }

    @Test
    fun `version scales with content length`() {
        // Short string fits v1 (21); a long URL needs a bigger version.
        assertEquals(21, QrCode.encode("x").size)
        val url = "http://192.168.1.100:8787/?token=abcdefghijklmnopqrstuvwxyz012345"
        val big = QrCode.encode(url)
        assertTrue(big.size > 21, "a 64-char URL should not fit v1")
        assertTrue(big.size <= 37, "should still fit within v5 (37 modules)")
    }

    @Test
    fun `over-long input is rejected rather than producing a broken code`() {
        assertFailsWith<QrCode.TooLong> { QrCode.encode("z".repeat(200)) }
    }

    /**
     * A golden matrix, captured after the output was confirmed scannable by a real decoder
     * (OpenCV) off-device. Byte placement, masking, format bits and the dark module are all
     * subtle enough that a structural check misses a regression a decoder would catch; this
     * pins the exact modules so a change that breaks scannability fails here instead of in
     * the field. Regenerate only against a decoder, never by hand.
     */
    @Test
    fun `encoding is byte-exact against a decoder-verified golden`() {
        val golden = GOLDEN.split(';')
        val m = QrCode.encode("OPENTIMELAPSE-PAIR")
        assertEquals(golden.size, m.size)
        for (row in m.indices) {
            val line = m[row].joinToString("") { if (it) "1" else "0" }
            assertEquals(golden[row], line, "row $row differs")
        }
    }

    private companion object {
        const val GOLDEN =
            "1111111000100000001111111;1000001001000001101000001;1011101010010010101011101;" +
                "1011101001001100001011101;1011101001001110001011101;1000001000111011101000001;" +
                "1111111010101010101111111;0000000010010001000000000;1110111110001000111000100;" +
                "1110000001011101110000010;1101001110111010101011111;1011100001101111000010010;" +
                "1101001000110010011010101;0001010000110011100000100;1010011111100100001110011;" +
                "0100000110110001110000001;1000111001101001111111110;0000000000111101100010010;" +
                "1111111010011011101010111;1000001011001111100010011;1011101011010010111110101;" +
                "1011101000010010000101100;1011101011100101111111101;1000001010110001010000010;" +
                "1111111011101001110100111"
    }

    private fun assertFinder(m: Array<BooleanArray>, row: Int, col: Int) {
        // Finder corners are dark; the ring one in is light.
        assertTrue(m[row][col], "finder corner at $row,$col should be dark")
        assertTrue(m[row + 6][col + 6], "finder far corner should be dark")
        assertTrue(!m[row + 1][col + 1], "finder inner ring should be light")
        assertTrue(m[row + 3][col + 3], "finder centre should be dark")
    }

    /** Reflectively reach the private RS so the test does not depend on encode() framing. */
    private fun callReedSolomon(data: IntArray, ecLen: Int): IntArray {
        val method = QrCode::class.java.getDeclaredMethod(
            "reedSolomon", IntArray::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return method.invoke(QrCode, data, ecLen) as IntArray
    }
}
