package app.pictext.core.analyze

import app.pictext.core.util.Otsu

/** 二值化结果。 */
data class BinarizeResult(
    val mask: BooleanArray,
    val threshold: Int,
    val inkPixels: Int,
)

/**
 * Step 3：Otsu 二值化 + 8 邻域孤立点清除。
 *
 * 不做形态学腐蚀：22px 中文的细笔画只有 1.5–2px 宽，3×3 腐蚀会把"一、二、十"整根吃掉。
 */
class Binarizer(private val options: AnalysisOptions = AnalysisOptions()) {

    /** @param width 图像宽，用于邻域清理；`ink.size` 必须等于 `width * height`。 */
    fun binarize(ink: ByteArray, width: Int, height: Int): BinarizeResult {
        val raw = Otsu.threshold(ink).coerceIn(options.inkFloor, options.inkCeil)
        val mask = BooleanArray(ink.size)
        for (i in ink.indices) {
            if ((ink[i].toInt() and 0xFF) > raw) mask[i] = true
        }
        val cleaned = if (width > 0 && height > 0) clean(mask, width, height) else mask
        var kept = 0
        for (b in cleaned) if (b) kept++
        return BinarizeResult(cleaned, raw, kept)
    }

    /** 清除 8 邻域无墨点的孤立像素；1px 长线因为每个像素都有邻居而保留。 */
    fun clean(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        if (width <= 0 || height <= 0) return mask
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                if (!mask[i]) continue
                var neighbors = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= height) continue
                    val base = yy * width
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val xx = x + dx
                        if (xx < 0 || xx >= width) continue
                        if (mask[base + xx]) {
                            neighbors++
                            if (neighbors >= 1) break
                        }
                    }
                    if (neighbors >= 1) break
                }
                if (neighbors >= 1) out[i] = true
            }
        }
        return out
    }
}
