package app.pictext.core.analyze

import app.pictext.core.model.Block
import app.pictext.core.model.LineRole
import app.pictext.core.util.Stats
import kotlin.math.max

/**
 * Step 7 的页面度量。版心由“长行”（宽度 ≥ 0.70 × 粗略版面宽）的最左/最右决定，
 * 避免页码、水印把版心拉到图片边缘。
 *
 * v1.1：只用 BODY / HEADING 长行估计版心。头像行、按钮行、图注行虽然也可能很长，
 * 但它们不代表正文的左右边界；把它们算进来会让版心变宽，切分安全门随之失效。
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
) {
    val roughWidth: Int get() = max(0, roughRight - roughLeft)
    val pageWidth: Int get() = max(0, textRight - textLeft)
}

object PageMetricsCalculator {
    fun compute(blocks: List<Block>, options: AnalysisOptions = AnalysisOptions()): PageMetrics {
        val allBounds = blocks.map { it.bounds }.filter { !it.isEmpty }
        val roughLeft = allBounds.minOfOrNull { it.left } ?: 0
        val roughRight = allBounds.maxOfOrNull { it.right } ?: 0
        val roughWidth = max(1, roughRight - roughLeft)

        val lines = blocks.flatMap { b ->
            when (b) {
                is Block.Paragraph -> b.lines
                is Block.Figure -> emptyList()
            }
        }.sortedBy { it.band.top }
        if (lines.isEmpty()) {
            return PageMetrics(roughLeft, roughRight, 0f, 6f, 0f, 0, roughLeft, roughRight)
        }

        val bodyLines = lines.filter { it.role == LineRole.BODY || it.role == LineRole.HEADING }
            .ifEmpty { lines }
        val longLines = bodyLines.filter { it.band.width >= options.longLineRatio * roughWidth }
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
        )
    }
}
