package app.pictext.core.layout

import app.pictext.core.model.CutKind
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.FigureKind
import app.pictext.core.model.InkProfile
import app.pictext.core.model.LineCutFeatures
import app.pictext.core.model.LineRole
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RenderItem
import app.pictext.core.model.TextLine
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.planDocLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * V2-30：全页一个倍率 + 按需 N 段（docs/07 §4.8）。
 *
 * 页面尺寸与截图用例同构：1080 宽，左半文字区 + 右半图片/文字区，
 * 整行文字需要切 2 段（否则 `s` 撑不住），半宽长行也需要切 2 段。
 */
class ReflowPlannerV2Test {

    private fun line(
        x0: Int,
        x1: Int,
        y: Int,
        h: Int,
        role: LineRole = LineRole.BODY,
        features: LineCutFeatures = LineCutFeatures.NONE,
    ) = TextLine(RectI(x0, y, x1, y + h), role = role, cutFeatures = features)

    /** 一条“有一刀硬缝”的行：缝在 [center] 两侧各 5px。 */
    private fun hardCutAt(center: Int): LineCutFeatures =
        LineCutFeatures(hardGaps = listOf((center - 5) until (center + 6)))

    private val page = PageContext(srcWidth = 1080, srcHeight = 1400)

    private fun layoutOf(regions: List<LayoutRegion>): PageLayout =
        PageComposer().compose(page, regions, CoverageReport.EMPTY)

    /** 截图用例的骨架：A/C 左上、B 右上图、D 整行、E 图、F/G/H 右半、I 图、J/K/L 右半。 */
    private fun screenshotLayout(): PageLayout {
        val regions = listOf(
            LayoutRegion(
                id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(40, 20, 520, 120),
                lines = listOf(
                    line(40, 500, 20, 22, features = hardCutAt(270)),
                    line(40, 500, 52, 22, features = hardCutAt(270)),
                ),
            ),
            LayoutRegion(
                id = 1, kind = RegionKind.FIGURE, order = 1, bounds = RectI(540, 20, 1040, 300),
            ),
            LayoutRegion(
                id = 2, kind = RegionKind.TEXT, order = 2, bounds = RectI(40, 320, 940, 342),
                lines = listOf(line(40, 940, 320, 22, features = hardCutAt(490))),
            ),
            LayoutRegion(
                id = 3, kind = RegionKind.FIGURE, order = 3, bounds = RectI(40, 360, 500, 560),
            ),
            LayoutRegion(
                id = 4, kind = RegionKind.TEXT, order = 4, bounds = RectI(540, 360, 980, 470),
                lines = listOf(
                    line(540, 980, 360, 22, features = hardCutAt(760)),
                    line(540, 980, 392, 22, features = hardCutAt(760)),
                    line(540, 980, 424, 22, features = hardCutAt(760)),
                ),
            ),
            LayoutRegion(
                id = 5, kind = RegionKind.FIGURE, order = 5, bounds = RectI(40, 600, 400, 800),
            ),
            LayoutRegion(
                id = 6, kind = RegionKind.TEXT, order = 6, bounds = RectI(540, 600, 1040, 790),
                lines = listOf(
                    line(540, 1020, 600, 22, features = hardCutAt(780)),
                    line(540, 1020, 632, 22, features = hardCutAt(780)),
                    line(540, 1020, 664, 22, features = hardCutAt(780)),
                ),
            ),
        )
        return layoutOf(regions)
    }

