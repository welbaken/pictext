package app.pictext.core.layout

import app.pictext.core.model.GrayImage
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [CoverageAuditor] 的合成页用例（V2-22 验收）。
 *
 * 全部用白底黑块的合成灰度图，不读磁盘、不依赖真实样例：审计是纯像素统计，
 * 合成图能把“期望值”算成整数，断言可以是等号而不是“大概”。
 *
 * 页码/坐标约定：480×480 的页面 + 一张 `src = 整页` 的 tile，此时 S 与 T 恒等
 * （`modelX = srcX`），坐标断言就只考察审计本身；唯一需要真正映射的是
 * [residuals map back to source coordinates] 的 1080 → 480 用例。
 */
class CoverageAuditorTest {

    /**
     * 验收 1（漏检找回）：一页白底，左上半区 3 行“伪文字”，右下半区一块黑图片；
     * 只把图片块交给审计（故意漏掉 3 行）。
     *
     * 期望：残差框贴着 3 行文字的真实外接框（S 误差 ≤3px），而不是膨胀后的框；
     * 把残差框当成补出来的区域再审计一次，coverage ≥99.5%。
     */
    @Test
    fun `1 missing text lines are recovered as residuals`() {
        val tile = modelTile()
        val gray = whitePage()
        val lines = listOf(
            RectI(20, 40, 320, 60),
            RectI(20, 70, 320, 90),
            RectI(20, 100, 320, 120),
        )
        val picture = RectI(260, 260, 460, 460)
        for (line in lines) gray.paint(line)
        gray.paint(picture)

        val auditor = CoverageAuditor()
        val first = auditor.auditTile(gray, tile, listOf(figure(picture)))

        assertTrue("漏掉 3 行文字后必须产生残差", first.residuals.isNotEmpty())
        // 图片块被覆盖，3 行文字（3 × 300 × 20）全部是未覆盖墨。
        assertEquals(3 * 300 * 20, first.uncovered)
        // 行区间 40..59 / 70..89 / 100..119 在 8px 膨胀后连成同一个簇 → 1 个框。
        assertEquals(1, first.residuals.size)
        val box = first.residuals[0].bounds
        assertNear(20, box.left, 3, "residual.left")
        assertNear(40, box.top, 3, "residual.top")
        assertNear(320, box.right, 3, "residual.right")
        assertNear(120, box.bottom, 3, "residual.bottom")
        assertEquals(3 * 300 * 20, first.residuals.sumOf { it.ink })

        // 补区：残差框作为区域再审计一次。
        val second = auditor.auditTile(gray, tile, listOf(figure(picture), text(box)))
        assertTrue("补区后 coverage=${second.ratio} 应 ≥0.995", second.ratio >= 0.995f)
        assertTrue(second.residuals.isEmpty())
    }

    /**
     * 验收 2（纯图页）：整页就是一张黑图片，且它已经是 region → 不该补出假区域。
     */
    @Test
    fun `2 pure figure page has no residual`() {
        val tile = modelTile()
        val gray = whitePage()
        val picture = RectI(40, 40, 440, 440)
        gray.paint(picture)
        val auditor = CoverageAuditor()
        val coverage = auditor.auditTile(gray, tile, listOf(figure(picture)))

        assertTrue(coverage.ink > 0)
        assertTrue("纯图页不该有残差: ${coverage.residuals}", coverage.residuals.isEmpty())
        assertTrue("残差墨量必须远小于 1%", coverage.residuals.sumOf { it.ink } * 100 < coverage.ink)
        assertTrue("coverage=${coverage.ratio}", coverage.ratio >= 0.995f)

        val report = auditor.report(listOf(coverage))
        assertEquals(1.0f, report.coverage, 0f)
        assertNull(report.fallbackCode(LayoutOptions()))
    }

