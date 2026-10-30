package app.pictext.core.plan

import app.pictext.core.model.CutKind
import app.pictext.core.model.LineCutFeatures
import app.pictext.core.model.LineRole
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LayoutStrategy
import app.pictext.core.model.LayoutSummary
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RenderItem
import app.pictext.core.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-31：v2 计划审计。每个用例注入一种坏计划，断言“能报出对应的 issue”。
 *
 * 审计失败 = 回退信号，不是调参信号（docs/07 §4.9、V2-31）。
 */
class LayoutAuditTest {

    private val options = ReflowOptions(outputWidth = 1080)
    private val s = 2f

    private fun line(index: Int, y: Int, sensitive: Boolean = false): TextLine =
        TextLine(
            RectI(0, y, 1000, y + 20),
            role = LineRole.BODY,
            cutFeatures = if (sensitive) LineCutFeatures(sensitive = true, latinLike = true) else LineCutFeatures.NONE,
        )

    private fun layoutOf(sensitiveLines: Int = 0, normalLines: Int = 2): PageLayout {
        val normal = (0 until normalLines).map { line(it, it * 40) }
        val sensitive = (0 until sensitiveLines).map { line(2 + it, 80 + it * 40, sensitive = true) }
        val regions = listOf(
            LayoutRegion(
                id = 0, kind = RegionKind.TEXT, order = 0, bounds = RectI(0, 0, 1000, 60),
                lines = normal,
            ),
            if (sensitive.isEmpty()) {
                LayoutRegion(
                    id = 1, kind = RegionKind.FIGURE, order = 1, bounds = RectI(0, 140, 600, 400),
                )
            } else {
                LayoutRegion(
                    id = 1, kind = RegionKind.TEXT, order = 1,
                    bounds = RectI(0, 80, 1000, 80 + sensitive.size * 40),
                    lines = sensitive,
                )
            },
        )
        return PageLayout(
            srcWidth = 1080,
            srcHeight = 600,
            analysisScale = 1f,
            blocks = emptyList(),
            textLeft = 0,
            textRight = 1000,
            splitX = -1,
            cleanRatio = 1f,
            medianLineHeight = 20f,
            medianGap = 20f,
            medianPitch = 40f,
            isDark = false,
            backgroundColor = 0xFFFFFFFF.toInt(),
            diagnostics = app.pictext.core.model.Diagnostics(),
            regions = regions,
            strategy = LayoutStrategy.DOC_LAYOUT,
        )
    }

    private fun textRow(
        srcWidth: Int,
        destWidth: Int,
        top: Int,
        lineIndex: Int = 0,
        role: LineRole = LineRole.BODY,
        regionIndex: Int = 0,
        segments: Int = 1,
        cut: CutKind = CutKind.NONE,
        scale: Float = destWidth.toFloat() / maxOf(1, srcWidth),
    ) = RenderItem.TextRow(
        src = RectI(0, top, srcWidth, top + 20),
        dest = RectI(0, top, destWidth, top + (20 * scale).toInt()),
        lineIndex = lineIndex,
        role = role,
        scale = scale,
        regionIndex = regionIndex,
        segments = segments,
        cut = cut,
    )

    private fun planOf(
        items: List<RenderItem>,
        coverage: Float = 1f,
        mode: PlanMode = PlanMode.REFLOW,
    ): ReflowPlan = ReflowPlan(
        outputWidth = 1080,
        height = 2000,
        scale = s,
        gain = 2f,
        mode = mode,
        fallback = app.pictext.core.model.FallbackReason.NONE,
        items = items,
        backgroundColor = 0xFFFFFFFF.toInt(),
        bodyScale = s,
        strategy = LayoutStrategy.DOC_LAYOUT,
        layout = LayoutSummary(regions = 2, coverage = coverage),
    )

    /** 一个“完全正确”的计划：两个 region 都有 item，缩放都是 s，段数 1。 */
    private fun goodItems(): List<RenderItem> = listOf(
        textRow(500, 1000, 0, lineIndex = 0, regionIndex = 0),
        textRow(500, 1000, 45, lineIndex = 1, regionIndex = 0),
        RenderItem.Figure(
            src = RectI(0, 140, 600, 400),
            dest = RectI(0, 100, 600, 400),
            blockIndex = 1,
            regionIndex = 1,
        ),
    )

    @Test
    fun `a good plan passes`() {
        val report = LayoutAuditor.audit(layoutOf(), planOf(goodItems()), options)
        assertTrue("issues=${report.issues}", report.ok)
        assertEquals(2, report.regionCount)
        assertEquals(0, report.regionsWithoutItem)
    }

    @Test
    fun `a dropped region is reported`() {
        val items = goodItems().filter { it !is RenderItem.Figure }
        val report = LayoutAuditor.audit(layoutOf(), planOf(items), options)
        assertFalse(report.ok)
        assertEquals(1, report.regionsWithoutItem)
        assertTrue(report.issues.any { it.startsWith("region_dropped(#1") })
    }

    @Test
    fun `region order must be monotonic`() {
        val items = listOf(
            textRow(500, 1000, 0, lineIndex = 0, regionIndex = 1),
            textRow(500, 1000, 45, lineIndex = 1, regionIndex = 0),
            RenderItem.Figure(RectI(0, 140, 600, 400), RectI(0, 100, 600, 400), 1, regionIndex = 1),
        )
        val report = LayoutAuditor.audit(layoutOf(), planOf(items), options)
        assertTrue(report.issues.any { it.startsWith("region_order_violations") })
    }

