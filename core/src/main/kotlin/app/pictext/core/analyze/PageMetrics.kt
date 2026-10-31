package app.pictext.core.analyze

import app.pictext.core.model.Block
import app.pictext.core.model.LineRole
import app.pictext.core.util.Stats
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Step 7 的页面度量。版心由“长行”的最左/最右决定，避免页码、水印把版心拉到图片边缘。
 *
 * v1.1：只用 BODY / HEADING 行估计版心。头像行、按钮行、图注行虽然也可能很长，
 * 但它们不代表正文的左右边界；把它们算进来会让版心变宽，切分安全门随之失效。
 *
 * v1.3：**粗略版面宽与长行门槛都只看文字行**（不再看方块 bounds）。图文混排的图块会把
 * 右侧那几行字并进自己的包围盒，用方块算出来的版面宽会被撑宽，长行门槛跟着抬高，
 * 于是正常的正文也不再算“长行”，整页被 `too_few_long_lines` 判死。
 */
data class PageMetrics(
    val textLeft: Int,
    val textRight: Int,
    val medianLineHeight: Float,
    val medianGap: Float,
    val medianPitch: Float,
    val longLineCount: Int,
    val roughLeft: Int,
    val roughRight: Int,
    /**
     * 长行门槛（列宽，A 坐标）：宽度 ≥ 它的行才算“长行”。
     *
     * `0` 表示没算过（测试或旧调用直接构造），此时按 `0.70 × roughWidth` 推导；
     * 生产路径由 [PageMetricsCalculator] 填真值（`longLineRatio × 文字行宽 90 分位`）。
     */
    val longLineMinWidth: Int = 0,
) {
    val roughWidth: Int get() = max(0, roughRight - roughLeft)
    val pageWidth: Int get() = max(0, textRight - textLeft)

    /** 实际使用的长行门槛。 */
    val longLineThreshold: Int
        get() = if (longLineMinWidth > 0) {
            longLineMinWidth
        } else {
            max(1, (LEGACY_LONG_LINE_RATIO * roughWidth).roundToInt())
        }

    private companion object {
        /** 旧口径：长行门槛 = 0.70 × 粗略版面宽。 */
        const val LEGACY_LONG_LINE_RATIO = 0.70f
    }
}

object PageMetricsCalculator {
    fun compute(blocks: List<Block>, options: AnalysisOptions = AnalysisOptions()): PageMetrics {
        val lines = blocks.flatMap { b ->
            when (b) {
                is Block.Paragraph -> b.lines
                is Block.Figure -> emptyList()
            }
        }.sortedBy { it.band.top }

        if (lines.isEmpty()) {
            // 没有文字行：只能退回方块范围（图块 / 空页），供调用方做兜底。
            val allBounds = blocks.map { it.bounds }.filter { !it.isEmpty }
            val roughLeft = allBounds.minOfOrNull { it.left } ?: 0
            val roughRight = allBounds.maxOfOrNull { it.right } ?: 0
            return PageMetrics(roughLeft, roughRight, 0f, 6f, 0f, 0, roughLeft, roughRight, 0)
        }

        // 粗略版面宽只用文字行：图块右缘常常是“被并进图里的那几行字”，
        // 用它算会把长行门槛一起抬高（见类注释）。
        val roughLeft = lines.minOf { it.band.left }
        val roughRight = lines.maxOf { it.band.right }

        val bodyLines = lines.filter { it.role == LineRole.BODY || it.role == LineRole.HEADING }
            .ifEmpty { lines }
        // 参考宽度取 90 分位而不是 max：一条通栏插图行/超宽标题不该把整页门槛顶上去。
        val widths = IntArray(bodyLines.size) { bodyLines[it].band.width }.sortedArray()
        val refIndex = ((widths.size - 1) * 0.9f).toInt().coerceIn(0, widths.size - 1)
        val refWidth = widths[refIndex].coerceAtLeast(1)
        val longLineMinWidth = max(1, (options.longLineRatio * refWidth).roundToInt())

        val longLines = bodyLines.filter { it.band.width >= longLineMinWidth }
        val textLeft = longLines.minOfOrNull { it.band.left } ?: roughLeft
        val textRight = longLines.maxOfOrNull { it.band.right } ?: roughRight

        val heights = IntArray(bodyLines.size) { bodyLines[it].band.height }
        val medH = Stats.robustMedian(heights)

        val gaps = ArrayList<Int>(bodyLines.size)
        val pitches = ArrayList<Int>(bodyLines.size)
        for (i in 1 until bodyLines.size) {
            val g = bodyLines[i].band.top - bodyLines[i - 1].band.bottom
            if (g > 0) gaps.add(g)
            val p = bodyLines[i].band.top - bodyLines[i - 1].band.top
            if (p > 0) pitches.add(p)
        }
        val medGap = if (gaps.isEmpty()) 6f else Stats.median(gaps, 6)
        val pitch = if (pitches.isEmpty()) medH + medGap else Stats.median(pitches, (medH + medGap).toInt())

        return PageMetrics(
            textLeft = textLeft,
            textRight = textRight,
            medianLineHeight = medH,
            medianGap = medGap,
            medianPitch = pitch,
            longLineCount = longLines.size,
            roughLeft = roughLeft,
            roughRight = roughRight,
            longLineMinWidth = longLineMinWidth,
        )
    }
}
