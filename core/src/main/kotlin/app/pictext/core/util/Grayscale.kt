package app.pictext.core.util

import app.pictext.core.model.GrayImage

/** 整数灰度化：`Y = (77R + 150G + 29B) shr 8`。 */
object Grayscale {
    fun fromArgb(width: Int, height: Int, argb: IntArray, backgroundColor: Int = 0xFFFFFFFF.toInt()): GrayImage {
        require(argb.size >= width * height) { "argb data too small" }
        val out = ByteArray(width * height)
        for (i in out.indices) {
            val c = argb[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            out[i] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
        }
        return GrayImage(width, height, out, backgroundColor)
    }
}
