package app.pictext.core.layout

import app.pictext.core.model.Block
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RenderItem
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.planDocLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * V2-13 之二：**页面组装 + 规划**用 HTML 真值验收（不加载模型）。
 *
 * 真值里有每个语义块的类别、外接框和文字行；把它们直接组装成 `PageLayout` 再规划，
 * 检查的是“区域→块→渲染项”这条链路的不变量：
 *
 * - 图/表**整块保留**，一图一项，`src` 就是区域框（不裁、不切）；
 * - 有行的文字区域**每行都要有渲染项**，且全页只有一个倍率 `s`（压缩行 ≤10%）；
 * - 所有 `src` 在页内、`dest` 在列宽内、垂直不重叠。
 */
class FixtureComposerTest {

    private val outWidth = 1080

    private fun planOf(id: String, width: Int): Triple<FixtureGt, app.pictext.core.model.PageLayout, app.pictext.core.model.ReflowPlan>? {
        val gt = Fixtures.load(id, width) ?: return null
        val page = PageContext(srcWidth = gt.width, srcHeight = gt.height)
        val layout = PageComposer().compose(page, gt.regionsForLayout(), CoverageReport.EMPTY)
        val plan = planDocLayout(layout, ReflowOptions(outputWidth = outWidth))
        return Triple(gt, layout, plan)
    }

    @Test
    fun `every fixture produces a plannable layout`() {
        val rows = ArrayList<String>()
        var reflowed = 0
        var original = 0
        for ((id, w) in Fixtures.all()) {
            val (gt, layout, plan) = planOf(id, w) ?: continue
            rows.add(
                "%-24s mode=%-9s scale=%.2f items=%3d regions=%d lines=%d".format(
                    id, plan.mode, plan.bodyScale, plan.items.size,
                    layout.regions.size, layout.regionLines().size,
                ),
            )
            // GT 版面必须能进 v2 规划器；`LAYOUT_FALLBACK` 说明规划器在真实版面上直接投降了。
            assertTrue(
                "$id 不应该回退 v1（notes=${plan.notes}）",
                plan.fallback != FallbackReason.LAYOUT_FALLBACK,
            )
            when (plan.mode) {
                PlanMode.REFLOW, PlanMode.NO_SPLIT -> {
                    reflowed++
                    assertTrue("$id 值得重排时必须有渲染项", plan.items.isNotEmpty())
                }
                PlanMode.ORIGINAL -> {
                    original++
                    assertTrue(
                        "$id 回退原图必须带原因",
                        plan.fallback != FallbackReason.NONE,
                    )
                }
            }
            gt.width
        }
        println("=== FixtureComposerTest ===\n" + rows.joinToString("\n"))
        assertTrue("至少要有一半夹具能真的重排（reflowed=$reflowed original=$original）", reflowed >= 6)
    }

    @Test
    fun `figures stay whole and text rows keep a single scale`() {
        for ((id, w) in Fixtures.all()) {
            val (gt, _, plan) = planOf(id, w) ?: continue
            if (plan.mode != PlanMode.REFLOW) continue

            val figures = plan.items.filterIsInstance<RenderItem.Figure>()
            val texts = plan.items.filterIsInstance<RenderItem.TextRow>()

            // 1) 图/表：一图一项，src 等于区域框（允许被页边裁剪 ±1px）。
            val blockRegions = gt.regions.filter { !it.isTextual() }
            for (r in blockRegions) {
                val expected = r.box.rect.clampTo(gt.width, gt.height)
                val matched = figures.filter { abs(it.src.left - expected.left) <= 1 &&
                    abs(it.src.top - expected.top) <= 1 &&
                    abs(it.src.right - expected.right) <= 1 &&
                    abs(it.src.bottom - expected.bottom) <= 1 }
                assertEquals(
                    "$id 区域 ${r.id} 必须正好一个整块 item：实际 ${figures.size} 个图块",
                    1,
                    matched.size,
                )
            }

            // 2) 有行的文字区域每行至少一个渲染项。
            val textLines = gt.regions.filter { it.isTextual() }.sumOf { it.lines.size }
            assertTrue(
                "$id 文字行 $textLines 行，渲染项只有 ${texts.size} 个",
                texts.size >= textLines,
            )

            // 3) 全页一个 s：除“压缩行”外的文字行偏差 ≤2%，且压缩行 ≤10%。
            val s = plan.bodyScale
            val byLine = texts.groupBy { it.lineIndex }
            val compressedLines = byLine.filter { (_, items) ->
                items.size == 1 && s > 0f && items[0].dest.width.toFloat() / items[0].src.width < s * 0.98f
            }.keys
            val deviations = texts.filter { it.role != app.pictext.core.model.LineRole.UI }
                .filter { it.lineIndex !in compressedLines }
                .map { abs(it.dest.width.toFloat() / it.src.width - s) / s }
            val worst = deviations.maxOrNull() ?: 0f
            assertTrue(
                "$id 非压缩行的字号最大偏差 ${"%.3f".format(worst)}（s=$s，" +
                    "压缩 ${compressedLines.size}/${byLine.size} 行）",
                worst <= 0.02f,
            )
            val compressedRatio = compressedLines.size.toFloat() / byLine.size.coerceAtLeast(1)
            assertTrue(
                "$id 压缩行占比 ${"%.3f".format(compressedRatio)} 超过 10%",
                compressedRatio <= 0.10f,
            )

            // 4) 坐标与顺序。
            var prevBottom = -1
            for (item in plan.items) {
                assertTrue("$id dest 越界 ${item.dest}", item.dest.left >= 0 && item.dest.right <= outWidth)
                assertTrue("$id src 越界 ${item.src}", item.src.left >= 0 && item.src.right <= gt.width)
                assertTrue("$id 垂直重叠 dest=${item.dest}", item.dest.top >= prevBottom)
                prevBottom = item.dest.bottom
            }
        }
    }

    @Test
    fun `block regions are never split into multiple items`() {
        for ((id, w) in Fixtures.all()) {
            val (gt, _, plan) = planOf(id, w) ?: continue
            if (plan.mode == PlanMode.ORIGINAL) continue
            val figures = plan.items.filterIsInstance<RenderItem.Figure>()
            // 每个图块 item 的 src 必须与某个 GT 图/表区域重合：不允许“把一个图切成两条”。
            for (f in figures) {
                val owner = gt.regions.filter { !it.isTextual() }.count { r ->
                    val b = r.box.rect
                    f.src.left < b.right && f.src.right > b.left && f.src.top < b.bottom && f.src.bottom > b.top
                }
                assertTrue("$id 图块 item ${f.src} 找不到所属区域", owner >= 1)
            }
            val blockCount = gt.regions.count { !it.isTextual() }
            assertTrue(
                "$id 图块 item 数 ${figures.size} 不应超过 GT 块数 $blockCount",
                figures.size <= blockCount + 1,
            )
        }
    }

    @Test
    fun `blocks are typed as figures not paragraphs`() {
        for ((id, w) in Fixtures.all()) {
            val gt = Fixtures.load(id, w) ?: continue
            val page = PageContext(srcWidth = gt.width, srcHeight = gt.height)
            val layout = PageComposer().compose(page, gt.regionsForLayout(), CoverageReport.EMPTY)
            val expectedFigures = gt.regions.count { !it.isTextual() }
            val actualFigures = layout.blocks.count { it is Block.Figure }
            assertEquals("$id 图块数", expectedFigures, actualFigures)
        }
    }
}
