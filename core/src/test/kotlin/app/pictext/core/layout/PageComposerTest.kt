package app.pictext.core.layout

import app.pictext.core.model.Block
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LayoutStrategy
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RenderItem
import app.pictext.core.model.TextLine
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2-24：页面组装（S 坐标、恒等换算、块映射、统计量）。 */
class PageComposerTest {

    private val composer = PageComposer()
    private val page = PageContext(srcWidth = 1080, srcHeight = 2400)

    private fun line(y: Int, h: Int, x0: Int = 40, x1: Int = 1000, role: LineRole = LineRole.BODY) =
        TextLine(RectI(x0, y, x1, y + h), role = role)

    @Test
    fun `regions are composed in reading order and keep their lines`() {
        val title = LayoutRegion(
            id = 0, kind = RegionKind.TITLE, order = 0, bounds = RectI(40, 0, 1000, 60),
            lines = listOf(line(0, 50, 40, 700, LineRole.HEADING)),
        )
        val body = LayoutRegion(
            id = 1, kind = RegionKind.TEXT, order = 1, bounds = RectI(40, 80, 1000, 200),
            lines = listOf(line(80, 24), line(110, 24), line(140, 24)),
        )
        val fig = LayoutRegion(
            id = 2, kind = RegionKind.FIGURE, order = 2, bounds = RectI(40, 240, 1040, 600),
        )
        // 故意打乱输入顺序：composer 必须按 order 排。
        val layout = composer.compose(page, listOf(fig, body, title), CoverageReport.EMPTY)

        assertEquals(LayoutStrategy.DOC_LAYOUT, layout.strategy)
        assertEquals(1f, layout.analysisScale, 0f)
        assertEquals(-1, layout.splitX)
        assertEquals(1f, layout.cleanRatio, 0f)
        assertEquals(3, layout.regions.size)
        assertEquals(listOf(0, 1, 2), layout.regions.map { it.order })
        assertEquals(3, layout.blocks.size)
        assertTrue(layout.blocks[0] is Block.Paragraph)
        assertEquals(1, (layout.blocks[0] as Block.Paragraph).lines.size)
        assertTrue(layout.blocks[1] is Block.Paragraph)
        assertEquals(3, (layout.blocks[1] as Block.Paragraph).lines.size)
        assertTrue(layout.blocks[2] is Block.Figure)
        assertEquals(4, layout.regionLines().size)
    }

    @Test
    fun `toSource is the identity in the S coordinate system`() {
        val body = LayoutRegion(
            id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(40, 80, 1000, 200),
            lines = listOf(line(80, 24)),
        )
        val layout = composer.compose(page, listOf(body), CoverageReport.EMPTY)
        val r = RectI(41, 83, 999, 201)
        assertEquals(r, layout.toSource(r))
    }

    @Test
    fun `metrics come from body lines and line gaps`() {
        val body = LayoutRegion(
            id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(40, 80, 1000, 200),
            lines = listOf(line(80, 24), line(112, 24), line(144, 26)),
        )
        val layout = composer.compose(page, listOf(body), CoverageReport.EMPTY)
        assertEquals(24f, layout.medianLineHeight, 0f)
        assertEquals(8f, layout.medianGap, 0f)      // 112 - (80+24)
        assertEquals(32f, layout.medianPitch, 0f)    // 112 - 80
        assertEquals(40, layout.textLeft)
        assertEquals(1000, layout.textRight)
    }

    @Test
    fun `a chrome region with lines becomes ui rows`() {
        val chrome = LayoutRegion(
            id = 0, kind = RegionKind.CHROME, order = 0, bounds = RectI(0, 0, 1080, 100),
            lines = listOf(line(10, 20, 20, 300, LineRole.BODY)),
        )
        val layout = composer.compose(page, listOf(chrome), CoverageReport.EMPTY)
        val para = layout.blocks[0] as Block.Paragraph
        assertEquals(LineRole.UI, para.lines[0].role)
    }

    @Test
    fun `a chrome region without lines becomes a ui figure`() {
        val chrome = LayoutRegion(
            id = 0, kind = RegionKind.CHROME, order = 0, bounds = RectI(0, 0, 1080, 100),
        )
        val layout = composer.compose(page, listOf(chrome), CoverageReport.EMPTY)
        val fig = layout.blocks[0] as Block.Figure
        assertEquals(FigureKind.UI, fig.kind)
    }

    @Test
    fun `a table region is a block that is never split`() {
        val table = LayoutRegion(
            id = 0, kind = RegionKind.TABLE, order = 0, bounds = RectI(40, 0, 1000, 400),
        )
        val layout = composer.compose(page, listOf(table), CoverageReport.EMPTY)
        assertTrue(layout.blocks[0] is Block.Figure)
    }

    @Test
    fun `v2 plan keeps figures whole and gives every text row the same scale`() {
        // 所有文字行都够窄（放得下 s），所以“同一倍率”这条能严格成立；
        // 放不下的行会被压缩，那是 §4.8.2 的预期行为，另有 ReflowPlannerV2Test 覆盖。
        val title = LayoutRegion(
            id = 0, kind = RegionKind.TITLE, order = 0, bounds = RectI(40, 0, 480, 60),
            lines = listOf(line(0, 50, 40, 480, LineRole.HEADING)),
        )
        val body = LayoutRegion(
            id = 1, kind = RegionKind.TEXT, order = 1, bounds = RectI(40, 100, 540, 200),
            lines = listOf(line(100, 24, 40, 540), line(130, 24, 40, 540)),
        )
        val fig = LayoutRegion(
            id = 2, kind = RegionKind.FIGURE, order = 2, bounds = RectI(40, 240, 400, 440),
        )
        val layout = composer.compose(page, listOf(title, body, fig), CoverageReport.EMPTY)
        val plan = ReflowPlanner().plan(layout, ReflowOptions(outputWidth = 1080))

        assertEquals(LayoutStrategy.DOC_LAYOUT, plan.strategy)
        val texts = plan.items.filterIsInstance<RenderItem.TextRow>()
        val figures = plan.items.filterIsInstance<RenderItem.Figure>()
        assertEquals(3, texts.size)
        assertEquals(1, figures.size)
        for (t in texts) {
            val scale = t.dest.width.toFloat() / t.src.width
            assertEquals("每一行都必须用同一个 s", plan.bodyScale, scale, 0.02f * plan.bodyScale)
        }
        // 图块整块保留：src 就是区域 bounds（不切、不裁）。
        assertEquals(RectI(40, 240, 400, 440), figures[0].src)
    }
}
