package app.pictext.core

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.BlockClassifier
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.analyze.RowBand
import app.pictext.core.analyze.RowDecomposer
import app.pictext.core.analyze.RowSegmenter
import app.pictext.core.model.Block
import app.pictext.core.model.FigureKind
import app.pictext.core.model.GrayImage
import app.pictext.core.util.Grayscale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 / P0-2 的行结构护栏：
 * - 行带合并必须有总高度上限（否则两行正文会被并成一个大带，再被判成图块）；
 * - 被平滑吃掉 1–2 行缝隙的大带，要靠 RowDecomposer 拆回多行；
 * - 细竖直装饰列（引用竖线）不应把行粘在一起。
 */
class RowStructureTest {

    private val segmenter = RowSegmenter()

    private fun bars(w: Int, h: Int, vararg rects: Triple<Int, Int, IntRange>): BooleanArray {
        val mask = BooleanArray(w * h)
        for ((y0, y1, xs) in rects) {
            for (y in y0 until y1) {
                for (x in xs) if (x in 0 until w) mask[y * w + x] = true
            }
        }
        return mask
    }

    /** 画一行“伪文字”：每 glyph px 一个字形（两竖笔 + 一横），密度接近真实正文，不会被判成图块。 */
    private fun glyphRow(mask: BooleanArray, w: Int, y0: Int, height: Int, x0: Int, x1: Int) {
        val pad = maxOf(2, height / 8)
        val glyph = maxOf(8, height)
        var x = x0
        while (x + glyph <= x1) {
            for (dy in pad until height - pad) {
                val y = y0 + dy
                for (dx in 2 until 5) {
                    val xx = x + dx
                    if (y < mask.size / w && xx in 0 until w) mask[y * w + xx] = true
                }
                for (dx in glyph - 4 until glyph - 1) {
                    val xx = x + dx
                    if (y < mask.size / w && xx in 0 until w) mask[y * w + xx] = true
                }
            }
            val my = y0 + height / 2
            if (my < mask.size / w) {
                for (dx in 4 until glyph - 2) {
                    val xx = x + dx
                    if (xx in 0 until w) mask[my * w + xx] = true
                }
            }
            x += glyph + 2
        }
    }

    @Test
    fun `two tight body lines are not merged into one band`() {
        // 两行 22px、行间 8px：旧实现按 0.45×行高 合并，会把它们并成 52px 的大带。
        val w = 300
        val h = 160
        val mask = bars(
            w, h,
            Triple(10, 32, 20..280),
            Triple(40, 62, 20..280),
            Triple(90, 112, 20..280),
            Triple(130, 152, 20..280),
        )
        val r = segmenter.segment(mask, w, h)
        println("bands=" + r.bands.map { "${it.y0}..${it.y1}" } + " medH=${r.medianHeight}")
        assertEquals(4, r.bands.size)
        assertTrue("y0=${r.bands[0].y0}", r.bands[0].y0 in 9..10)
        assertEquals(33, r.bands[0].y1)
        assertEquals(39, r.bands[1].y0)
    }

    @Test
    fun `stacked glyph fragments still merge into one band`() {
        // 一个字的上下结构：8px + 8px，中间 2px 缝；总高 18 < 1.25×行高。
        val w = 300
        val h = 160
        val mask = bars(
            w, h,
            Triple(10, 18, 20..280),
            Triple(20, 28, 20..280),
            Triple(50, 72, 20..280),
            Triple(90, 112, 20..280),
            Triple(130, 152, 20..280),
        )
        val r = segmenter.segment(mask, w, h)
        println("bands=" + r.bands.map { "${it.y0}..${it.y1}" })
        assertEquals(4, r.bands.size)
        assertTrue("y0=${r.bands[0].y0}", r.bands[0].y0 in 9..10)
        assertEquals(29, r.bands[0].y1)
    }

    @Test
    fun `thin vertical rule does not glue lines together`() {
        val w = 300
        val h = 140
        val mask = bars(
            w, h,
            Triple(10, 32, 90..280),
            Triple(42, 64, 90..280),
            Triple(74, 96, 90..280),
            // 3px 宽的引用竖线，贯穿三行
            Triple(10, 96, 60..62),
        )
        val r = segmenter.segment(mask, w, h)
        println("bands=" + r.bands.map { "${it.y0}..${it.y1} x=${it.x0}..${it.x1}" })
        assertEquals(3, r.bands.size)
        // 装饰列被清除后，行带左边界是正文，而不是竖线。
        assertEquals(90, r.bands[0].x0)
        assertTrue(r.decorationColumns.count { it } >= 3)
    }

    @Test
    fun `tight paragraph merged by smoothing is decomposed again`() {
        val w = 320
        val h = 170
        val mask = BooleanArray(w * h)
        // 两行只隔 2px：平滑会把缝抹掉，行投影变成一个 46px 的带，交给 RowDecomposer 拆回去。
        glyphRow(mask, w, 10, 22, 20, 300)
        glyphRow(mask, w, 34, 22, 20, 300)
        glyphRow(mask, w, 80, 22, 20, 300)
        glyphRow(mask, w, 120, 22, 20, 300)

        val layout = LayoutAnalyzer(AnalysisOptions()).analyze(gray(mask, w, h))
        println("rowInk=" + (6..36).joinToString { y ->
            var c = 0
            for (x in 0 until w) if (mask[y * w + x]) c++
            "$y:$c"
        })
        println("rawBands=" + segmenter.segment(mask, w, h).bands.map { "${it.y0}..${it.y1}" })
        val lines = layout.textLines()
        println("lines=" + lines.map { "${it.band.top}..${it.band.bottom} ${it.role}" })
        assertEquals(4, lines.size)
        assertEquals(11, lines[0].band.top)
        assertEquals(31, lines[0].band.bottom)
        assertEquals(0, layout.blocks.count { it is Block.Figure })
    }

    @Test
    fun `photo band is not decomposed into strips`() {
        val w = 200
        val h = 260
        val mask = BooleanArray(w * h)
        for (y in 0 until 160) for (x in 10 until 190) if ((x * 7 + y * 13) % 10 != 0) mask[y * w + x] = true
        for (y in 180 until 202) for (x in 10 until 190) mask[y * w + x] = true
        for (y in 220 until 242) for (x in 10 until 190) mask[y * w + x] = true

        val decomposer = RowDecomposer()
        val band = RowBand(0, 160, 10, 190, 190 * 160)
        assertNull(decomposer.decompose(mask, w, band, 22f))

        val classifier = BlockClassifier()
        val page = classifier.classify(mask, w, h, listOf(band), 22f, ByteArray(w * h))
        val figures = page.blocks.filterIsInstance<Block.Figure>()
        assertEquals(1, figures.size)
        assertEquals(FigureKind.PHOTO, figures[0].kind)
    }

    @Test
    fun `normal band is not decomposed`() {
        val w = 200
        val h = 120
        val mask = BooleanArray(w * h)
        for (y in 20 until 42) for (x in 10 until 190) mask[y * w + x] = true
        val decomposer = RowDecomposer()
        assertNull(decomposer.decompose(mask, w, RowBand(20, 42, 10, 190, 180 * 22), 22f))
    }

    private fun gray(mask: BooleanArray, w: Int, h: Int): GrayImage {
        val argb = IntArray(w * h) { if (mask[it]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Grayscale.fromArgb(w, h, argb)
    }
}
