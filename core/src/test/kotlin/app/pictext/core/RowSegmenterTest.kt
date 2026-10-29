package app.pictext.core

import app.pictext.core.analyze.RowSegmenter
import app.pictext.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowSegmenterTest {

    private val segmenter = RowSegmenter()

    @Test
    fun `three equal bars produce three bands`() {
        val w = 100
        val h = 60
        val mask = maskFromRects(
            w, h,
            RectI(10, 5, 90, 10),
            RectI(10, 20, 90, 25),
            RectI(10, 35, 90, 40),
        )
        val r = segmenter.segment(mask, w, h)
        assertEquals(3, r.bands.size)
        assertTrue(Math.abs(r.bands[0].y0 - 5) <= 2)
        assertTrue(Math.abs(r.bands[1].y0 - 20) <= 2)
        assertTrue(Math.abs(r.bands[2].y0 - 35) <= 2)
        assertEquals(10, r.bands[0].x0)
        assertEquals(90, r.bands[0].x1)
    }

    @Test
    fun `noise below absolute threshold creates no band`() {
        val w = 100
        val h = 40
        val mask = BooleanArray(w * h)
        mask[20 * w + 10] = true
        mask[20 * w + 50] = true
        val r = segmenter.segment(mask, w, h)
        assertEquals(0, r.bands.size)
    }

    @Test
    fun `caption with one fifth ink is still detected`() {
        val w = 200
        val h = 80
        val mask = BooleanArray(w * h)
        // 正文行：墨量 100
        for (x in 20 until 120) {
            mask[10 * w + x] = true
            mask[30 * w + x] = true
        }
        // 图注行：墨量 20（正文的 1/5），高于绝对阈值 3
        for (x in 60 until 80) mask[50 * w + x] = true
        val r = segmenter.segment(mask, w, h)
        assertEquals(3, r.bands.size)
        assertTrue(r.bands.any { it.y0 in 48..52 })
    }

    @Test
    fun `upper and lower strokes merge into one band`() {
        val w = 100
        val h = 40
        val mask = maskFromRects(
            w, h,
            RectI(10, 10, 90, 14),
            RectI(10, 15, 90, 19), // 中间 1px 空白
        )
        val r = segmenter.segment(mask, w, h)
        assertEquals(1, r.bands.size)
    }
}
