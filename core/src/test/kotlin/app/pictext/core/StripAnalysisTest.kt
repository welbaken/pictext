package app.pictext.core

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.analyze.StripLayoutAnalyzer
import app.pictext.core.model.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P2：像素超预算时的条带分析。
 *
 * 验收标准只有一条：**条带合并后的版面必须和一次分析的结果一致**——
 * 行数、行带位置、版心、切分列都对得上，不能出现重复行或跨边界的半行。
 */
class StripAnalysisTest {

    private val w = 400
    private val h = 9000
    private val lineH = 20
    private val pitch = 40

    private fun tallPage(): app.pictext.core.model.GrayImage {
        val mask = BooleanArray(w * h)
        var y = 30
        while (y + lineH < h) {
            drawGlyphRow(mask, w, y, lineH, 20, 380)
            y += pitch
        }
        return grayFromMask(mask, w, h)
    }

    @Test
    fun `slices cover the page and overlap`() {
        val analyzer = StripLayoutAnalyzer(
            AnalysisOptions(stripHeight = 3000, stripOverlap = 48),
        ) { _, _ -> error("not used") }
        val slices = analyzer.slices(10000)
        assertEquals(0, slices.first().first)
        assertEquals(10000, slices.last().first + slices.last().second)
        assertTrue("至少 4 条: $slices", slices.size >= 4)
        for (i in 1 until slices.size) {
            val previousEnd = slices[i - 1].first + slices[i - 1].second
            assertEquals("相邻条带必须重叠 48px", 48, previousEnd - slices[i].first)
            assertTrue(slices[i].second in 1..3000)
        }
    }

    @Test
    fun `strip merge matches a single pass`() {
        val gray = tallPage()
        val single = LayoutAnalyzer(AnalysisOptions(analysisMaxPixels = 100_000_000))
            .analyze(gray, w, h)
        val striped = LayoutAnalyzer(
            AnalysisOptions(analysisMaxPixels = 1_500_000, stripHeight = 3000, stripOverlap = 48),
        ).analyze(gray, w, h)

        assertTrue("必须真的走了条带: ${striped.diagnostics.notes}", striped.diagnostics.notes.any { it.startsWith("strip=") })
        assertEquals("行数", single.textLines().size, striped.textLines().size)

        val a = single.textLines().map { it.band.top }
        val b = striped.textLines().map { it.band.top }
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertTrue("第 $i 行 y 偏差过大: ${a[i]} vs ${b[i]}", abs(a[i] - b[i]) <= 2)
        }
        // 缝合过的行带必须严格递增，不能出现重复行。
        assertTrue(
            "行带重复: ${b.zipWithNext().filter { (p, n) -> n <= p }}",
            b.zipWithNext().all { (p, n) -> n > p },
        )
        assertEquals("版心左", single.textLeft, striped.textLeft)
        assertEquals("版心右", single.textRight, striped.textRight)
        assertEquals("行高中位数", single.medianLineHeight, striped.medianLineHeight, 0.5f)
        assertTrue("切分列偏差过大: ${single.splitX} vs ${striped.splitX}", abs(single.splitX - striped.splitX) <= 3)
        assertEquals("整页高度", h, striped.srcHeight)
        assertEquals("分析缩放", 1f, striped.analysisScale, 1e-6f)
    }

    @Test
    fun `strip page keeps working when a strip is empty`() {
        // 下半页纯白：条带分析不能把“没有文字的带”的 0 混进版心。
        val mask = BooleanArray(w * h)
        var y = 30
        while (y + lineH < 2000) {
            drawGlyphRow(mask, w, y, lineH, 20, 380)
            y += pitch
        }
        val gray = grayFromMask(mask, w, h)
        val layout = LayoutAnalyzer(
            AnalysisOptions(analysisMaxPixels = 1_500_000, stripHeight = 3000, stripOverlap = 48),
        ).analyze(gray, w, h)
        assertTrue(layout.textLines().isNotEmpty())
        assertTrue("版心左不能变成 0: ${layout.textLeft}", layout.textLeft >= 15)
        assertTrue("版心右不能变成 0: ${layout.textRight}", layout.textRight >= 360)
        assertEquals(w, layout.srcWidth)
        assertEquals(h, layout.srcHeight)
    }
}
