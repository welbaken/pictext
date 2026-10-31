package app.pictext.core

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.BandRegionSplitter
import app.pictext.core.analyze.BlockClassifier
import app.pictext.core.analyze.PageMetricsCalculator
import app.pictext.core.analyze.RowBand
import app.pictext.core.model.Block
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3 图文混排：**带内竖切**（[BandRegionSplitter]）与“超高档位不再吞掉右侧文字”。
 *
 * 三条底线，任何一条破了都算回归：
 * 1. 左图右文要拆开，且按**先左后右**输出；
 * 2. 一行字里的宽字距、照片里的一道亮纹**不能**被当成竖缝（宁可整块当图）；
 * 3. 图块右缘不能污染版面宽/长行门槛（否则整页 `too_few_long_lines`）。
 */
class BandSplitTest {

    private val classifier = BlockClassifier()

    /** 左图右文：左侧 160×260 实心块，右侧 5 行伪文字。 */
    private fun mixedPage(w: Int = 400, h: Int = 300): BooleanArray {
        val mask = BooleanArray(w * h)
        for (y in 20 until 280) for (x in 20 until 180) mask[y * w + x] = true
        for (i in 0 until 5) drawGlyphRow(mask, w, 40 + i * 44, 20, 200, 380)
        return mask
    }

    @Test
    fun `left figure and right text are split left first`() {
        val w = 400
        val h = 300
        val mask = mixedPage(w, h)
        val band = bandOf(mask, w, 20, 280, 20, 380)

        val page = classifier.classify(mask, w, h, listOf(band), 20f, ByteArray(w * h))
        val figures = page.blocks.filterIsInstance<Block.Figure>()
        val paragraphs = page.blocks.filterIsInstance<Block.Paragraph>()

        assertEquals("左侧图块应独立", 1, figures.size)
        assertEquals("右侧 5 行字应成为正文", 1, paragraphs.size)
        assertEquals(5, paragraphs[0].lines.size)
        assertTrue("图块不该包含右侧文字: ${figures[0].bounds}", figures[0].bounds.right <= 200)
        assertTrue(
            "文字行必须在图的右边: ${paragraphs[0].lines.map { it.band.left }}",
            paragraphs[0].lines.all { it.band.left >= 180 },
        )
        assertEquals("先左后右：图块排在正文之前", figures[0], page.blocks.first())
    }

    @Test
    fun `splitter finds the gutter only when it is wide enough`() {
        val splitter = BandRegionSplitter()
        val w = 400
        val h = 300
        val mask = mixedPage(w, h)
        val split = splitter.find(mask, w, bandOf(mask, w, 20, 280, 20, 380))
        assertNotNull(split)
        assertTrue("cut=${split!!.cutX}", split.cutX in 180..200)

        // 缝只有 3px（字距级别）时不算图文通道
        val tight = BooleanArray(w * h)
        for (y in 20 until 280) {
            for (x in 20 until 180) tight[y * w + x] = true
            for (x in 183 until 380) tight[y * w + x] = true
        }
        val noGap = splitter.find(tight, w, bandOf(tight, w, 20, 280, 20, 380))
        assertTrue("3px 缝不该被当成图文分界: ${noGap?.cutX}", noGap == null)
    }

    @Test
    fun `gutter too close to an edge is ignored`() {
        val splitter = BandRegionSplitter()
        val w = 400
        val h = 300
        val mask = BooleanArray(w * h)
        // 左边缘一条 30px 窄块，右侧整片内容：切点离左边界只有 30px < 15% × 带宽
        for (y in 20 until 280) for (x in 20 until 50) mask[y * w + x] = true
        for (y in 20 until 280) for (x in 90 until 380) mask[y * w + x] = true
        val split = splitter.find(mask, w, bandOf(mask, w, 20, 280, 20, 380))
        assertNull("过于靠边的缝不处理: ${split?.cutX}", split)
    }

    @Test
    fun `wide word gap in a single text line is not a band split`() {
        val w = 400
        val h = 120
        val mask = BooleanArray(w * h)
        // 一行 24px 高的文字，中间 24px 的宽字距
        drawGlyphRow(mask, w, 20, 24, 20, 170)
        drawGlyphRow(mask, w, 20, 24, 200, 380)
        val band = bandOf(mask, w, 20, 44, 20, 380)
        val page = classifier.classify(mask, w, h, listOf(band), 24f, ByteArray(w * h))

        val lines = page.blocks.filterIsInstance<Block.Paragraph>().flatMap { it.lines }
        assertEquals("宽字距不能把一行拆成两行", 1, lines.size)
        assertEquals(0, page.blocks.count { it is Block.Figure })
    }

    @Test
    fun `single tall band page still separates figure from text`() {
        // 整页只有“一张图 + 右侧 6 行字”：行高中位数 = 带高（退化），
        // 所有相对阈值（1.25×/1.8×/2.8×）都失效，连“这是图”都判不出来。
        val w = 400
        val h = 620
        val mask = BooleanArray(w * h)
        for (y in 20 until 560) for (x in 20 until 200) mask[y * w + x] = true
        for (i in 0 until 6) drawGlyphRow(mask, w, 60 + i * 46, 20, 230, 390)
        val band = bandOf(mask, w, 20, 560, 20, 390)

        val page = classifier.classify(mask, w, h, listOf(band), 540f, ByteArray(w * h))
        assertEquals("退化页也要认出图块", 1, page.blocks.count { it is Block.Figure })
        val lines = page.blocks.filterIsInstance<Block.Paragraph>().flatMap { it.lines }
        assertEquals("右侧 6 行字要成为正文", 6, lines.size)
    }

    @Test
    fun `long line threshold ignores the figure bounds`() {
        val lines = (0 until 6).map { TextLine(RectI(20, it * 30, 320, it * 30 + 20)) }
        // 图块右缘 900：旧口径会把它算进版面宽，长行门槛被抬到 630，6 行正文全都不算长行
        val blocks = listOf(Block.Figure(RectI(0, 0, 900, 200)), Block.Paragraph(lines))

        val m = PageMetricsCalculator.compute(blocks, AnalysisOptions())
        assertEquals("粗略版面宽只看文字行", 300, m.roughWidth)
        assertEquals("门槛 = 0.70 × 文字行宽 90 分位", 210, m.longLineThreshold)
        assertEquals("6 行正文都算长行", 6, m.longLineCount)
    }
}
