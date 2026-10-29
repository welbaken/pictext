package app.pictext.core

import app.pictext.core.model.Block
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.model.TextLine
import app.pictext.core.plan.PlanAuditor
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-4 统一缩放 + P0-5 诚实回退 + P0-0 计划审计。
 */
class PlannerRoleTest {

    private val planner = ReflowPlanner()

    private fun line(
        y0: Int,
        y1: Int,
        left: Int = 120,
        right: Int = 1080,
        role: LineRole = LineRole.BODY,
        cut: Int = 600,
        split: Boolean = true,
        centered: Boolean = false,
    ) = TextLine(RectI(left, y0, right, y1), cutX = cut, split = split, centered = centered, role = role)

    private fun layout(
        blocks: List<Block>,
        srcW: Int = 1080,
        srcH: Int = 400,
        textLeft: Int = 120,
        textRight: Int = 1080,
        medH: Float = 24f,
        medGap: Float = 12f,
        notes: List<String> = emptyList(),
    ) = PageLayout(
        srcWidth = srcW,
        srcHeight = srcH,
        analysisScale = 1f,
        blocks = blocks,
        textLeft = textLeft,
        textRight = textRight,
        splitX = 600,
        cleanRatio = if (notes.contains("weak_cut")) 0.4f else 1f,
        medianLineHeight = medH,
        medianGap = medGap,
        medianPitch = medH + medGap,
        isDark = false,
        backgroundColor = -1,
        diagnostics = Diagnostics(cleanRatio = 1f, notes = notes),
    )

    @Test
    fun `all body rows share one scale`() {
        val lines = listOf(line(20, 44), line(70, 94), line(120, 144, left = 120, right = 900))
        val plan = planner.plan(layout(listOf(Block.Paragraph(lines))), ReflowOptions(outputWidth = 1080))
        val rows = plan.items.filterIsInstance<RenderItem.TextRow>().filter { it.role == LineRole.BODY }
        assertTrue(rows.isNotEmpty())
        val scales = rows.map { it.dest.width.toFloat() / it.src.width }
        val max = scales.max()
        val min = scales.min()
        println("scales=$scales bodyScale=${plan.bodyScale}")
        assertTrue("bodyScale=${plan.bodyScale} min=$min max=$max", max - min <= 0.05f * plan.bodyScale)
    }

    @Test
    fun `ui row is never larger than 1_5x`() {
        val lines = listOf(
            line(20, 44),
            line(70, 94),
            line(120, 160, left = 200, right = 320, role = LineRole.UI, cut = -1, split = false),
        )
        val plan = planner.plan(layout(listOf(Block.Paragraph(lines))), ReflowOptions(outputWidth = 1080))
        val ui = plan.items.filterIsInstance<RenderItem.TextRow>().single { it.role == LineRole.UI }
        val scale = ui.dest.width.toFloat() / ui.src.width
        println("uiScale=$scale")
        assertTrue("uiScale=$scale", scale <= 1.5f + 0.01f)
    }

