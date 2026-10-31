package app.pictext.core

import app.pictext.core.model.Block
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.PageLayout
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染几何：`ReflowPlan` 的瓦片命中 / 图块点按 / 瓦片高度。
 *
 * 这三个函数是 `TileRenderer` 与 `ReflowPage` 唯一的共用几何契约，但它们在 v1 里从来没有
 * 自动化覆盖（渲染链路本身要 Android 位图，只有这几段可以纯 JVM 验证）。`itemsIntersecting`
 * 用 `dest.top` 二分，**依赖 items 按 dest.top 单调**——这条假设正是这里要钉住的东西。
 */
class RenderPlanGeometryTest {

    private fun textRow(top: Int, height: Int, width: Int = 500): RenderItem.TextRow =
        RenderItem.TextRow(
            src = RectI(0, top, 100, top + height),
            dest = RectI(0, top, width, top + height),
            lineIndex = 0,
        )

    private fun figure(left: Int, top: Int, right: Int, bottom: Int): RenderItem.Figure =
        RenderItem.Figure(
            src = RectI(left, top, right, bottom),
            dest = RectI(left, top, right, bottom),
            blockIndex = 0,
        )

    private fun planOf(items: List<RenderItem>, height: Int, tileHeight: Int = 1024): ReflowPlan =
        ReflowPlan(
            outputWidth = 500,
            height = height,
            scale = 1f,
            gain = 1.5f,
            mode = PlanMode.REFLOW,
            fallback = FallbackReason.NONE,
            items = items,
            tileHeightPx = tileHeight,
        )

    @Test
    fun `tile height and count handle a partial last tile`() {
        val plan = planOf(emptyList(), height = 1500)
        assertEquals(2, plan.tileCount)
        assertEquals(1024, plan.tileHeight(0))
        assertEquals(476, plan.tileHeight(1))
        assertEquals(1024, plan.tileTop(1))
    }

    @Test
    fun `zero height plan has no tiles`() {
        val plan = planOf(emptyList(), height = 0)
        assertEquals(0, plan.tileCount)
        assertEquals(0, plan.tileHeight(0))
    }

    @Test
    fun `items touching a tile boundary belong to the later tile only`() {
        val a = textRow(0, 50)
        val b = textRow(50, 50)
        val plan = planOf(listOf(a, b), height = 100)
        assertEquals("上边界是开的", listOf(b), plan.itemsIntersecting(RectI(0, 50, 500, 100)))
        assertEquals("下边界是闭的", listOf(a), plan.itemsIntersecting(RectI(0, 0, 500, 50)))
    }

    @Test
    fun `items intersecting matches a linear scan over random tiles`() {
        val items = (0 until 40).map { i -> textRow(i * 37, 37) }
        val plan = planOf(items, height = 40 * 37)
        val random = kotlin.random.Random(7)
        repeat(200) {
            val top = random.nextInt(0, plan.height)
            val h = random.nextInt(1, 600)
            val rect = RectI(0, top, 500, top + h)
            val expected = items.filter { !it.dest.intersect(rect).isEmpty }
            assertEquals("tile=$rect", expected, plan.itemsIntersecting(rect))
        }
    }

    @Test
    fun `items outside the output width still come back if they intersect the tile`() {
        // 越界的 item 由审计负责报，不应该让瓦片查询“看不见”它（否则渲染与审计口径不一致）。
        val wide = RenderItem.TextRow(src = RectI(0, 0, 10, 10), dest = RectI(0, 0, 900, 10), lineIndex = 0)
        val plan = planOf(listOf(wide), height = 10)
        assertEquals(listOf(wide), plan.itemsIntersecting(RectI(0, 0, 500, 10)))
    }

    @Test
    fun `figure hit test is exclusive on right and bottom`() {
        val fig = figure(10, 20, 110, 120)
        val plan = planOf(listOf(fig), height = 200)
        assertSame(fig, plan.figureAt(10, 20))
        assertSame(fig, plan.figureAt(109, 119))
        assertNull("右边是开区间", plan.figureAt(110, 60))
        assertNull("下边是开区间", plan.figureAt(60, 120))
        assertNull("文字行不参与点按", plan.figureAt(200, 200))
    }

    @Test
    fun `figure hit test works on a real plan`() {
        // 一个文字段落 + 一张图：图块在输出坐标里的位置由规划器算，点按应当命中它。
        val lines = listOf(
            app.pictext.core.model.TextLine(RectI(20, 10, 1000, 34)),
            app.pictext.core.model.TextLine(RectI(20, 50, 1000, 74)),
        )
        val layout = PageLayout(
            srcWidth = 1080, srcHeight = 400, analysisScale = 1f,
            blocks = listOf(Block.Paragraph(lines), Block.Figure(RectI(100, 120, 500, 300))),
            textLeft = 20, textRight = 1000, splitX = 500, cleanRatio = 1f,
            medianLineHeight = 24f, medianGap = 16f, medianPitch = 40f,
            isDark = false, backgroundColor = 0xFFFFFFFF.toInt(), diagnostics = Diagnostics(),
        )
        // 这一页按宽度算出的收益不足 1.25，自动模式会回退原图；用 forceReflow 拿到真正的计划。
        val plan = ReflowPlanner().plan(layout, ReflowOptions(outputWidth = 1080, forceReflow = true))
        assertEquals(PlanMode.NO_SPLIT, plan.mode)
        val fig = plan.items.filterIsInstance<RenderItem.Figure>().single()
        assertSame(fig, plan.figureAt(fig.dest.left + 1, fig.dest.top + 1))
        assertNull(plan.figureAt(fig.dest.left - 1, fig.dest.top + 1))
        assertTrue(plan.itemsIntersecting(RectI(0, fig.dest.top, 1080, fig.dest.bottom)).contains(fig))
    }
}
