package org.peekit.opentimelapse.core.net

/**
 * A minimal QR encoder, just enough to turn a pairing URL into something a phone camera can
 * scan. Pure logic and no dependency - ZXing would be the obvious choice, but the app's
 * dependency list is short on purpose and a URL is a tiny, fixed encoding problem.
 *
 * Deliberately narrow: byte mode, error-correction level L, versions 1-5, one fixed mask.
 * Level L versions 1-5 are all a single error-correction block, which removes block
 * interleaving entirely, and v5 holds ~100 bytes - far more than `http://<ip>:<port>/?token=`.
 * A single fixed mask is still a valid, scannable code because the mask id travels in the
 * format bits; choosing the "best" mask by penalty only affects robustness, not correctness.
 *
 * Returns a square matrix of booleans: true is a dark module. Rendering to pixels is the
 * caller's job.
 */
object QrCode {

    /** Data-codeword capacity per version at level L (after mode + count + terminator fit inside). */
    private val DATA_CODEWORDS = mapOf(1 to 19, 2 to 34, 3 to 55, 4 to 80, 5 to 108)
    private val EC_CODEWORDS = mapOf(1 to 7, 2 to 10, 3 to 15, 4 to 20, 5 to 26)

    /** The single alignment-pattern centre for v2-5; v1 has none. */
    private val ALIGN_CENTRE = mapOf(2 to 18, 3 to 22, 4 to 26, 5 to 30)

    /** Format bits for level L with mask 0, BCH-encoded and mask-applied (the canonical value). */
    private const val FORMAT_L_MASK0 = 0b111011111000100

    class TooLong(val bytes: Int) : Exception("$bytes bytes is too long for a v5 QR")

    fun encode(text: String): Array<BooleanArray> {
        val data = text.encodeToByteArray()
        val version = pickVersion(data.size) ?: throw TooLong(data.size)
        val size = 17 + version * 4

        val codewords = buildCodewords(data, version)
        val modules = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        placeFinder(modules, reserved, 0, 0)
        placeFinder(modules, reserved, size - 7, 0)
        placeFinder(modules, reserved, 0, size - 7)
        placeTiming(modules, reserved, size)
        ALIGN_CENTRE[version]?.let { placeAlignment(modules, reserved, it) }
        reserveFormat(reserved, size)
        // The one always-dark module, beside the top-right format strip at (row 8, col size-8).
        modules[8][size - 8] = true
        reserved[8][size - 8] = true

        placeData(modules, reserved, codewords, size)
        placeFormat(modules, size)
        return modules
    }

    private fun pickVersion(byteLen: Int): Int? {
        // 1 byte mode nibble + 1 byte count (8-bit for v1-9) + terminator ~= 2 bytes overhead.
        for (v in 1..5) if (byteLen + 2 <= DATA_CODEWORDS.getValue(v)) return v
        return null
    }

    private fun buildCodewords(data: ByteArray, version: Int): IntArray {
        val capacity = DATA_CODEWORDS.getValue(version)
        val bits = BitBuffer()
        bits.append(0b0100, 4)              // byte mode
        bits.append(data.size, 8)           // 8-bit count indicator (v1-9)
        for (b in data) bits.append(b.toInt() and 0xFF, 8)
        bits.append(0, 4)                   // terminator, truncated if it would overflow

        while (bits.length % 8 != 0) bits.append(0, 1)

        val dataCodewords = bits.toBytes().toMutableList()
        // Pad to capacity with the two alternating pad bytes the spec mandates.
        var pad = 0xEC
        while (dataCodewords.size < capacity) {
            dataCodewords += pad
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }

        val ec = reedSolomon(dataCodewords.toIntArray(), EC_CODEWORDS.getValue(version))
        return (dataCodewords + ec.toList()).toIntArray()
    }

    // --- GF(256) for Reed-Solomon --------------------------------------------------------

