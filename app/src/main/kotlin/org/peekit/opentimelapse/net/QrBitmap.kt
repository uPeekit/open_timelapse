package org.peekit.opentimelapse.net

import android.graphics.Bitmap
import android.graphics.Color
import org.peekit.opentimelapse.core.net.QrCode

/**
 * Renders the pure [QrCode] matrix to a black-and-white [Bitmap] for the pairing screen.
 *
 * A quiet zone (the mandatory light border) is added here rather than in the encoder, since
 * it is a rendering concern: without it many scanners refuse to lock on.
 */
object QrBitmap {

    private const val QUIET = 4

    fun render(text: String, moduleSize: Int = 12): Bitmap? {
        val matrix = runCatching { QrCode.encode(text) }.getOrNull() ?: return null
        val modules = matrix.size + QUIET * 2
        val pixels = modules * moduleSize

        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.RGB_565)
        bitmap.eraseColor(Color.WHITE)

        for (row in matrix.indices) {
            for (col in matrix[row].indices) {
                if (!matrix[row][col]) continue
                val top = (row + QUIET) * moduleSize
                val left = (col + QUIET) * moduleSize
                for (y in 0 until moduleSize) {
                    for (x in 0 until moduleSize) {
                        bitmap.setPixel(left + x, top + y, Color.BLACK)
                    }
                }
            }
        }
        return bitmap
    }
}