    @Test
    fun `screenshot case cuts the wide rows and keeps the short ones whole`() {
        val layout = screenshotLayout()
        val plan = planDocLayout(layout, ReflowOptions(outputWidth = 1080))

        assertEquals(PlanMode.REFLOW, plan.mode)
        assertEquals(FallbackReason.NONE, plan.fallback)
        val texts = plan.items.filterIsInstance<RenderItem.TextRow>()
        val figures = plan.items.filterIsInstance<RenderItem.Figure>()

        // 三块图整块保留。
        assertEquals(3, figures.size)
        for (f in figures) {
            assertEquals("图块必须整块 src", f.src, layout.regions.first { it.order == f.regionIndex }.bounds)
            assertEquals(FigureKind.PHOTO, f.kind)
        }

        // 整行 D（region 2）切 2 段；右半 J/K/L（region 6）各切 2 段。
        val d = texts.filter { it.regionIndex == 2 }
        assertEquals(2, d.size)
        assertEquals(2, d[0].segments)
        assertEquals(CutKind.NONE, d[0].cut)
        assertEquals(CutKind.HARD, d[1].cut)
        for (region in intArrayOf(6)) {
            val j = texts.filter { it.regionIndex == region }
            assertEquals(6, j.size)  // 3 行 × 2 段
            for (row in j.filter { it.segmentIndex == 1 }) assertEquals(CutKind.HARD, row.cut)
        }

        // F/G/H（region 4）每行一段（半宽、够放）。
        for (region in intArrayOf(4)) {
            val f = texts.filter { it.regionIndex == region }
            assertEquals(3, f.size)
            assertTrue(f.all { it.segments == 1 })
        }
    }

    @Test
    fun `text, title and caption all use the same scale`() {
        val regions = listOf(
            LayoutRegion(
                id = 0, kind = RegionKind.TITLE, order = 0, bounds = RectI(40, 0, 600, 60),
                lines = listOf(line(40, 600, 0, 44, LineRole.HEADING, hardCutAt(320))),
            ),
            LayoutRegion(
                id = 1, kind = RegionKind.TEXT, order = 1, bounds = RectI(40, 80, 600, 140),
                lines = listOf(line(40, 600, 80, 22, features = hardCutAt(320))),
            ),
            LayoutRegion(
                id = 2, kind = RegionKind.CAPTION, order = 2, bounds = RectI(40, 160, 600, 176),
                lines = listOf(line(40, 600, 160, 16, LineRole.CAPTION, hardCutAt(320))),
            ),
        )
        val plan = planDocLayout(layoutOf(regions), ReflowOptions(outputWidth = 1080))
        val s = plan.bodyScale
        assertTrue("s 必须 >1（只放大）", s >= 1f)
        for (item in plan.items.filterIsInstance<RenderItem.TextRow>()) {
            val actual = item.dest.width.toFloat() / item.src.width
            assertEquals("role=${item.role}", s, actual, 0.02f * s)
        }
    }

    @Test
    fun `chrome rows are capped at 1x`() {
        val chrome = LayoutRegion(
            id = 0, kind = RegionKind.CHROME, order = 0, bounds = RectI(40, 0, 900, 30),
            lines = listOf(line(40, 900, 0, 24, LineRole.UI)),
        )
        val body = LayoutRegion(
            id = 1, kind = RegionKind.TEXT, order = 1, bounds = RectI(40, 60, 900, 82),
            lines = listOf(line(40, 900, 60, 22)),
        )
        val plan = planDocLayout(layoutOf(listOf(chrome, body)), ReflowOptions(outputWidth = 1080))
        val chromeRow = plan.items.filterIsInstance<RenderItem.TextRow>().first { it.role == LineRole.UI }
        val scale = chromeRow.dest.width.toFloat() / chromeRow.src.width
        assertTrue("chrome scale=$scale", scale <= 1.0f + 0.001f)
        assertTrue("body 仍然放大", plan.bodyScale > 1f)
    }

    @Test
    fun `a page that cannot gain anything returns ORIGINAL`() {
        val small = PageContext(srcWidth = 400, srcHeight = 400)
        val regions = listOf(
            LayoutRegion(
                id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(0, 0, 380, 300),
                lines = listOf(line(0, 380, 0, 60), line(0, 380, 80, 60)),
            ),
        )
        val layout = PageComposer().compose(small, regions, CoverageReport.EMPTY)
        val plan = planDocLayout(layout, ReflowOptions(outputWidth = 1080))
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertEquals(FallbackReason.GAIN_TOO_SMALL, plan.fallback)
    }