    @Test
    fun `scale deviation over two percent is reported`() {
        val items = listOf(
            // 切成 2 段的行不属于“压缩行”，它偏离 s 必须被判出来。
            textRow(500, 750, 0, lineIndex = 0, regionIndex = 0, segments = 2),   // 1.5x ≠ 2.0x
            textRow(500, 1000, 45, lineIndex = 1, regionIndex = 0),
            RenderItem.Figure(RectI(0, 140, 600, 400), RectI(0, 100, 600, 400), 1, regionIndex = 1),
        )
        val report = LayoutAuditor.audit(layoutOf(), planOf(items), options)
        assertTrue(report.issues.any { it.startsWith("scale_inconsistent") })
        assertEquals(0.25f, report.maxScaleDeviation, 0.001f)
    }

    @Test
    fun `ui rows above one and a half are reported`() {
        val items = goodItems() + textRow(
            400, 800, 500, lineIndex = 9, role = LineRole.UI, regionIndex = 0,
        )
        val report = LayoutAuditor.audit(layoutOf(), planOf(items), options)
        assertTrue(report.issues.any { it.startsWith("ui_row_too_large") })
        assertEquals(2f, report.uiMaxScale, 0.001f)
    }

    @Test
    fun `segments above the hard max are reported`() {
        val items = goodItems() + textRow(1000, 500, 600, lineIndex = 7, regionIndex = 0, segments = 9)
        val report = LayoutAuditor.audit(layoutOf(), planOf(items), options)
        assertTrue(report.issues.any { it.startsWith("segments_over_hard_max") })
    }

    @Test
    fun `a forced cut on a sensitive line is reported`() {
        val layout = layoutOf(sensitiveLines = 1)
        val items = listOf(
            textRow(500, 1000, 0, lineIndex = 0, regionIndex = 0),
            textRow(500, 1000, 45, lineIndex = 1, regionIndex = 0),
            textRow(1000, 1000, 85, lineIndex = 2, regionIndex = 1, segments = 2, cut = CutKind.FORCED),
            textRow(1000, 1000, 130, lineIndex = 2, regionIndex = 1, segments = 2, cut = CutKind.HARD),
        )
        val report = LayoutAuditor.audit(layout, planOf(items), options)
        assertTrue(report.issues.any { it.startsWith("sensitive_forced_cut") })
        assertEquals(1, report.sensitiveForcedCuts)
    }

    @Test
    fun `vertical overlap above two pixels is reported`() {
        val items = listOf(
            textRow(500, 1000, 0, lineIndex = 0, regionIndex = 0),     // dest bottom = 40
            textRow(500, 1000, 30, lineIndex = 1, regionIndex = 0),    // 上移 10px
            RenderItem.Figure(RectI(0, 140, 600, 400), RectI(0, 100, 600, 400), 1, regionIndex = 1),
        )
        val report = LayoutAuditor.audit(layoutOf(), planOf(items), options)
        assertTrue(report.issues.any { it.startsWith("items_vertical_overlap") })
    }

    @Test
    fun `low coverage is reported`() {
        val report = LayoutAuditor.audit(layoutOf(), planOf(goodItems(), coverage = 0.9f), options)
        assertTrue(report.issues.any { it.startsWith("coverage_low") })
    }

    @Test
    fun `compressed rows above ten percent are reported`() {
        // 3 行文字（达到比例门槛的最小样本量），3 行都被压缩 → 100%
        val items = listOf(
            textRow(1000, 1000, 0, lineIndex = 0, regionIndex = 0),   // 1.0x < 0.98 × 2
            textRow(1000, 1000, 45, lineIndex = 1, regionIndex = 0),
            textRow(1000, 1000, 90, lineIndex = 2, regionIndex = 0),
            RenderItem.Figure(RectI(0, 140, 600, 400), RectI(0, 100, 600, 400), 1, regionIndex = 1),
        )
        val layout = layoutOf(normalLines = 3)
        val report = LayoutAuditor.audit(layout, planOf(items), options)
        assertTrue(report.issues.any { it.startsWith("compressed_ratio") })
        assertEquals(1f, report.compressedRatio, 0.001f)
    }

    @Test
    fun `small samples do not fail the ratio gates`() {
        // 整页只有 2 行文字、其中 1 行被压缩：比例 50% 很吓人，但样本太小，不该判失败。
        val items = listOf(
            textRow(1000, 1000, 0, lineIndex = 0, regionIndex = 0),
            textRow(500, 1000, 45, lineIndex = 1, regionIndex = 0),
            RenderItem.Figure(RectI(0, 140, 600, 400), RectI(0, 100, 600, 400), 1, regionIndex = 1),
        )
        val report = LayoutAuditor.audit(layoutOf(), planOf(items), options)
        assertTrue("issues=${report.issues}", report.ok)
        assertEquals(0.5f, report.compressedRatio, 0.001f)
    }

    @Test
    fun `original plans are never audited`() {
        val report = LayoutAuditor.audit(layoutOf(), planOf(emptyList(), mode = PlanMode.ORIGINAL), options)
        assertTrue(report.ok)
    }
}
