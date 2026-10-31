package app.pictext.core.analyze

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 分析图定标规则（P1）：**宽度优先**。
 *
 * 旧规则是“长边 ≤ 1600”，对长图是灾难：690×10305 会被压成 107×1598，一行只剩 ~30 个
 * 像素列，字缝、行带、中线全部失真，重排结果也就成了“左边一条窄列”。
 *
 * 新规则只看宽度：`k = min(1, maxWidth / srcW)`。高度不再参与定标，超长的图由
 * [AnalysisOptions.analysisMaxPixels] 触发条带分析（[StripLayoutAnalyzer]），
 * 条带只压内存，不压宽度。
 *
 * CLI、Android、回归测试必须共用这一份规则，否则调参与真机行为会悄悄分叉。
 */
object AnalysisScale {

    /** 分析图相对源图的缩放因子，`(0, 1]`。 */
    fun factor(
        srcWidth: Int,
        srcHeight: Int,
        options: AnalysisOptions = AnalysisOptions(),
    ): Float {
        if (srcWidth <= 0 || srcHeight <= 0) return 1f
        val byWidth = options.analysisMaxWidth.toFloat() / srcWidth
        return min(1f, byWidth)
    }

    /** 分析图尺寸，宽高至少为 1。 */
    fun size(
        srcWidth: Int,
        srcHeight: Int,
        options: AnalysisOptions = AnalysisOptions(),
    ): AnalysisSize {
        val k = factor(srcWidth, srcHeight, options)
        return AnalysisSize(
            width = max(1, (srcWidth * k).roundToInt()),
            height = max(1, (srcHeight * k).roundToInt()),
            factor = k,
        )
    }

    /** 分析图是否需要切条带（像素超预算）。 */
    fun needsStrips(
        analysisWidth: Int,
        analysisHeight: Int,
        options: AnalysisOptions = AnalysisOptions(),
    ): Boolean = analysisWidth.toLong() * analysisHeight > options.analysisMaxPixels
}

/** 分析图尺寸与缩放因子。 */
data class AnalysisSize(val width: Int, val height: Int, val factor: Float)
