package app.pictext.core.analyze

import app.pictext.core.model.RectI
import kotlin.math.abs
import kotlin.math.max

/**
 * 一条超高行带内部的“图文竖切”结果。坐标为 A（分析图）。
 *
 * @param cutX 竖缝中心所在的列。
 * @param gapWidth 竖缝宽度（列数），只用于调试/日志。
 * @param left 左子区（半开区间 `[left, right) × [top, bottom)`）。
 * @param right 右子区。
 */
data class BandVerticalSplit(
    val cutX: Int,
    val gapWidth: Int,
    val left: RectI,
    val right: RectI,
)

/**
 * 带内竖切（**区域级**）：把一条超高行带按“贯通整带高度的空白竖缝”拆成左右两个子区。
 *
 * 要解决的问题（docs/02 的已知失败模式）：图文混排时，图的纵向范围让水平投影出一条
 * 超高的行带，图右侧的几行字被并进同一条带，随后整条带被判成图块，文字只能跟着图
 * 一起缩放（≈1.0×），既没有重排也不会放大。
 *
 * 与 [ColumnSplitter] 的分工必须分清：
 * - 本类切的是**区域**（左图 / 右文），输出两个矩形，之后各自重做行投影；
 * - [ColumnSplitter] 切的是**行内字缝**（一行切成左右两半）。
 * 拿 ColumnSplitter 去切图会把照片劈开；拿本类去切一行正常文字会把一句话拆成两行
 * （最后这条由 [BlockClassifier] 的验收规则兜住：切完必须验出**明显更矮**的文字行）。
 *
 * 纯函数、线程安全；只读 mask，不持有状态。
 */
class BandRegionSplitter(private val options: AnalysisOptions = AnalysisOptions()) {

    /**
     * 在 [band] 内找一条可以下刀的竖缝；找不到或位置太靠边时返回 `null`。
     *
     * 判据（缺一不可）：
     * 1. 竖缝里每一列的墨量都 ≤ `bandSplitMaxGapInkRatio × 带高`（允许抗锯齿噪点）；
     * 2. 缝宽 ≥ `bandSplitMinGapRatio × 图宽`（字距只有 2–4px，不够宽）；
     * 3. 切点到左右边界都 ≥ `bandSplitMinSideRatio × 带宽`（过于靠边就不处理）；
     * 4. 两侧都真的有墨（否则切出来的是页边留白）；
     * 5. 多条候选缝时取离带中心最近的（同距取更宽的）。
     */
    fun find(mask: BooleanArray, width: Int, band: RowBand): BandVerticalSplit? {
        if (width <= 0 || mask.isEmpty()) return null
        val x0 = band.x0.coerceIn(0, width)
        val x1 = band.x1.coerceIn(x0, width)
        val y0 = band.y0.coerceAtLeast(0)
        val y1 = band.y1.coerceAtMost(mask.size / width)
        val cols = x1 - x0
        if (cols < 8 || y1 - y0 < 2) return null

        val counts = IntArray(cols)
        for (y in y0 until y1) {
            val base = y * width
            for (i in 0 until cols) if (mask[base + x0 + i]) counts[i]++
        }
        // 前缀和，避免为每条候选缝重新累加整条带
        val prefix = IntArray(cols + 1)
        for (i in 0 until cols) prefix[i + 1] = prefix[i] + counts[i]

        val maxInk = max(1, (options.bandSplitMaxGapInkRatio * band.height).toInt())
        // 下限 4px：2–4px 是汉字字距/西文词距的量级，不能当图文通道。
        val minGap = max(4, (options.bandSplitMinGapRatio * width).toInt())
        val minSide = max(2, (options.bandSplitMinSideRatio * cols).toInt())
        val bandCenter = x0 + cols / 2

        var best: BandVerticalSplit? = null
        var bestScore = Int.MAX_VALUE
        var i = 0
        while (i < cols) {
            if (counts[i] > maxInk) {
                i++
                continue
            }
            val start = i
            while (i < cols && counts[i] <= maxInk) i++
            val gapWidth = i - start
            if (gapWidth < minGap) continue
            val cutLocal = (start + i - 1) / 2
            val cut = x0 + cutLocal
            if (cut - x0 < minSide || x1 - cut < minSide) continue
            if (prefix[cutLocal] <= 0 || prefix[cols] - prefix[cutLocal] <= 0) continue
            val score = abs(cut - bandCenter)
            if (score < bestScore) {
                bestScore = score
                best = BandVerticalSplit(
                    cutX = cut,
                    gapWidth = gapWidth,
                    left = RectI(x0, band.y0, cut, band.y1),
                    right = RectI(cut, band.y0, x1, band.y1),
                )
            }
        }
        return best
    }
}
