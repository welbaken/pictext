package app.pictext.core.layout

import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LineRole
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RegionSource
import app.pictext.core.model.TextLine
import kotlin.math.max

/**
 * 区域二次判定：`figure ↔ text` 互换（docs/07 §4.4.4）。
 *
 * 模型是**文档扫描件**训练出来的，手机截图里“整张文字卡片被判成 figure”“照片被当成 text”
 * 都很常见。判据全部来自细化结果，不看模型标签：
 *
 * | 从 | 到 | 条件 |
 * |---|---|---|
 * | FIGURE | TEXT | ≥3 条稳定行、行高 CV <0.25、行宽中位数 ≥0.5×区域宽、密度 <0.30 |
 * | TEXT/CAPTION | FIGURE | 0 行，或密度 >0.62，或 maxRun >2.6×行高 |
 * | CHROME（文字型） | FIGURE(UI) | 0 行（页眉图/页脚图没有文字行） |
 *
 * 改判后 `source = MODEL_FIXED`，`label` 保留模型原标签，便于 CLI 统计“改判了多少”。
 */
class RegionKindFixer(private val options: LayoutOptions = LayoutOptions()) {

    fun fix(region: LayoutRegion, refine: RefineResult): LayoutRegion {
        val lines = refine.lines
        val nextKind = when (region.kind) {
            RegionKind.FIGURE, RegionKind.TABLE -> figureOrText(region, refine)
            RegionKind.TEXT, RegionKind.CAPTION -> textOrFigure(region, refine)
            RegionKind.CHROME -> if (lines.isEmpty()) RegionKind.FIGURE else RegionKind.CHROME
            RegionKind.TITLE -> if (lines.isEmpty()) RegionKind.FIGURE else RegionKind.TITLE
            RegionKind.OTHER -> RegionKind.OTHER
        }
        val withLines = region.withLines(lines)
        if (nextKind == region.kind) return withLines
        val fixed = withLines.withKind(nextKind, RegionSource.MODEL_FIXED)
        return if (nextKind == RegionKind.FIGURE) {
            // 改判成图块：文字行不再参与文字排版，整块保留像素，绝不切分。
            fixed.copy(lines = emptyList())
        } else {
            fixed
        }
    }

    private fun figureOrText(region: LayoutRegion, refine: RefineResult): RegionKind {
        // TABLE 不参与改判：表格拆成文字行会把单元格关系毁掉（D-V2-6）。
        if (region.kind == RegionKind.TABLE) return RegionKind.TABLE
        val lines = refine.lines
        if (lines.size < options.figureToTextMinLines) return region.kind
        val heights = FloatArray(lines.size) { lines[it].band.height.toFloat() }
        val mean = heights.average().toFloat()
        if (mean <= 0f) return region.kind
        val cv = sd(heights, mean) / mean
        if (cv > options.figureToTextMaxHeightCv) return region.kind
        val medWidth = median(FloatArray(lines.size) { lines[it].band.width.toFloat() })
        val regionWidth = max(1, region.bounds.width)
        if (medWidth < options.figureToTextMinWidthRatio * regionWidth) return region.kind
        if (refine.density > options.figureToTextMaxDensity) return region.kind
        return RegionKind.TEXT
    }

    private fun textOrFigure(region: LayoutRegion, refine: RefineResult): RegionKind {
        val lines = refine.lines
        if (lines.isEmpty()) return RegionKind.FIGURE
        if (refine.density > options.textToFigureMaxDensity) return RegionKind.FIGURE
        val medH = max(1f, refine.medianLineHeight)
        if (refine.maxRun > options.textToFigureMaxRunRatio * medH) return RegionKind.FIGURE
        return region.kind
    }

    /** 行高变异系数；样本 <2 时返回 0（单行一定是“稳定”的）。 */
    private fun sd(values: FloatArray, mean: Float): Float {
        if (values.size < 2) return 0f
        var acc = 0f
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        return kotlin.math.sqrt(acc / values.size)
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val s = values.clone()
        s.sort()
        return s[s.size / 2]
    }

    /** 改判后的行角色：TITLE→HEADING、CAPTION→CAPTION、CHROME→UI，TEXT 保留分类器结论。 */
    fun normalizeRoles(region: LayoutRegion): List<TextLine> {
        val forced = when (region.kind) {
            RegionKind.TITLE -> LineRole.HEADING
            RegionKind.CAPTION -> LineRole.CAPTION
            RegionKind.CHROME -> LineRole.UI
            else -> null
        } ?: return region.lines
        return region.lines.map { it.copy(role = forced) }
    }

    /** 竖排占比：竖排文字区域面积 / 全部文字区域面积（>0.3 整页回退原图）。 */
    fun verticalRatio(regions: List<LayoutRegion>): Float {
        var total = 0L
        var vertical = 0L
        for (r in regions) {
            if (!r.isTextual) continue
            val area = r.bounds.width.toLong() * r.bounds.height
            total += area
            if (r.vertical) vertical += area
        }
        return if (total <= 0L) 0f else vertical.toFloat() / total
    }

    /** 调试：行宽中位数与区域宽之比，CLI 的 `--dump-regions` 用。 */
    fun widthRatio(region: LayoutRegion): Float {
        if (region.lines.isEmpty()) return 0f
        val med = median(FloatArray(region.lines.size) { region.lines[it].band.width.toFloat() })
        return if (region.bounds.width <= 0) 0f else med / region.bounds.width
    }
}
