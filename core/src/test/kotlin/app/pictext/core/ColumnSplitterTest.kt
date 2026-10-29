package app.pictext.core

import app.pictext.core.analyze.ColumnSplitter
import app.pictext.core.analyze.PageMetrics
import app.pictext.core.model.Block
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnSplitterTest {

    private val splitter = ColumnSplitter()
    private val w = 220
    private val h = 120

    private fun metrics() = PageMetrics(
        textLeft = 10,
        textRight = 190,
        medianLineHeight = 20f,
        medianGap = 10f,
        medianPitch = 30f,
        longLineCount = 3,
        roughLeft = 10,
        roughRight = 190,
    )

    private fun addGlyphRow(mask: BooleanArray, row: IntRange, shift: Int = 0) {
        for (x in listOf(10, 40, 70, 100, 130, 160)) {
            val x0 = x + shift
            for (y in row) for (xx in x0 until x0 + 20) if (xx in 0 until w) mask[y * w + xx] = true
        }
    }

    private fun glyphMask(rows: List<IntRange>, shift: Int = 0): BooleanArray {
        val mask = BooleanArray(w * h)
        for (row in rows) addGlyphRow(mask, row, shift)
        return mask
    }

    private fun blocks(vararg lines: TextLine): List<Block> = listOf(Block.Paragraph(lines.toList()))

    @Test
    fun `cut lands in the character gap near the center`() {
        val rows = listOf(10..30, 45..65, 80..100)
        val mask = glyphMask(rows)
        val lines = rows.mapIndexed { i, r -> TextLine(RectI(10, r.first, 190, r.last + 1)) }
        val result = splitter.split(mask, w, h, blocks(*lines.toTypedArray()), metrics())
        assertTrue("splitX=${result.splitX}", result.splitX in 90..100)
        assertEquals(1f, result.cleanRatio, 0.001f)
        assertFalse(result.weak)
        assertTrue(result.blocks.flatMap { (it as Block.Paragraph).lines }.all { it.split })
    }

    @Test
    fun `shifted row keeps its own gap`() {
        // 最后一行整体右移 11px：它不左对齐版心，因此不参与切分（P0-3 安全门），
        // 但逐行找缝仍然要落在它自己的字缝上。
        val m = BooleanArray(w * h)
        addGlyphRow(m, 0..20)
        addGlyphRow(m, 35..55)
        addGlyphRow(m, 70..90, shift = 11)
        val lines = listOf(
            TextLine(RectI(10, 0, 190, 21)),
            TextLine(RectI(10, 35, 190, 56)),
            TextLine(RectI(21, 70, 201, 91)),
        )
        val result = splitter.split(m, w, h, blocks(*lines.toTypedArray()), metrics())
        val updated = result.blocks.flatMap { (it as Block.Paragraph).lines }
        assertEquals(1f, result.cleanRatio, 0.001f)
        assertTrue(updated[0].split)
        assertFalse("右移的行不左对齐版心，不应被切开", updated[2].split)

        // 找缝本身是正确的：在切点附近窗口里仍然能落在字缝上。
        val ink = app.pictext.core.analyze.ColumnInk.of(m, w, updated[2].band)
        val cut = splitter.gapNear(ink, center = result.splitX, win = 8, medianHeight = 20f)
        assertTrue("cut=$cut", cut != null && cut.first >= 100)
    }

    @Test
    fun `centered short title is not split`() {
        val rows = listOf(10..30, 45..65)
        val mask = glyphMask(rows)
        val lines = listOf(
            TextLine(RectI(10, 10, 190, 31)),
            TextLine(RectI(10, 45, 190, 66)),
            TextLine(RectI(70, 90, 130, 110)),
        )
        val result = splitter.split(mask, w, h, blocks(*lines.toTypedArray()), metrics())
        val title = result.blocks.flatMap { (it as Block.Paragraph).lines }[2]
        assertFalse(title.split)
        assertTrue(title.centered)
    }

    @Test
    fun `solid rows without gaps fall back to weak cut`() {
        val mask = BooleanArray(w * h)
        for (y in 10 until 100) for (x in 10 until 190) mask[y * w + x] = true
        val lines = listOf(
            TextLine(RectI(10, 10, 190, 31)),
            TextLine(RectI(10, 45, 190, 66)),
            TextLine(RectI(10, 80, 190, 101)),
        )
        val result = splitter.split(mask, w, h, blocks(*lines.toTypedArray()), metrics())
        assertTrue(result.weak)
        assertTrue(result.cleanRatio < 0.70f)
        assertTrue(result.blocks.flatMap { (it as Block.Paragraph).lines }.none { it.split })
    }

    @Test
    fun `too few long lines is weak`() {
        val mask = glyphMask(listOf(10..30))
        val result = splitter.split(
            mask, w, h,
            blocks(TextLine(RectI(10, 10, 190, 31))),
            metrics().copy(longLineCount = 1),
        )
        assertTrue(result.weak)
    }
}
