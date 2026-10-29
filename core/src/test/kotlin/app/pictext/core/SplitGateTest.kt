package app.pictext.core

import app.pictext.core.analyze.ColumnSplitter
import app.pictext.core.analyze.PageMetrics
import app.pictext.core.model.Block
import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3 切分安全门 + P1-1 拉丁保护。
 *
 * 只有“左对齐版心、够宽、不是 UI”的整行正文才会被切；拉丁/代码行在没有开启实验时
 * 一律不切，并计入 `cleanRatio` 分母，把整页推向安全的回退。
 */
class SplitGateTest {

    private val splitter = ColumnSplitter()
    private val w = 400
    private val h = 200

    private fun metrics() = PageMetrics(
        textLeft = 10,
        textRight = 390,
        medianLineHeight = 22f,
        medianGap = 10f,
        medianPitch = 32f,
        longLineCount = 3,
        roughLeft = 10,
        roughRight = 390,
    )

    private fun block(vararg lines: TextLine): List<Block> = listOf(Block.Paragraph(lines.toList()))

    private fun lines(mask: BooleanArray, rows: List<Triple<Int, Int, IntRange>>): List<TextLine> =
        rows.map { (y0, y1, xs) ->
            var ink = 0
            for (y in y0 until y1) for (x in xs) if (mask[y * w + x]) ink++
            TextLine(RectI(xs.first, y0, xs.last + 1, y1))
        }

    /** 拉丁/代码行：细窄字母簇，簇间距 6px。 */
    private fun latinRow(mask: BooleanArray, y0: Int, x0: Int, x1: Int) {
        var x = x0
        while (x + 4 <= x1) {
            maskFill(mask, w, y0 + 3, y0 + 17, x, x + 3)
            x += 8
        }
    }

    @Test
    fun `full width body rows are split`() {
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 22, 10, 390)
        drawGlyphRow(mask, w, 45, 22, 10, 390)
        drawGlyphRow(mask, w, 80, 22, 10, 390)
        val lines = lines(mask, listOf(Triple(10, 32, 10..389), Triple(45, 67, 10..389), Triple(80, 102, 10..389)))
        val result = splitter.split(mask, w, h, block(*lines.toTypedArray()), metrics())
        println("splitX=${result.splitX} clean=${result.cleanRatio} blocks=${result.blocks}")
        assertFalse(result.weak)
        assertTrue(result.blocks.flatMap { (it as Block.Paragraph).lines }.all { it.split })
    }

    @Test
    fun `indented quote row is not split`() {
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 22, 10, 390)
        drawGlyphRow(mask, w, 45, 22, 10, 390)
        drawGlyphRow(mask, w, 80, 22, 80, 390)
        val lines = listOf(
            TextLine(RectI(10, 10, 390, 32)),
            TextLine(RectI(10, 45, 390, 67)),
            TextLine(RectI(80, 80, 390, 102)),
        )
        val result = splitter.split(mask, w, h, block(*lines.toTypedArray()), metrics())
        val updated = result.blocks.flatMap { (it as Block.Paragraph).lines }
        assertTrue(updated[0].split)
        assertFalse("缩进行不应参与切分", updated[2].split)
        assertEquals(-1, updated[2].cutX)
    }

    @Test
    fun `ui row is never split`() {
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 22, 10, 390)
        var x = 10
        while (x + 30 <= 390) {
            maskFill(mask, w, 55, 77, x, x + 10)
            maskFill(mask, w, 59, 69, x + 16, x + 30)
            x += 90
        }
        val lines = listOf(
            TextLine(RectI(10, 10, 390, 32)),
            TextLine(RectI(10, 55, 370, 77), role = LineRole.UI),
        )
        val result = splitter.split(mask, w, h, block(*lines.toTypedArray()), metrics())
        val updated = result.blocks.flatMap { (it as Block.Paragraph).lines }
        assertFalse(updated[1].split)
    }

    @Test
    fun `latin rows are refused and push the page to weak`() {
        val mask = BooleanArray(w * h)
        latinRow(mask, 10, 10, 390)
        latinRow(mask, 45, 10, 390)
        latinRow(mask, 80, 10, 390)
        val lines = listOf(
            TextLine(RectI(10, 10, 390, 32)),
            TextLine(RectI(10, 45, 390, 67)),
            TextLine(RectI(10, 80, 390, 102)),
        )
        val result = splitter.split(mask, w, h, block(*lines.toTypedArray()), metrics())
        println("latin weak=${result.weak} clean=${result.cleanRatio}")
        assertTrue("拉丁行不应被切开", result.blocks.flatMap { (it as Block.Paragraph).lines }
            .none { it.split })
        assertEquals(0f, result.cleanRatio, 0.001f)
        assertTrue(result.weak)
    }

    @Test
    fun `chinese rows with a latin row still split`() {
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 22, 10, 390)
        drawGlyphRow(mask, w, 45, 22, 10, 390)
        drawGlyphRow(mask, w, 80, 22, 10, 390)
        latinRow(mask, 115, 10, 390)
        val lines = listOf(
            TextLine(RectI(10, 10, 390, 32)),
            TextLine(RectI(10, 45, 390, 67)),
            TextLine(RectI(10, 80, 390, 102)),
            TextLine(RectI(10, 115, 390, 137)),
        )
        val result = splitter.split(mask, w, h, block(*lines.toTypedArray()), metrics())
        println("mixed clean=${result.cleanRatio} weak=${result.weak}")
        assertFalse(result.weak)
        assertEquals(0.75f, result.cleanRatio, 0.01f)
        val updated = result.blocks.flatMap { (it as Block.Paragraph).lines }
        assertTrue(updated.take(3).all { it.split })
        assertFalse(updated[3].split)
    }
}
