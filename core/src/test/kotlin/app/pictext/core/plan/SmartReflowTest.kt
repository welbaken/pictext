package app.pictext.core.plan

import app.pictext.core.layout.Detection
import app.pictext.core.layout.FakeLayoutDetector
import app.pictext.core.layout.GrayPageRegionSampler
import app.pictext.core.layout.GrayPageTileSampler
import app.pictext.core.layout.LayoutOptions
import app.pictext.core.layout.PageContext
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.GrayImage
import app.pictext.core.model.LayoutStrategy
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-51：版本只由用户手选，v2 失败后不再自动退回 v1。
 *
 * 480×480 的合成页 + 单 tile，坐标恒等，方便断言“走的是哪条链路”。
 */
class SmartReflowTest {

    private val analysisOptions = app.pictext.core.analyze.AnalysisOptions()
    private val pageContext = PageContext(PAGE, PAGE)

    @Test
    fun `without detector only v1 runs and it is not reported as a fallback`() {
        val gray = textPage()

        val result = SmartReflow(analysisOptions).run(
            analysis = gray,
            page = pageContext,
            planOptions = ReflowOptions(outputWidth = 1080),
            detector = null,
        )

        assertEquals(LayoutStrategy.PROJECTION, result.layout.strategy)
        assertNull("默认 v1 不是回退：${result.fallbackCode}", result.fallbackCode)
        assertFalse(result.plan.notes.any { it.startsWith("layout_fallback") })
        assertTrue("v1 应该切出文字行", result.layout.textLines().isNotEmpty())
    }

    @Test
    fun `v2 detector produces a doc layout page`() {
        val gray = textPage()
        val detector = FakeLayoutDetector(detections = listOf(titleDetection(), textDetection()))

        val result = SmartReflow(analysisOptions, LayoutOptions.singleTile()).run(
            analysis = gray,
            page = pageContext,
            planOptions = ReflowOptions(outputWidth = 1080),
            sampler = GrayPageTileSampler(gray),
            regionSampler = GrayPageRegionSampler(gray),
            detector = detector,
        )

        assertEquals(LayoutStrategy.DOC_LAYOUT, result.layout.strategy)
        assertNull(result.fallbackCode)
        assertTrue(result.layout.regions.isNotEmpty())
        assertTrue(result.layoutAudit?.ok == true)
        // 480px 的合成图 gain 不足，v2 规划器选择“原图”是正常业务判断；这里只要求
        // 它是 v2 自己出的计划，而不是 v2 管道失败被换成 v1。
        assertTrue(result.plan.fallback != FallbackReason.LAYOUT_FALLBACK)
    }

    @Test
    fun `v2 failure shows the original image instead of falling back to v1`() {
        val gray = textPage()
        // 模型一个框都没给：LayoutPipeline 返回 layout_no_detection。
        val detector = FakeLayoutDetector(detections = emptyList())

        val result = SmartReflow(analysisOptions, LayoutOptions.singleTile()).run(
            analysis = gray,
            page = pageContext,
            planOptions = ReflowOptions(outputWidth = 1080),
            sampler = GrayPageTileSampler(gray),
            regionSampler = GrayPageRegionSampler(gray),
            detector = detector,
        )

        assertEquals("layout_no_detection", result.fallbackCode)
        assertEquals(PlanMode.ORIGINAL, result.plan.mode)
        assertEquals(FallbackReason.LAYOUT_FALLBACK, result.plan.fallback)
        // 关键：不能再拿 v1 的版面冒充“强化重排”的结果。
        assertEquals(LayoutStrategy.DOC_LAYOUT, result.layout.strategy)
        assertTrue("v2 失败应该是空版面 + 原图", result.layout.blocks.isEmpty())
        assertTrue(result.layout.textLines().isEmpty())
        assertTrue(result.plan.notes.contains("layout_no_detection"))
    }

    @Test
    fun `v2 model success but too few regions still goes original not v1`() {
        val gray = textPage()
        // 一个很小的框：v2 能检出，但区域数不足，LayoutPipeline 判 layout_too_few_regions。
        val detector = FakeLayoutDetector(
            detections = listOf(
                Detection(labelId = 2, score = 0.9f, left = 20f, top = 20f, right = 60f, bottom = 40f),
            ),
        )

        val result = SmartReflow(analysisOptions, LayoutOptions.singleTile()).run(
            analysis = gray,
            page = pageContext,
            planOptions = ReflowOptions(outputWidth = 1080),
            sampler = GrayPageTileSampler(gray),
            regionSampler = GrayPageRegionSampler(gray),
            detector = detector,
        )

        assertEquals("layout_too_few_regions", result.fallbackCode)
        assertEquals(PlanMode.ORIGINAL, result.plan.mode)
        assertTrue(result.layout.blocks.isEmpty())
        assertTrue(result.layout.textLines().isEmpty())
    }

    @Test
    fun `v2 requested without samplers is original not v1`() {
        val gray = textPage()

        val result = SmartReflow(analysisOptions).run(
            analysis = gray,
            page = pageContext,
            planOptions = ReflowOptions(outputWidth = 1080),
            sampler = null,
            regionSampler = null,
            detector = FakeLayoutDetector(detections = listOf(titleDetection(), textDetection())),
        )

        assertEquals("layout_unavailable", result.fallbackCode)
        assertEquals(PlanMode.ORIGINAL, result.plan.mode)
        assertTrue(result.layout.blocks.isEmpty())
    }

    // ------------------------------------------------------------------

    private fun titleDetection(): Detection = Detection(
        labelId = 0,
        score = 0.9f,
        left = 10f,
        top = 30f,
        right = 470f,
        bottom = 65f,
    )

    private fun textDetection(): Detection = Detection(
        labelId = 2,
        score = 0.9f,
        left = 10f,
        top = 70f,
        right = 470f,
        bottom = 190f,
    )

    /**
     * 白底 + 4 行“字块”（每行 17 个 16px 块、8px 缝隙），行高 20、行距 20。
     *
     * 不能画成整条实心横杠：v1 的 `BlockClassifier` 会把实心横条判成图块，
     * 最后 `longLineCount < 2`，测不到“v1 切出行”这条路径。
     */
    private fun textPage(): GrayImage {
        val page = GrayImage.solid(PAGE, PAGE, 255)
        for (y in listOf(40, 80, 120, 160)) {
            var x = 20
            while (x + 16 <= 460) {
                page.paint(RectI(x, y, x + 16, y + 20), 0)
                x += 24
            }
        }
        return page
    }

    private fun GrayImage.paint(r: RectI, value: Int) {
        val v = value.toByte()
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) data[y * width + x] = v
        }
    }

    private companion object {
        const val PAGE = 480
    }
}