    @Test
    fun `too many forced cuts fall back to v1 instead of lowering the page scale`() {
        // 6 行都只能强制切 → forcedCutRatio = 1.0 > 15%，必须回退（不是把 s 压小）。
        val lines = (0 until 6).map { i ->
            val y = i * 40
            line(
                40, 940, y, 22,
                features = LineCutFeatures(
                    minInkColumn = 490,
                    cjkDominant = true,
                    density = 0.5f,
                    maxRun = 10,
                    profile = InkProfile.of(40, IntArray(900) { if (it == 450) 0 else 2 }),
                ),
            )
        }
        val regions = listOf(
            LayoutRegion(id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(40, 0, 940, 260), lines = lines),
        )
        val plan = planDocLayout(layoutOf(regions), ReflowOptions(outputWidth = 1080))
        assertEquals(FallbackReason.LAYOUT_FALLBACK, plan.fallback)
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertTrue(plan.notes.any { it.startsWith("layout_forced_cut_high") })
    }

    @Test
    fun `a sensitive row that cannot be cut is compressed and reported`() {
        val sensitive = line(
            40, 940, 0, 22,
            features = LineCutFeatures(
                sensitive = true, latinLike = true, density = 0.5f, maxRun = 8,
                profile = InkProfile.of(40, IntArray(900) { 3 }),
            ),
        )
        val ok = line(40, 300, 40, 22)
        val regions = listOf(
            LayoutRegion(id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(40, 0, 940, 80), lines = listOf(sensitive, ok)),
        )
        val plan = planDocLayout(layoutOf(regions), ReflowOptions(outputWidth = 1080))
        // 敏感行没刀口 → 100% 敏感行被压缩 → 超过 5% → 回退 v1（docs/07 §4.9）。
        assertEquals(FallbackReason.LAYOUT_FALLBACK, plan.fallback)
        assertTrue(plan.notes.any { it.startsWith("layout_sensitive_compressed") })
    }

    @Test
    fun `figures are clamped to the column width and never split`() {
        val fig = LayoutRegion(
            id = 0, kind = RegionKind.FIGURE, order = 0, bounds = RectI(0, 0, 300, 200),
        )
        val body = LayoutRegion(
            id = 1, kind = RegionKind.TEXT, order = 1, bounds = RectI(0, 220, 900, 242),
            lines = listOf(line(0, 900, 220, 22)),
        )
        val plan = planDocLayout(layoutOf(listOf(fig, body)), ReflowOptions(outputWidth = 1080))
        val figure = plan.items.filterIsInstance<RenderItem.Figure>().single()
        val scale = figure.dest.width.toFloat() / figure.src.width
        assertTrue("figure scale=$scale", scale >= 1f && scale <= 2.2f + 0.001f)
        assertEquals(1, plan.items.filterIsInstance<RenderItem.Figure>().size)
    }

    @Test
    fun `narrow figures keep their left right side instead of being centred`() {
        // 聊天页：左侧机器人头像 34px、右侧用户头像 32px，中间是正文。
        // 两者都是“窄图”，位置本身是语义（谁说的），不能被居中抹掉。
        val left = LayoutRegion(id = 0, kind = RegionKind.FIGURE, order = 0, bounds = RectI(8, 60, 42, 110))
        val right = LayoutRegion(id = 1, kind = RegionKind.FIGURE, order = 1, bounds = RectI(1040, 60, 1072, 110))
        val body = LayoutRegion(
            id = 2, kind = RegionKind.TEXT, order = 2, bounds = RectI(60, 130, 620, 152),
            lines = listOf(line(60, 620, 130, 22)),
        )
        val plan = planDocLayout(layoutOf(listOf(left, right, body)), ReflowOptions(outputWidth = 1080))
        val figures = plan.items.filterIsInstance<RenderItem.Figure>()
        assertEquals("两个头像都要保留", 2, figures.size)
        val avatarLeft = figures.first { it.src.left < 500 }
        val avatarRight = figures.first { it.src.left > 500 }
        assertTrue("左头像应靠左：${avatarLeft.dest}", avatarLeft.dest.left < 360)
        assertTrue("右头像应靠右：${avatarRight.dest}", avatarRight.dest.right > 720)
        for (f in figures) {
            assertTrue("不得越界：${f.dest}", f.dest.right <= 1080 && f.dest.left >= 0)
        }
    }

