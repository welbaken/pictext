package app.pictext.core.layout

import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RegionSource
import app.pictext.core.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2-20：区域合并、去重、二次判定。 */
class RegionBuilderTest {

    private val builder = RegionBuilder()
    private val fixer = RegionKindFixer()
    private val page = RectI.ofSize(1080, 2400)

    private fun det(
        rect: RectI,
        kind: RegionKind,
        label: String,
        score: Float = 0.9f,
        tile: Int = 0,
        labelId: Int = 2,
    ) = MappedDetection(
        labelId = labelId,
        score = score,
        bounds = rect,
        tileIndex = tile,
        label = label,
        kind = kind,
    )

    @Test
    fun `the same box detected by two tiles is kept once`() {
        val out = builder.build(
            listOf(
                det(RectI(100, 100, 500, 300), RegionKind.TEXT, "text", 0.9f, tile = 0),
                det(RectI(102, 98, 503, 302), RegionKind.TEXT, "text", 0.8f, tile = 1),
            ),
            page,
        )
        assertEquals(1, out.size)
        assertEquals(0.9f, out[0].confidence, 0.0001f)
    }

    @Test
    fun `a text box fully inside a figure is dropped and reported as the figure region`() {
        val out = builder.build(
            listOf(
                det(RectI(0, 0, 800, 800), RegionKind.FIGURE, "image", labelId = 1),
                det(RectI(100, 100, 300, 200), RegionKind.TEXT, "text"),
            ),
            page,
        )
        assertEquals(1, out.size)
        assertEquals(RegionKind.FIGURE, out[0].kind)
    }

    @Test
    fun `a figure box inside a text box is dropped`() {
        val out = builder.build(
            listOf(
                det(RectI(0, 0, 900, 900), RegionKind.TEXT, "text"),
                det(RectI(100, 100, 200, 200), RegionKind.FIGURE, "image", labelId = 1),
            ),
            page,
        )
        assertEquals(1, out.size)
        assertEquals(RegionKind.TEXT, out[0].kind)
    }

    @Test
    fun `two stacked text fragments of the same column are merged`() {
        val out = builder.build(
            listOf(
                det(RectI(50, 100, 1000, 200), RegionKind.TEXT, "text"),
                det(RectI(50, 210, 1000, 310), RegionKind.TEXT, "text"),
            ),
            page,
            lineHeightHint = 24f,
        )
        assertEquals(1, out.size)
        assertEquals(RectI(50, 100, 1000, 310), out[0].bounds)
    }

    @Test
    fun `side by side regions are never merged`() {
        // 截图用例：A/C 在左上（同一列的两行，合并成一个文字区），B 在右上。
        val out = builder.build(
            listOf(
                det(RectI(0, 0, 300, 60), RegionKind.TEXT, "text"),
                det(RectI(0, 70, 300, 130), RegionKind.TEXT, "text"),
                det(RectI(320, 0, 1080, 400), RegionKind.FIGURE, "image", labelId = 1),
            ),
            page,
            lineHeightHint = 30f,
        )
        assertEquals(2, out.size)
        val text = out.first { it.kind == RegionKind.TEXT }
        assertEquals(RectI(0, 0, 300, 130), text.bounds)
        val fig = out.first { it.kind == RegionKind.FIGURE }
        assertEquals(RectI(320, 0, 1080, 400), fig.bounds)
    }

    @Test
    fun `tiny detections are dropped`() {
        val out = builder.build(
            listOf(det(RectI(10, 10, 11, 11), RegionKind.TEXT, "text")),
            page,
        )
        assertTrue(out.isEmpty())
    }

    // ---- 二次判定 ----

    private fun line(y: Int, h: Int, w: Int) = TextLine(RectI(20, y, 20 + w, y + h), role = LineRole.BODY)

