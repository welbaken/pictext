package app.pictext.core.plan

import app.pictext.core.model.Block
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import kotlin.math.abs

/**
 * 计划审计报告：把“肉眼看不出来”的结构问题变成可断言的数字。
 *
 * 对应 docs/06 P0-0：每个块都要有渲染项、`dest` 不越界、顺序不倒挂、
 * BODY 缩放一致、UI 不过度放大、标题不比正文小。
 */
data class PlanAuditReport(
    val issues: List<String>,
    val mode: PlanMode,
    val blockCount: Int,
    val blocksWithoutItem: Int,
    val itemsOutOfBounds: Int,
    val nonMonotonic: Int,
    val srcOutOfBounds: Int,
    val bodyScaleDeviation: Float,
    val uiMaxScale: Float,
    val headingHeightRatio: Float,
    val roleCounts: Map<LineRole, Int>,
    val figureKindCounts: Map<FigureKind, Int>,
) {
    val ok: Boolean get() = issues.isEmpty()

    fun summary(): String = buildString {
        append("mode=$mode blocks=$blockCount dropped=$blocksWithoutItem ")
        append("outOfBounds=$itemsOutOfBounds nonMonotonic=$nonMonotonic ")
        append("bodyDev=${"%.3f".format(bodyScaleDeviation)} uiMax=${"%.2f".format(uiMaxScale)} ")
        append("headingRatio=${"%.2f".format(headingHeightRatio)} roles=$roleCounts figures=$figureKindCounts")
        if (issues.isNotEmpty()) append(" ISSUES=$issues")
    }
}

object PlanAuditor {

    /** BODY 行允许的缩放偏差（±5%，对应 docs/06 §3.2）。 */
    const val BODY_SCALE_TOLERANCE = 0.05f

    /** UI 行/图的放大上限（D-C）。 */
    const val UI_SCALE_CEILING = 1.6f

    fun audit(layout: PageLayout, plan: ReflowPlan): PlanAuditReport {
        val issues = ArrayList<String>()
        val blocks = layout.blocks
        if (plan.mode == PlanMode.ORIGINAL) {
            return PlanAuditReport(
                issues = issues,
                mode = plan.mode,
                blockCount = blocks.size,
                blocksWithoutItem = 0,
                itemsOutOfBounds = 0,
                nonMonotonic = 0,
                srcOutOfBounds = 0,
                bodyScaleDeviation = 0f,
                uiMaxScale = 0f,
                headingHeightRatio = 0f,
                roleCounts = layout.roleCounts(),
                figureKindCounts = layout.figureKindCounts(),
            )
        }

        // 1) 每个块都要有渲染项。
        val sourceBounds = blocks.map { layout.toSource(it.bounds) }
        val covered = BooleanArray(blocks.size)
        for (item in plan.items) {
            for (i in sourceBounds.indices) {
                if (covered[i]) continue
                if (!item.src.intersect(sourceBounds[i]).isEmpty) covered[i] = true
            }
        }
        var blocksWithoutItem = 0
        for (i in covered.indices) {
            if (!covered[i]) {
                blocksWithoutItem++
                issues.add("block_dropped(${blocks[i].bounds})")
            }
        }

        // 2) dest/src 越界与顺序。
        var outOfBounds = 0
        var srcOutOfBounds = 0
        var nonMonotonic = 0
        var prevTop = Int.MIN_VALUE
        for (item in plan.items) {
            val d = item.dest
            if (d.left < 0 || d.top < 0 || d.right > plan.outputWidth || d.bottom > maxOf(plan.height, d.bottom)) {
                outOfBounds++
            }
            if (d.top < prevTop) nonMonotonic++
            prevTop = d.top
            val s = item.src
            if (s.left < 0 || s.top < 0 || s.right > layout.srcWidth || s.bottom > layout.srcHeight) {
                srcOutOfBounds++
            }
        }
        if (outOfBounds > 0) issues.add("items_out_of_bounds($outOfBounds)")
        if (nonMonotonic > 0) issues.add("items_out_of_order($nonMonotonic)")
        if (srcOutOfBounds > 0) issues.add("src_out_of_bounds($srcOutOfBounds)")

        // 3) 角色缩放一致性。
        var maxBodyDeviation = 0f
        var uiMax = 0f
        val bodyHeights = ArrayList<Int>()
        val headingHeights = ArrayList<Int>()
        for (item in plan.items) {
            if (item !is RenderItem.TextRow) continue
            val scale = if (item.src.width > 0) item.dest.width.toFloat() / item.src.width else 1f
            when (item.role) {
                LineRole.BODY -> {
                    val dev = if (plan.bodyScale > 0f) abs(scale - plan.bodyScale) / plan.bodyScale else 0f
                    if (dev > maxBodyDeviation) maxBodyDeviation = dev
                    bodyHeights.add(item.dest.height)
                }
                LineRole.HEADING -> headingHeights.add(item.dest.height)
                LineRole.UI -> if (scale > uiMax) uiMax = scale
                LineRole.CAPTION -> Unit
            }
        }
        // 用户手动强制（×2/×4）时，个别没有安全切点的长行只能压回较小倍率，
        // 这是预期行为：数值照实记录，但不判失败（自动模式仍然严格）。
        val forced = "forced_scale" in plan.notes
        if (maxBodyDeviation > BODY_SCALE_TOLERANCE && !forced) {
            issues.add("body_scale_inconsistent(${"%.3f".format(maxBodyDeviation)})")
        }
        if (uiMax > UI_SCALE_CEILING) issues.add("ui_row_too_large(${"%.2f".format(uiMax)})")

        var headingRatio = 0f
        if (headingHeights.isNotEmpty() && bodyHeights.isNotEmpty()) {
            val bodyMedian = bodyHeights.sorted()[bodyHeights.size / 2].toFloat()
            val headingMedian = headingHeights.sorted()[headingHeights.size / 2].toFloat()
            headingRatio = if (bodyMedian > 0f) headingMedian / bodyMedian else 0f
            if (headingRatio < 0.95f) issues.add("heading_smaller_than_body(${"%.2f".format(headingRatio)})")
        }

        return PlanAuditReport(
            issues = issues,
            mode = plan.mode,
            blockCount = blocks.size,
            blocksWithoutItem = blocksWithoutItem,
            itemsOutOfBounds = outOfBounds,
            nonMonotonic = nonMonotonic,
            srcOutOfBounds = srcOutOfBounds,
            bodyScaleDeviation = maxBodyDeviation,
            uiMaxScale = uiMax,
            headingHeightRatio = headingRatio,
            roleCounts = layout.roleCounts(),
            figureKindCounts = figureKindCounts(blocks),
        )
    }

    private fun figureKindCounts(blocks: List<Block>): Map<FigureKind, Int> =
        blocks.filterIsInstance<Block.Figure>().groupingBy { it.kind }.eachCount()
}