    @Test
    fun `wide photos stay centred`() {
        val photo = LayoutRegion(id = 0, kind = RegionKind.FIGURE, order = 0, bounds = RectI(40, 100, 1040, 500))
        val body = LayoutRegion(
            id = 1, kind = RegionKind.TEXT, order = 1, bounds = RectI(40, 520, 650, 542),
            lines = listOf(line(40, 650, 520, 22)),
        )
        val plan = planDocLayout(layoutOf(listOf(photo, body)), ReflowOptions(outputWidth = 1080))
        val figure = plan.items.filterIsInstance<RenderItem.Figure>().single()
        val leftGap = figure.dest.left
        val rightGap = 1080 - figure.dest.right
        assertTrue("宽图应居中：left=$leftGap right=$rightGap", kotlin.math.abs(leftGap - rightGap) <= 2)
    }
    @Test
    fun `every item stays inside the output canvas`() {
        val plan = planDocLayout(screenshotLayout(), ReflowOptions(outputWidth = 1080))
        for (item in plan.items) {
            assertTrue("dest.left ${item.dest}", item.dest.left >= 0)
            assertTrue("dest.right ${item.dest}", item.dest.right <= 1080)
            assertTrue("dest.top ${item.dest}", item.dest.top >= 0)
            assertTrue("dest.bottom ${item.dest}", item.dest.bottom <= plan.height)
            assertTrue("src ${item.src}", item.src.left >= 0 && item.src.right <= 1080)
        }
        // 垂直不重叠
        var prevBottom = -1
        for (item in plan.items) {
            assertTrue("overlap at ${item.dest}", item.dest.top >= prevBottom)
            prevBottom = item.dest.bottom
        }
    }

    @Test
    fun `max segments are respected`() {
        val line = line(
            0, 1000, 0, 22,
            features = LineCutFeatures(
                hardGaps = (1..6).map { (it * 140 - 3) until (it * 140 + 4) },
                cjkDominant = true,
                density = 0.4f,
                maxRun = 9,
            ),
        )
        val regions = listOf(
            LayoutRegion(id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(0, 0, 1000, 22), lines = listOf(line)),
        )
        val plan = planDocLayout(layoutOf(regions), ReflowOptions(outputWidth = 600, targetEm = 60))
        val rows = plan.items.filterIsInstance<RenderItem.TextRow>()
        assertTrue(rows.isNotEmpty())
        val segments = rows.maxOf { it.segments }
        assertTrue("segments=$segments", segments <= ReflowOptions(outputWidth = 600).hardMaxSegments)
        // 每段都是左对齐、宽度为正。
        for (r in rows) assertTrue(r.dest.width > 0)
        assertEquals(segments, rows.size)
    }

    @Test
    fun `total width of the segments equals the source line width`() {
        val plan = planDocLayout(screenshotLayout(), ReflowOptions(outputWidth = 1080))
        val d = plan.items.filterIsInstance<RenderItem.TextRow>().filter { it.regionIndex == 2 }
        assertEquals(2, d.size)
        val total = d.sumOf { it.src.width }
        assertEquals(900, total)
        // 段与段首尾相接（`c` 归右段）。
        assertEquals(d[0].src.right, d[1].src.left)
    }

    @Test
    fun `plan height stays reasonable for a dense page`() {
        val plan = planDocLayout(screenshotLayout(), ReflowOptions(outputWidth = 1080))
        assertTrue(plan.height in 1..300_000)
        val s = plan.bodyScale
        assertEquals(s, plan.scale, 0.0001f)
        assertTrue(plan.tileCount >= 1)
        assertEquals((plan.height + plan.tileHeightPx - 1) / plan.tileHeightPx, plan.tileCount)
        // 与手算的 s 对齐：em = 0.92 × 22，s = 44 / em（上限 3.0，只放大不缩小）。
        val expected = (44f / (0.92f * 22f)).coerceIn(1f, 3f)
        assertEquals(expected, s, 0.01f)
    }
}