    /**
     * 验收 3（残差 >5% 回退）：页面 90% 是墨，只给一个很小的 region → 整页回退。
     *
     * [CoverageReport.fallbackCode] 先判残差、再判覆盖率：`coverage == 1 - residualRatio`
     * 恒成立，残差超限时必然先命中 `layout_residual_high`；`layout_low_coverage` 只在
     * “残差正常但覆盖率没到 99.5%”时出现（这里用一条构造报告单独钉住那个分支）。
     */
    @Test
    fun `3 residual above five percent falls back`() {
        val tile = modelTile()
        val gray = whitePage()
        // 留 12px 白边，页面才被判成浅底（否则整页黑会被当成深色底，墨迹反向）。
        gray.paint(RectI(12, 12, 468, 468))
        val tiny = RectI(0, 0, 48, 48)

        val auditor = CoverageAuditor()
        val coverage = auditor.auditTile(gray, tile, listOf(figure(tiny)))
        val report = auditor.report(listOf(coverage))

        assertTrue("residualRatio=${report.residualRatio}", report.residualRatio > 0.05f)
        assertEquals("layout_residual_high", report.fallbackCode(LayoutOptions()))

        // 补充：残差正常、只是覆盖率差一点点 → 才轮到 low_coverage；两个门槛都满足 → 不回退。
        val nearlyCovered = CoverageReport(ink = 1000, covered = 996)
        assertEquals("layout_low_coverage", nearlyCovered.fallbackCode(LayoutOptions(minCoverage = 0.999f)))
        assertNull(nearlyCovered.fallbackCode(LayoutOptions()))
    }

    /**
     * 验收 4（全覆盖）：一个覆盖整页的 region → coverage 恰好 1.0、无残差。
     * 同时传入重叠的第二块区域，验证 covered 是**并集掩膜**而不是逐区域累加。
     */
    @Test
    fun `4 full page region covers everything exactly`() {
        val tile = modelTile()
        val gray = whitePage()
        gray.paint(RectI(20, 40, 320, 60))
        gray.paint(RectI(260, 260, 460, 460))
        val auditor = CoverageAuditor()
        val coverage = auditor.auditTile(
            gray,
            tile,
            listOf(region(RectI(0, 0, 480, 480), RegionKind.OTHER), figure(RectI(260, 260, 460, 460), id = 1)),
        )

        assertEquals(1.0f, coverage.ratio, 0f)
        assertTrue(coverage.residuals.isEmpty())

        val report = auditor.report(listOf(coverage))
        assertEquals(1.0f, report.coverage, 0f)
        assertEquals(report.ink, report.covered)
        assertEquals(0, report.residualInk)
    }

    /**
     * 验收 5（坐标映射）：tile 是 1080×1080 → 480×480（比例 2.25），
     * S 空间 x∈[100,400]、y∈[200,300] 的墨不带 region，残差框必须映射回 S（±4px）。
     */
    @Test
    fun `5 residuals map back to source coordinates`() {
        val tile = Tile(
            index = 0,
            row = 0,
            col = 0,
            src = RectI(0, 0, 1080, 1080),
            innerWidth = 480,
            innerHeight = 480,
            padX = 0,
            padY = 0,
            modelInput = 480,
        )
        // 按 tile 的映射在 T 空间画同一块墨：等价于“S 空间 x∈[100,400]、y∈[200,300] 画墨”。
        val sourceRect = RectI(100, 200, 400, 300)
        val modelRect = tile.modelRect(sourceRect)
        val gray = GrayImage.solid(480, 480, 255)
        gray.paint(modelRect)

        val coverage = CoverageAuditor().auditTile(gray, tile, emptyList())
        assertEquals(1, coverage.residuals.size)
        val box = coverage.residuals[0].bounds
        assertNear(100, box.left, 4, "mapped.left")
        assertNear(200, box.top, 4, "mapped.top")
        assertNear(400, box.right, 4, "mapped.right")
        assertNear(300, box.bottom, 4, "mapped.bottom")
    }

