package app.pictext.core.layout

import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionSource
import app.pictext.core.model.TextLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 细化后的去重（V2-48）：**行带级**去重 + **残差块**去重。
 *
 * ## 为什么要在细化之后再做一次
 *
 * `RegionBuilder` 的跨 tile 去重（[Nms.dedupeAcrossTiles]）只处理**模型框**，
 * 框在 S 空间里差几个像素、IoU 掉到 0.5 以下就漏掉。而更常见的一类重复根本不在模型输出里：
 *
 * - 覆盖审计的残差是**按 tile 的未覆盖墨迹**聚出来的。同一个模型区域可能同时落在两个
 *   重叠 tile 的审计范围里（一个在 tile 内只被覆盖了一部分），于是两个 tile 各补出一块
 *   残差框；再细化时，残差 ROI 又把**已经由模型区域覆盖的行**重新切出来一遍。
 * - 残差 ROI 是一个外接框（还带 [LayoutOptions.refinePadPx] 外扩），框里可能包含
 *   “已被覆盖的行 + 真正漏掉的行”。细化只看像素，不会知道哪一行已经有人负责了。
 *
 * 结果就是 `plan.json` 里同一个 `src` 被渲染 2–3 次（V2-46 发现的 690×1533 深色页：
 * 17 组重复）。所以去重必须发生在**两边都细化完、拿到行带之后**，按行带几何判断。
 *
 * ## 规则
 *
 * 1. **模型区域优先**：先收模型区域的行，残差行的几何与已有行重合就丢弃；
 * 2. **同一行**：y 重叠 ≥ 50% 较矮行高、行中心距 ≤ 50% 较矮行高、x 重叠 ≥ 50% 较窄行宽。
 *    只要求 x 有重叠即可排除双栏（左右并排的两行 x 不重叠）；
 * 3. 一个区域的行**全部**被判为重复时：模型区域整块丢弃（它就是个重复框），
 *    残差区域也整块丢弃——真正没被覆盖的墨迹会由补区后的覆盖审计重新量出来，
 *    覆盖率掉下去就走回退，不会静默丢内容；
 * 4. 没有行的残差块（图/图标）与已保留的非文字区域包含度 ≥80% 时丢弃（跨 tile 重复）。
 */
class RegionDeduper {

    /**
     * @param modelRegions 模型区域（已细化、已二次判定）。
     * @param residuals 覆盖审计补出来的残差区域（已细化）。
     * @return 去重后的区域列表，保持“模型区域在前、残差在后”的顺序。
     */
    fun dedupe(modelRegions: List<LayoutRegion>, residuals: List<LayoutRegion>): List<LayoutRegion> {
        val out = ArrayList<LayoutRegion>(modelRegions.size + residuals.size)
        val keptLines = ArrayList<TextLine>()

        for (region in modelRegions) {
            val lines = uniqueLines(region.lines, keptLines)
            if (region.lines.isNotEmpty() && lines.isEmpty()) {
                // 整个模型区域的行都在别处保留过了：跨 tile 的重复框，整块不要。
                continue
            }
            out.add(region.copy(lines = lines))
        }

        for (region in residuals) {
            val lines = uniqueLines(region.lines, keptLines)
            if (region.lines.isNotEmpty() && lines.isEmpty()) continue
            val candidate = region.copy(lines = lines)
            if (isCoveredBlock(candidate, out)) continue
            out.add(candidate)
        }
        return out
    }

    private fun uniqueLines(lines: List<TextLine>, kept: MutableList<TextLine>): List<TextLine> {
        if (lines.isEmpty()) return lines
        val out = ArrayList<TextLine>(lines.size)
        for (line in lines) {
            if (kept.any { sameLine(it.band, line.band) }) continue
            kept.add(line)
            out.add(line)
        }
        return out
    }

    /**
     * 两个行带是否是“同一物理行”。
     *
     * 用**较矮行**做尺度：残差 ROI 外扩后切出的行常比模型区域的行略高或略低，
     * 拿较高行算比例会让这种正常的 ±20% 高度差漏过。
     */
    private fun sameLine(a: RectI, b: RectI): Boolean {
        val minH = min(a.height, b.height)
        if (minH <= 0) return false
        val yOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (yOverlap <= 0) return false
        if (yOverlap.toFloat() / minH < LINE_Y_OVERLAP_RATIO) return false
        val dy = abs((a.top + a.bottom) - (b.top + b.bottom)) / 2f
        if (dy > LINE_Y_CENTER_RATIO * minH) return false
        val xOverlap = min(a.right, b.right) - max(a.left, b.left)
        if (xOverlap <= 0) return false
        val narrower = min(a.width, b.width)
        if (narrower <= 0) return false
        return xOverlap.toFloat() / narrower >= LINE_X_OVERLAP_RATIO
    }

    /** 没有独立行的块，若已被某个非文字区域装下 ≥80%，就不再重复渲染。 */
    private fun isCoveredBlock(region: LayoutRegion, kept: List<LayoutRegion>): Boolean {
        if (region.lines.isNotEmpty()) return false
        if (region.source != RegionSource.RESIDUAL) return false
        return kept.any { other ->
            !other.isTextual && !other.bounds.isEmpty &&
                overlapOfBInA(other.bounds, region.bounds) >= BLOCK_CONTAINMENT_RATIO
        }
    }

    private companion object {
        /** 同一行在 y 方向至少重叠较矮行的 50%。 */
        const val LINE_Y_OVERLAP_RATIO = 0.5f

        /** 行中心距不得超过较矮行高的 50%（防“相邻两行被高度相近的残差带吞掉”）。 */
        const val LINE_Y_CENTER_RATIO = 0.5f

        /** 同一行在 x 方向至少重叠较窄行宽的 50%。 */
        const val LINE_X_OVERLAP_RATIO = 0.5f

        /** 残差块被已保留的非文字区域覆盖到这个比例就视为重复。 */
        const val BLOCK_CONTAINMENT_RATIO = 0.8f
    }
}