    @Test
    fun `a figure with stable text lines becomes TEXT`() {
        val region = app.pictext.core.model.LayoutRegion(
            id = 0, kind = RegionKind.FIGURE, bounds = RectI(0, 0, 800, 400), label = "image",
        )
        val lines = listOf(line(0, 24, 760), line(40, 24, 755), line(80, 24, 762), line(120, 24, 758))
        val refine = RefineResult(
            region = region, medianLineHeight = 24f, inkPixels = 5000, inkRatio = 0.05f,
            density = 0.12f, maxRun = 30, lines = lines,
        )
        val fixed = fixer.fix(region, refine)
        assertEquals(RegionKind.TEXT, fixed.kind)
        assertEquals(RegionSource.MODEL_FIXED, fixed.source)
        assertEquals(4, fixed.lines.size)
    }

    @Test
    fun `a figure with only two lines stays FIGURE`() {
        val region = app.pictext.core.model.LayoutRegion(
            id = 0, kind = RegionKind.FIGURE, bounds = RectI(0, 0, 800, 400),
        )
        val refine = RefineResult(
            region = region, medianLineHeight = 24f, inkPixels = 100, inkRatio = 0.01f,
            density = 0.1f, maxRun = 20, lines = listOf(line(0, 24, 700), line(40, 24, 700)),
        )
        assertEquals(RegionKind.FIGURE, fixer.fix(region, refine).kind)
    }

    @Test
    fun `a text region with no lines becomes FIGURE and keeps its pixels`() {
        val region = app.pictext.core.model.LayoutRegion(
            id = 0, kind = RegionKind.TEXT, bounds = RectI(0, 0, 600, 400), label = "text",
        )
        val refine = RefineResult(
            region = region, medianLineHeight = 0f, inkPixels = 0, inkRatio = 0f,
            density = 0f, maxRun = 0, lines = emptyList(), failed = true,
        )
        val fixed = fixer.fix(region, refine)
        assertEquals(RegionKind.FIGURE, fixed.kind)
        assertTrue(fixed.lines.isEmpty())
    }

    @Test
    fun `a dense text region is re classified as FIGURE`() {
        val region = app.pictext.core.model.LayoutRegion(
            id = 0, kind = RegionKind.TEXT, bounds = RectI(0, 0, 600, 400),
        )
        val refine = RefineResult(
            region = region, medianLineHeight = 20f, inkPixels = 200_000, inkRatio = 0.8f,
            density = 0.75f, maxRun = 100, lines = listOf(line(0, 20, 590)),
        )
        assertEquals(RegionKind.FIGURE, fixer.fix(region, refine).kind)
    }

    @Test
    fun `a chrome region without lines becomes a UI figure`() {
        val region = app.pictext.core.model.LayoutRegion(
            id = 0, kind = RegionKind.CHROME, bounds = RectI(0, 0, 400, 100),
        )
        val refine = RefineResult(
            region = region, medianLineHeight = 0f, inkPixels = 0, inkRatio = 0f,
            density = 0f, maxRun = 0, lines = emptyList(), failed = true,
        )
        assertEquals(RegionKind.FIGURE, fixer.fix(region, refine).kind)
    }

    @Test
    fun `vertical ratio is area weighted over textual regions only`() {
        val vertical = app.pictext.core.model.LayoutRegion(
            id = 0, kind = RegionKind.TEXT, bounds = RectI(0, 0, 100, 600), vertical = true,
        )
        val horizontal = app.pictext.core.model.LayoutRegion(
            id = 1, kind = RegionKind.TEXT, bounds = RectI(200, 0, 900, 100),
        )
        val figure = app.pictext.core.model.LayoutRegion(
            id = 2, kind = RegionKind.FIGURE, bounds = RectI(0, 700, 900, 1000), vertical = true,
        )
        val ratio = fixer.verticalRatio(listOf(vertical, horizontal, figure))
        assertEquals(60_000f / (60_000f + 70_000f), ratio, 0.001f)
    }

    @Test
    fun `titles keep HEADING role after normalisation`() {
        val region = app.pictext.core.model.LayoutRegion(
            id = 0, kind = RegionKind.TITLE, bounds = RectI(0, 0, 600, 100),
            lines = listOf(line(0, 40, 500)),
        )
        assertEquals(LineRole.HEADING, fixer.normalizeRoles(region).first().role)
    }
}