    @Test
    fun `heading is at least as tall as body`() {
        val lines = listOf(
            line(10, 44, left = 100, right = 1080, role = LineRole.HEADING, cut = 590),
            line(60, 82, left = 100, right = 1080),
            line(100, 122, left = 100, right = 1080),
        )
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines)), medH = 22f),
            ReflowOptions(outputWidth = 1080),
        )
        val rows = plan.items.filterIsInstance<RenderItem.TextRow>()
        val headingH = rows.filter { it.role == LineRole.HEADING }.map { it.dest.height }.maxOrNull() ?: 0
        val bodyH = rows.filter { it.role == LineRole.BODY }.map { it.dest.height }.maxOrNull() ?: 0
        println("heading=$headingH body=$bodyH scale=${plan.scale}")
        assertTrue("heading=$headingH body=$bodyH", headingH >= bodyH)
    }

    @Test
    fun `weak cut with no gain falls back to original`() {
        // 1080 宽的页面按 1080 输出：只放大约 3%，低于 minGain。
        val lines = listOf(line(20, 44, left = 36, right = 1048, split = false))
        val plan = planner.plan(
            layout(
                listOf(Block.Paragraph(lines)),
                srcW = 1080,
                textLeft = 36,
                textRight = 1048,
                medH = 18f,
                notes = listOf("weak_cut"),
            ),
            ReflowOptions(outputWidth = 1080),
        )
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertEquals(FallbackReason.WEAK_CUT, plan.fallback)
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `weak cut with real gain keeps the enlarged page`() {
        val lines = listOf(line(20, 44, left = 10, right = 1000, split = false))
        val plan = planner.plan(
            layout(
                listOf(Block.Paragraph(lines)),
                srcW = 2160,
                textLeft = 10,
                textRight = 1000,
                notes = listOf("weak_cut"),
            ),
            ReflowOptions(outputWidth = 1080),
        )
        assertEquals(PlanMode.NO_SPLIT, plan.mode)
        assertEquals(FallbackReason.WEAK_CUT, plan.fallback)
        assertTrue(plan.items.isNotEmpty())
    }

    @Test
    fun `multi column page falls back to original`() {
        val lines = listOf(line(20, 44), line(70, 94))
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines)), notes = listOf("multi_column")),
            ReflowOptions(outputWidth = 1080),
        )
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertEquals(FallbackReason.MULTI_COLUMN, plan.fallback)
    }

    @Test
    fun `figure kinds use different scale ceilings`() {
        val lines = listOf(line(20, 44), line(70, 94))
        val photo = Block.Figure(RectI(100, 120, 400, 260), FigureKind.PHOTO)
        val ui = Block.Figure(RectI(100, 300, 140, 340), FigureKind.UI)
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines), photo, ui)),
            ReflowOptions(outputWidth = 1080),
        )
        val figures = plan.items.filterIsInstance<RenderItem.Figure>()
        val photoScale = figures.single { it.kind == FigureKind.PHOTO }.dest.width.toFloat() / 300f
        val uiScale = figures.single { it.kind == FigureKind.UI }.dest.width.toFloat() / 40f
        println("photo=$photoScale ui=$uiScale")
        assertTrue("photo=$photoScale", photoScale > 1.5f)
        assertTrue("ui=$uiScale", uiScale <= 1.5f + 0.01f)
    }

    @Test
    fun `audit is clean on a normal plan`() {
        val lines = listOf(line(20, 44), line(70, 94), line(120, 144))
        val page = layout(listOf(Block.Paragraph(lines)))
        val plan = planner.plan(page, ReflowOptions(outputWidth = 1080))
        val audit = PlanAuditor.audit(page, plan)
        println(audit.summary())
        assertTrue(audit.issues.toString(), audit.ok)
        assertEquals(0, audit.blocksWithoutItem)
    }

    @Test
    fun `audit reports a dropped block`() {
        val lines = listOf(line(20, 44), line(70, 94))
        val page = layout(listOf(Block.Paragraph(lines), Block.Figure(RectI(100, 200, 600, 380))))
        val plan = planner.plan(page, ReflowOptions(outputWidth = 1080))
        val withoutFigure = plan.copy(items = plan.items.filter { it !is RenderItem.Figure })
        val audit = PlanAuditor.audit(page, withoutFigure)
        println(audit.summary())
        assertFalse(audit.ok)
        assertEquals(1, audit.blocksWithoutItem)
    }

    @Test
    fun `audit reports inconsistent body scale`() {
        val lines = listOf(line(20, 44), line(70, 94))
        val page = layout(listOf(Block.Paragraph(lines)))
        val plan = planner.plan(page, ReflowOptions(outputWidth = 1080))
        val shrunk = plan.copy(
            items = plan.items.map { item ->
                if (item is RenderItem.TextRow && item.lineIndex == 0) {
                    item.copy(dest = RectI(item.dest.left, item.dest.top, item.dest.left + item.src.width, item.dest.bottom))
                } else {
                    item
                }
            },
        )
        val audit = PlanAuditor.audit(page, shrunk)
        println(audit.summary())
        assertFalse(audit.ok)
        assertTrue(audit.issues.any { it.startsWith("body_scale_inconsistent") })
    }

    @Test
    fun `plan exposes body scale for audit`() {
        val lines = listOf(line(20, 44), line(70, 94))
        val plan: ReflowPlan = planner.plan(layout(listOf(Block.Paragraph(lines))), ReflowOptions(outputWidth = 1080))
        assertEquals(plan.scale, plan.bodyScale, 0.0001f)
        assertTrue(plan.bodyScale > 1f)
    }
}
