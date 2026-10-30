package app.pictext.core

import app.pictext.core.model.Block
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.RenderItem
import app.pictext.core.model.TextLine
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowPlannerTest {

    private val planner = ReflowPlanner()

    private fun line(
        y0: Int,
        y1: Int,
        left: Int = 120,
        right: Int = 1080,
        cut: Int = 600,
        split: Boolean = true,
        centered: Boolean = false,
        role: LineRole = LineRole.BODY,
        cuts: List<Int> = emptyList(),
    ) = TextLine(
        RectI(left, y0, right, y1),
        cutX = cut,
        split = split,
        centered = centered,
        role = role,
        cuts = cuts,
    )

    private fun layout(
        blocks: List<Block>,
        srcW: Int = 1080,
        srcH: Int = 400,
        textLeft: Int = 120,
        textRight: Int = 1080,
        medH: Float = 24f,
        medGap: Float = 12f,
        notes: List<String> = emptyList(),
        analysisScale: Float = 1f,
    ) = PageLayout(
        srcWidth = srcW,
        srcHeight = srcH,
        analysisScale = analysisScale,
        blocks = blocks,
        textLeft = textLeft,
        textRight = textRight,
        splitX = 600,
        cleanRatio = if (notes.contains("weak_cut")) 0.5f else 1f,
        medianLineHeight = medH,
        medianGap = medGap,
        medianPitch = medH + medGap,
        isDark = false,
        backgroundColor = -1,
        diagnostics = Diagnostics(cleanRatio = 1f, notes = notes),
    )

    @Test
    fun `split rows are emitted in reading order`() {
        val lines = listOf(line(20, 44), line(70, 94), line(120, 144))
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines))),
            ReflowOptions(outputWidth = 1080),
        )
        assertEquals(PlanMode.REFLOW, plan.mode)
        assertEquals(6, plan.items.size)
        val indexes = plan.items.map { (it as RenderItem.TextRow).lineIndex }
        assertEquals(listOf(0, 0, 1, 1, 2, 2), indexes)
        for (i in 1 until plan.items.size) {
            assertTrue(plan.items[i].dest.top > plan.items[i - 1].dest.top)
        }
    }

    @Test
    fun `scale and gain match the formula`() {
        val lines = listOf(
            line(20, 44, left = 120, right = 1080, cut = 600),
            line(70, 94, left = 120, right = 1080, cut = 600),
        )
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines)), medH = 24f),
            ReflowOptions(outputWidth = 1080, targetEm = 44),
        )
        assertEquals(1.99f, plan.scale, 0.03f)
        assertEquals(1.99f, plan.gain, 0.03f)
        assertEquals(PlanMode.REFLOW, plan.mode)
    }

    @Test
    fun `narrow source falls back to original`() {
        val lines = listOf(
            line(20, 60, left = 20, right = 580, cut = 300),
            line(80, 120, left = 20, right = 580, cut = 300),
        )
        val plan = planner.plan(
            layout(
                listOf(Block.Paragraph(lines)),
                srcW = 600,
                textLeft = 20,
                textRight = 580,
                medH = 40f,
            ),
            ReflowOptions(outputWidth = 1080, targetEm = 44),
        )
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertEquals(FallbackReason.GAIN_TOO_SMALL, plan.fallback)
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `paragraph gap is larger than line gap`() {
        val lines = listOf(
            line(20, 44),
            line(70, 94),
            line(120, 144),
        )
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines)), medGap = 15f),
            ReflowOptions(outputWidth = 1080),
        )
        val normalGap = plan.items[1].dest.top - plan.items[0].dest.bottom
        val secondParagraphTop = plan.items[4].dest.top
        val paragraphGap = secondParagraphTop - plan.items[3].dest.bottom
        assertTrue("normal=$normalGap paragraph=$paragraphGap", paragraphGap > normalGap * 1.5f)
    }

    @Test
    fun `figures are centered and never cropped`() {
        val lines = listOf(line(20, 44), line(70, 94))
        val fig = Block.Figure(RectI(260, 120, 820, 320))
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines), fig)),
            ReflowOptions(outputWidth = 1080),
        )
        val item = plan.items.filterIsInstance<RenderItem.Figure>().single()
        val contentW = 1080 - 2 * 19
        assertEquals(contentW, item.dest.width)
        assertTrue(item.dest.left >= 0 && item.dest.right <= plan.outputWidth)
        assertEquals(plan.outputWidth / 2f, (item.dest.left + item.dest.width / 2).toFloat(), 2f)
    }

    @Test
    fun `overwide figure is scaled down to column`() {
        val lines = listOf(line(20, 44), line(70, 94))
        val fig = Block.Figure(RectI(0, 120, 1200, 320))
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines), fig)),
            ReflowOptions(outputWidth = 1080),
        )
        val item = plan.items.filterIsInstance<RenderItem.Figure>().single()
        assertEquals(1080 - 2 * 19, item.dest.width)
        assertEquals(RectI(0, 120, 1080, 320), item.src)
    }

    @Test
    fun `centered short title is centered`() {
        val lines = listOf(
            line(20, 44),
            line(70, 94),
            line(120, 144, left = 440, right = 640, cut = -1, split = false, centered = true),
        )
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines))),
            ReflowOptions(outputWidth = 1080),
        )
        val title = plan.items.last() as RenderItem.TextRow
        assertEquals(plan.outputWidth / 2f, (title.dest.left + title.dest.width / 2).toFloat(), 2f)
    }

    @Test
    fun `all source rects stay inside the source image`() {
        val lines = listOf(line(20, 44), line(70, 94), line(120, 144))
        val fig = Block.Figure(RectI(-10, 160, 1200, 400))
        val plan = planner.plan(
            layout(listOf(Block.Paragraph(lines), fig), srcW = 1080, srcH = 400),
            ReflowOptions(outputWidth = 1080),
        )
        for (item in plan.items) {
            assertTrue(item.src.left >= 0 && item.src.top >= 0)
            assertTrue(item.src.right <= 1080 && item.src.bottom <= 400)
            assertTrue(!item.src.isEmpty)
        }
    }

    @Test
    fun `weak cut keeps content by disabling splitting`() {
        val lines = listOf(
            line(20, 44, left = 10, right = 1000, split = false),
            line(70, 94, left = 10, right = 1000, split = false),
        )
        val plan = planner.plan(
            layout(
                listOf(Block.Paragraph(lines)),
                srcW = 2160,
                textLeft = 10,
                textRight = 1000,
                notes = listOf("weak_cut"),
                analysisScale = 0.5f,
            ),
            ReflowOptions(outputWidth = 1080),
        )
        assertEquals(PlanMode.NO_SPLIT, plan.mode)
        assertEquals(FallbackReason.WEAK_CUT, plan.fallback)
        assertTrue(plan.items.isNotEmpty())
        assertTrue(plan.items.all { it.dest.right <= plan.outputWidth })
    }

    @Test
    fun `x4 turns a no-split page into a real reflow`() {
        // ×2 时两条 500px 行都放得下 → NO_SPLIT（整页放大）。×4 字号翻倍后装不下，
        // 第二次重排会真的切开，模式必须跟着变成 REFLOW，不能还挂着“整页不切”。
        val lines = listOf(
            line(20, 44, left = 120, right = 620, cut = -1, split = false, cuts = listOf(370)),
            line(70, 94, left = 120, right = 620, cut = -1, split = false, cuts = listOf(370)),
        )
        val page = layout(listOf(Block.Paragraph(lines)), textRight = 620)
        val x2 = planner.plan(page, ReflowOptions(outputWidth = 1080, forceReflow = true))
        assertEquals(PlanMode.NO_SPLIT, x2.mode)

        val x4 = planner.plan(page, x4Options())
        assertEquals(PlanMode.REFLOW, x4.mode)
        assertEquals(4, x4.items.filterIsInstance<RenderItem.TextRow>().size)
        assertTrue(x4.bodyScale > x2.bodyScale * 1.8f)
    }

    @Test
    fun `no text falls back with reason`() {
        val plan = planner.plan(
            layout(emptyList(), notes = listOf("no_text")),
            ReflowOptions(outputWidth = 1080),
        )
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertEquals(FallbackReason.NO_TEXT, plan.fallback)
    }

    // ---- ×4 二次重排 --------------------------------------------------------------

    private fun x4Options() = ReflowOptions(
        outputWidth = 1080,
        forceReflow = true,
        scaleMultiplier = 2f,
        maxSegments = 4,
        maxScale = 6f,
    )

    @Test
    fun `x4 re-splits every wide row into four segments at one scale`() {
        // 三条满宽行：×2 切两半，×4 必须切成四段，且每段仍是同一个 bodyScale
        // （不能像旧实现那样把放不下的段压回列宽，导致同页两三种字号）。
        val cuts = listOf(360, 600, 840)
        val lines = (0 until 3).map { i ->
            line(20 + i * 50, 44 + i * 50, left = 120, right = 1080, cuts = cuts)
        }
        val plan = planner.plan(layout(listOf(Block.Paragraph(lines))), x4Options())

        assertEquals(PlanMode.REFLOW, plan.mode)
        val body = plan.items.filterIsInstance<RenderItem.TextRow>().filter { it.role == LineRole.BODY }
        assertEquals("三条行各切 4 段", 12, body.size)
        for (row in body) {
            val scale = row.dest.width.toFloat() / row.src.width
            assertEquals("同页字号必须一致: $row", plan.bodyScale, scale, 0.02f)
            assertTrue("段宽溢出: $row", row.dest.right <= plan.outputWidth)
            assertTrue("段宽超出列宽: $row", row.dest.width <= 1080)
        }
    }

    @Test
    fun `x4 splits a short row that already fit at x2`() {
        // 400px 的短行 ×2 不需要切；×4 字号翻倍后装不下，必须挑一条缝对半，
        // 而不是把它压回 ×2 的字号。
        val lines = listOf(
            line(20, 44, left = 120, right = 1080, cuts = listOf(360, 600, 840)),
            line(70, 94, left = 120, right = 520, cut = -1, split = false, cuts = listOf(320)),
        )
        val plan = planner.plan(layout(listOf(Block.Paragraph(lines))), x4Options())

        val second = plan.items.filterIsInstance<RenderItem.TextRow>().filter { it.src.top == 69 }
        assertEquals("短行应被切成两段: $second", 2, second.size)
        for (row in second) {
            val scale = row.dest.width.toFloat() / row.src.width
            assertEquals(plan.bodyScale, scale, 0.02f)
        }
    }

    @Test
    fun `x4 without safe cuts keeps the row whole instead of cutting through ink`() {
        val lines = listOf(
            line(20, 44, left = 120, right = 1080, cuts = listOf(360, 600, 840)),
            line(70, 94, left = 120, right = 500, cut = -1, split = false, cuts = emptyList()),
        )
        val plan = planner.plan(layout(listOf(Block.Paragraph(lines))), x4Options())

        val rows = plan.items.filterIsInstance<RenderItem.TextRow>()
        assertEquals("宽行切 4 段 + 短行整行 = 5 行", 5, rows.size)
        val orphan = rows.filter { it.src.top == 69 }.single()
        assertTrue("必须压回列宽而不是溢出", orphan.dest.right <= plan.outputWidth)
        assertTrue("没有安全缝就不许切", orphan.src.width >= 370)
    }
}
