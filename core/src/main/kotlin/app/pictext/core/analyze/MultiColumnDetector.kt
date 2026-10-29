package app.pictext.core.analyze

import app.pictext.core.model.Block

/**
 * P1-4 多栏安全网：只检测，不重排。
 *
 * 双栏报刊是唯一“会安静地做错”的场景——切点干净、`cleanRatio` 通过，但左右两栏的内容
 * 被按行交错拼在一起。检测条件（三条同时满足）：
 *
 * 1. 中线附近存在宽度 ≥ 5% 页宽、每列墨量 < 1% 的竖带；
 * 2. 该竖带在至少 60% 页高上连续无墨；
 * 3. 竖带左右两侧各有 ≥ 2 条长行（宽度 ≥ 30% 版心）。
 *
 * 检测到就直接回退原图，不尝试自动重排。
 */
class MultiColumnDetector(private val options: AnalysisOptions = AnalysisOptions()) {

    fun detect(
        mask: BooleanArray,
        width: Int,
        height: Int,
        blocks: List<Block>,
        metrics: PageMetrics,
    ): Boolean {
        val pageWidth = metrics.pageWidth
        if (pageWidth <= 0 || height <= 0 || width <= 0) return false
        if (metrics.longLineCount < options.multiColumnMinSideLines * 2) return false

        val center = (metrics.textLeft + metrics.textRight) / 2
        val minBandWidth = maxOf(3, (options.multiColumnMinWidthRatio * pageWidth).toInt())
        val maxInk = options.multiColumnMaxInkRatio

        var x = (metrics.textLeft + pageWidth * 0.15f).toInt().coerceIn(0, width - 1)
        val xEnd = (metrics.textRight - pageWidth * 0.15f).toInt().coerceIn(0, width)
        while (x < xEnd) {
            if (colInkRatio(mask, width, height, x) >= maxInk) {
                x++
                continue
            }
            var x1 = x
            while (x1 + 1 < width && colInkRatio(mask, width, height, x1 + 1) < maxInk) x1++
            val bandWidth = x1 - x + 1
            if (bandWidth >= minBandWidth) {
                val mid = (x + x1) / 2
                val coverage = blankCoverage(mask, width, height, mid)
                if (coverage >= options.multiColumnMinCoverage && hasLongLinesOnBothSides(blocks, metrics, mid)) {
                    // 空竖带应靠近页心；靠边的留白不算分栏。
                    if (kotlin.math.abs(mid - center) <= 0.35f * pageWidth) return true
                }
            }
            x = x1 + 1
        }
        return false
    }

    private fun colInkRatio(mask: BooleanArray, width: Int, height: Int, x: Int): Float {
        var c = 0
        for (y in 0 until height) if (mask[y * width + x]) c++
        return c.toFloat() / height
    }

    /** 该列上最长的连续无墨行数 / 页高。 */
    private fun blankCoverage(mask: BooleanArray, width: Int, height: Int, x: Int): Float {
        var best = 0
        var run = 0
        for (y in 0 until height) {
            if (!mask[y * width + x]) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best.toFloat() / height
    }

    private fun hasLongLinesOnBothSides(blocks: List<Block>, metrics: PageMetrics, gutterX: Int): Boolean {
        val minWidth = 0.30f * metrics.pageWidth
        var left = 0
        var right = 0
        for (block in blocks) {
            if (block !is Block.Paragraph) continue
            for (line in block.lines) {
                if (line.band.width < minWidth) continue
                if (line.band.right <= gutterX) left++ else if (line.band.left >= gutterX) right++
            }
        }
        return left >= options.multiColumnMinSideLines && right >= options.multiColumnMinSideLines
    }
}
