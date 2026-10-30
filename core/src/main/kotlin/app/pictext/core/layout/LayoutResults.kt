package app.pictext.core.layout

import app.pictext.core.model.CutKind
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import kotlin.math.max

// ---------------------------------------------------------------------------
// 阅读顺序（§4.7）
// ---------------------------------------------------------------------------

/**
 * XY-cut 的结果。
 *
 * @param regions 排好序的区域（`order` 已写回）。
 * @param ambiguous 两个方向都切不开，退回“top, left”稳定排序。区域数 ≥4 时要记
 *   `layout_order_ambiguous`，但仍照常出图。
 */
data class OrderResult(
    val regions: List<LayoutRegion>,
    val ambiguous: Boolean,
)

// ---------------------------------------------------------------------------
// 覆盖审计与残差补区（§4.6）
// ---------------------------------------------------------------------------

/** 未被任何已接受区域覆盖的一块墨迹（S 坐标）。 */
data class ResidualRegion(val bounds: RectI, val ink: Int)

/**
 * 单 tile 的覆盖统计（T 空间：480×480 模型输入）。
 *
 * @param ink 该 tile 的有效区墨点数。
 * @param covered 被已接受区域覆盖的墨点数。
 * @param residuals 未被覆盖的墨迹聚类框（已映射回 S）。
 */
data class TileCoverage(
    val tileIndex: Int,
    val ink: Int,
    val covered: Int,
    val residuals: List<ResidualRegion> = emptyList(),
) {
    val ratio: Float get() = if (ink <= 0) 0f else covered.toFloat() / ink
    val uncovered: Int get() = max(0, ink - covered)
}

/**
 * 整页覆盖报告。
 *
 * @param coverage `ΣcoveredInk / Σink`，硬门槛 99.5%。
 * @param residualRatio `ΣuncoveredInk / Σink`；>5% 整页回退 v1。
 */
data class CoverageReport(
    val ink: Int,
    val covered: Int,
    val residuals: List<ResidualRegion> = emptyList(),
    val tiles: List<TileCoverage> = emptyList(),
) {
    val coverage: Float get() = if (ink <= 0) 1f else covered.toFloat() / ink
    val residualInk: Int get() = max(0, ink - covered)
    val residualRatio: Float get() = if (ink <= 0) 0f else residualInk.toFloat() / ink

    /**
     * 该报告是否触发回退；返回回退码或 `null`。
     *
     * **顺序很重要**：先判残差，再判覆盖率。因为 `coverage = 1 - residualRatio` 恒成立，
     * `residualRatio > 5%` 必然同时满足 `coverage < 95% < 99.5%`；若先判覆盖率，
     * `layout_residual_high` 永远不可达，诊断就只剩一个笼统的“覆盖不足”。
     */
    fun fallbackCode(options: LayoutOptions): String? = when {
        residualRatio > options.residualFailRatio -> "layout_residual_high"
        coverage < options.minCoverage -> "layout_low_coverage"
        else -> null
    }

    companion object {
        val EMPTY = CoverageReport(ink = 0, covered = 0)
    }
}

// ---------------------------------------------------------------------------
// 按需 N 段切分（§4.8.2）
// ---------------------------------------------------------------------------

/**
 * 输出的一段。`[x0, x1)` 是 S 坐标，`cut` 表示“这一段的**左**边界是怎么切出来的”：
 * 第一段的左边界就是行首，所以是 [CutKind.NONE]。
 */
data class Segment(
    val x0: Int,
    val x1: Int,
    val cut: CutKind = CutKind.NONE,
) {
    val width: Int get() = x1 - x0
}

/**
 * 一行的切分决定。
 *
 * @param segments 至少 1 段；段与段首尾相接，不重叠、不丢列。
 * @param compressed 该行放不下，只能整行压缩（敏感行/图注/找不到刀口）。
 * @param needed 按倍率“理想上”需要几段（用于诊断：`needed > segments.size` 说明被夹住了）。
 */
data class CutDecision(
    val segments: List<Segment>,
    val compressed: Boolean = false,
    val needed: Int = 1,
) {
    val count: Int get() = segments.size
    val isSplit: Boolean get() = segments.size > 1
    val forcedCuts: Int get() = segments.count { it.cut == CutKind.FORCED }
    val totalWidth: Int get() = segments.sumOf { it.width }

    companion object {
        fun single(line: TextLine, compressed: Boolean = false, needed: Int = 1): CutDecision =
            CutDecision(listOf(Segment(line.band.left, line.band.right, CutKind.NONE)), compressed, needed)
    }
}

// ---------------------------------------------------------------------------
// 区域细化（§4.5）
// ---------------------------------------------------------------------------

/**
 * 一个区域细化后的结果。
 *
 * @param region 行已写入、`bounds` 已按需要收紧/保持的区域。
 * @param medianLineHeight 该 ROI 内的行高中位数（S 坐标）；0 表示没找到行。
 * @param inkRatio ROI 内墨点占比；低于 `LayoutOptions.refineMinInkRatio` 视为“没字”。
 * @param density 墨点 / ROI 面积，用于 figure↔text 二次判定。
 * @param maxRun 最长竖直墨迹（S 像素）。
 * @param vertical 是否判定为竖排（列投影比行投影更碎）。
 * @param failed 细化失败（0 行或墨量过低）→ 该区域降级为整块 FIGURE，不硬切。
 */
data class RefineResult(
    val region: LayoutRegion,
    val medianLineHeight: Float,
    val inkPixels: Int,
    val inkRatio: Float,
    val density: Float,
    val maxRun: Int,
    val vertical: Boolean = false,
    val failed: Boolean = false,
    val lines: List<TextLine> = emptyList(),
) {
    val lineCount: Int get() = lines.size
}