    /**
     * 验收 6（stats 自洽）：report 的 ink/covered/coverage/residualRatio 互相一致，
     * 且等于各 tile 的和。
     */
    @Test
    fun `6 report aggregates tile stats consistently`() {
        val auditor = CoverageAuditor()
        val textPage = whitePage()
        textPage.paint(RectI(20, 40, 320, 60))
        val figurePage = whitePage()
        figurePage.paint(RectI(260, 260, 460, 460))

        val a = auditor.auditTile(textPage, modelTile(index = 0), emptyList())
        val b = auditor.auditTile(figurePage, modelTile(index = 1), listOf(figure(RectI(260, 260, 460, 460))))
        val report = auditor.report(listOf(a, b))

        assertEquals(a.ink + b.ink, report.ink)
        assertEquals(a.covered + b.covered, report.covered)
        assertTrue("covered=${report.covered} 不能超过 ink=${report.ink}", report.covered <= report.ink)
        assertEquals(report.covered.toFloat() / report.ink, report.coverage, 1e-6f)
        assertEquals((report.ink - report.covered).toFloat() / report.ink, report.residualRatio, 1e-6f)
        assertEquals(1f, report.coverage + report.residualRatio, 1e-6f)
        assertEquals(a.residuals + b.residuals, report.residuals)
        assertEquals(listOf(a, b), report.tiles)
        assertTrue(report.residuals.isNotEmpty())
    }

    /**
     * padding 不计入墨迹：模型输入的填充区（`padValue = 114`）在灰度上比纸面暗得多，
     * 二值化会把它当墨；有效区裁剪必须把它排除，否则每个补边 tile 都会凭空多出几万墨点。
     */
    @Test
    fun `padding area is not audited as ink`() {
        val tile = Tile(
            index = 0,
            row = 0,
            col = 0,
            src = RectI(0, 0, 400, 400),
            innerWidth = 400,
            innerHeight = 400,
            padX = 40,
            padY = 40,
            modelInput = 480,
        )
        val gray = GrayImage.solid(480, 480, 114) // 整张先铺 padValue
        gray.paint(RectI(40, 40, 440, 440), 255)    // 有效区是纸面
        gray.paint(RectI(140, 140, 240, 240))       // 有效区里的一块墨 ≈ S(100,100,200,200)

        val coverage = CoverageAuditor().auditTile(gray, tile, emptyList())
        assertEquals("padding 区的墨点必须被有效区裁掉", 100 * 100, coverage.ink)
        assertEquals(1, coverage.residuals.size)
        assertEquals(RectI(100, 100, 200, 200), coverage.residuals[0].bounds)
    }

    /** 契约：`tileGray` 必须是模型输入尺寸，否则 `require` 失败（调用方喂错图要立刻炸）。 */
    @Test
    fun `tile gray must match model input size`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CoverageAuditor().auditTile(GrayImage.solid(240, 240, 255), modelTile(), emptyList())
        }
        assertTrue("message=${error.message}", error.message.orEmpty().contains("modelInput"))
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /** 一页 480×480 就是一张 tile：S 与 T 恒等，坐标断言只考察审计本身。 */
    private fun modelTile(index: Int = 0): Tile = Tile(
        index = index,
        row = 0,
        col = 0,
        src = RectI(0, 0, PAGE, PAGE),
        innerWidth = PAGE,
        innerHeight = PAGE,
        padX = 0,
        padY = 0,
        modelInput = PAGE,
    )

    private fun whitePage(): GrayImage = GrayImage.solid(PAGE, PAGE, 255)

    private fun GrayImage.paint(r: RectI, value: Int = 0) {
        for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(height)) {
            for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(width)) {
                data[y * width + x] = value.coerceIn(0, 255).toByte()
            }
        }
    }

    private fun region(bounds: RectI, kind: RegionKind, id: Int = 0): LayoutRegion =
        LayoutRegion(id = id, kind = kind, bounds = bounds)

    private fun figure(bounds: RectI, id: Int = 0): LayoutRegion =
        region(bounds, RegionKind.FIGURE, id)

    private fun text(bounds: RectI, id: Int = 1): LayoutRegion =
        region(bounds, RegionKind.TEXT, id)

    private fun assertNear(expected: Int, actual: Int, tolerance: Int, what: String) {
        assertTrue("$what: expected $expected±$tolerance but was $actual", abs(expected - actual) <= tolerance)
    }

    private companion object {
        const val PAGE = 480
    }
}
