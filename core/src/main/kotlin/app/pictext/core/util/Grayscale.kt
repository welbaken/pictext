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

    /**
     * 把一块 ARGB 行带灰度化后写进整图 [dest] 的第 [destOffsetY] 行起。
     *
     * 条带解码（P2）和大图灰度化都走这里：一次只持有一块 `bandWidth × bandHeight`
     * 的 IntArray，避免 `getPixels` 给整张图开一个 4 字节/像素的临时数组
     * （690×10305 就是 28MB，正好是长图 OOM 的常见来源）。
     */
    fun writeBand(
        dest: ByteArray,
        destWidth: Int,
        destOffsetY: Int,
        bandWidth: Int,
        bandHeight: Int,
        argb: IntArray,
    ) {
        require(argb.size >= bandWidth * bandHeight) { "argb band too small" }
        for (y in 0 until bandHeight) {
            val srcBase = y * bandWidth
            val dstBase = (destOffsetY + y) * destWidth
            for (x in 0 until bandWidth) {
                val c = argb[srcBase + x]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                dest[dstBase + x] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
            }
        }
    }
}
