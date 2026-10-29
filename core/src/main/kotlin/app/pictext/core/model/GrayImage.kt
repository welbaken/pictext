package app.pictext.core.model

/**
 * 单通道 8 位灰度图，`data[y * width + x]`，按无符号解释（0..255）。
 *
 * @param backgroundColor 源图边界的中位色（ARGB），用于阅读页底色。
 */
class GrayImage(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
) {
    init {
        require(width >= 0 && height >= 0) { "invalid size ${width}x$height" }
        require(data.size >= width * height) { "data too small: ${data.size} < ${width * height}" }
    }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    /** 创建一张纯色测试图。 */
    companion object {
        fun solid(width: Int, height: Int, value: Int, backgroundColor: Int = 0xFFFFFFFF.toInt()): GrayImage {
            val v = value.coerceIn(0, 255).toByte()
            return GrayImage(width, height, ByteArray(width * height) { v }, backgroundColor)
        }
    }
}