    private val EXP = IntArray(256)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D   // QR's primitive polynomial
        }
        EXP[255] = EXP[0]
    }

    private fun mul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[(LOG[a] + LOG[b]) % 255]

    private fun reedSolomon(data: IntArray, ecLen: Int): IntArray {
        val divisor = generator(ecLen)
        val result = IntArray(ecLen)
        for (byte in data) {
            val factor = byte xor result[0]
            for (i in 0 until ecLen - 1) result[i] = result[i + 1]
            result[ecLen - 1] = 0
            for (i in 0 until ecLen) result[i] = result[i] xor mul(divisor[i], factor)
        }
        return result
    }

    /** The divisor polynomial's low coefficients (length ecLen, leading 1 omitted). */
    private fun generator(ecLen: Int): IntArray {
        val result = IntArray(ecLen)
        result[ecLen - 1] = 1
        var root = 1
        for (i in 0 until ecLen) {
            for (j in 0 until ecLen) {
                result[j] = mul(result[j], root)
                if (j + 1 < ecLen) result[j] = result[j] xor result[j + 1]
            }
            root = mul(root, 2)
        }
        return result
    }

    // --- Matrix placement ----------------------------------------------------------------

    private fun placeFinder(m: Array<BooleanArray>, r: Array<BooleanArray>, row: Int, col: Int) {
        for (dr in -1..7) for (dc in -1..7) {
            val rr = row + dr
            val cc = col + dc
            if (rr !in m.indices || cc !in m.indices) continue
            val dark = (dr in 0..6 && (dc == 0 || dc == 6)) ||
                (dc in 0..6 && (dr == 0 || dr == 6)) ||
                (dr in 2..4 && dc in 2..4)
            m[rr][cc] = dark
            r[rr][cc] = true
        }
    }

    private fun placeTiming(m: Array<BooleanArray>, r: Array<BooleanArray>, size: Int) {
        for (i in 8 until size - 8) {
            val on = i % 2 == 0
            if (!r[6][i]) { m[6][i] = on; r[6][i] = true }
            if (!r[i][6]) { m[i][6] = on; r[i][6] = true }
        }
    }

    private fun placeAlignment(m: Array<BooleanArray>, r: Array<BooleanArray>, centre: Int) {
        for (dr in -2..2) for (dc in -2..2) {
            val rr = centre + dr
            val cc = centre + dc
            val dark = dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0)
            m[rr][cc] = dark
            r[rr][cc] = true
        }
    }

    private fun reserveFormat(r: Array<BooleanArray>, size: Int) {
        for (i in 0..8) {
            r[8][i] = true
            r[i][8] = true
        }
        for (i in 0..7) {
            r[8][size - 1 - i] = true
            r[size - 1 - i][8] = true
        }
    }

    private fun placeData(
        m: Array<BooleanArray>,
        r: Array<BooleanArray>,
        codewords: IntArray,
        size: Int,
    ) {
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            // Step over the vertical timing column by moving to 5; the decrement then
            // continues from 5, not 6, so the remaining column pairs stay aligned to the
            // grid. Using a temporary here instead double-processes column 4.
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val cc = right - j
                    // Both columns of a pair share one direction: up on some column pairs,
                    // down on the next. Keyed off the right column so the two agree.
                    val upward = ((right + 1) and 2) == 0
                    val row = if (upward) size - 1 - vert else vert
                    if (r[row][cc]) continue
                    var dark = if (bitIndex < totalBits) {
                        val cw = codewords[bitIndex / 8]
                        (cw shr (7 - bitIndex % 8)) and 1 == 1
                    } else {
                        false
                    }
                    // Mask 0: invert where (row + col) is even.
                    if ((row + cc) % 2 == 0) dark = !dark
                    m[row][cc] = dark
                    bitIndex++
                }
            }
            right -= 2
        }
    }

    private fun placeFormat(m: Array<BooleanArray>, size: Int) {
        val bits = FORMAT_L_MASK0
        // First copy: down the right of the top-left finder, then across its bottom.
        for (i in 0..5) m[i][8] = bit(bits, i)
        m[7][8] = bit(bits, 6)
        m[8][8] = bit(bits, 7)
        m[8][7] = bit(bits, 8)
        for (i in 9..14) m[8][14 - i] = bit(bits, i)
        // Second copy: across the bottom of the top-right finder, then down beside the
        // bottom-left finder.
        for (i in 0..7) m[8][size - 1 - i] = bit(bits, i)
        for (i in 8..14) m[size - 15 + i][8] = bit(bits, i)
    }

    private fun bit(value: Int, index: Int): Boolean = (value shr index) and 1 == 1

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length get() = bits.size

        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value shr i) and 1 == 1
        }

        fun toBytes(): List<Int> {
            val out = ArrayList<Int>(bits.size / 8)
            var i = 0
            while (i < bits.size) {
                var b = 0
                for (j in 0 until 8) {
                    b = b shl 1
                    if (i + j < bits.size && bits[i + j]) b = b or 1
                }
                out += b
                i += 8
            }
            return out
        }
    }
}
