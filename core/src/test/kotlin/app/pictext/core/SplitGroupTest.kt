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
import kotlin.math.abs

/**
 * C2：按段落左边距分组，同缩进的段落共用一根切分列。
 *
 * 缩进引用/列表项以前因为“不左对齐版心”被整组排除，只能被压进列宽
 * （audit 的 `body_scale_inconsistent`）；现在它们有自己的列，可以正常对半切。
 * 孤立的一行缩进行仍然不切（避免把聊天气泡、右移的单行标签劈开）。
 */
class SplitGroupTest {

    private val splitter = ColumnSplitter()
    private val w = 400
    private val h = 220

    private fun metrics() = PageMetrics(
        textLeft = 10,
        textRight = 390,
        medianLineHeight = 22f,
        medianGap = 10f,
        medianPitch = 32f,
        longLineCount = 5,
        roughLeft = 10,
        roughRight = 390,
    )

    private fun block(vararg lines: TextLine): List<Block> = listOf(Block.Paragraph(lines.toList()))

    @Test
    fun `indented paragraph group gets its own column`() {
        val mask = BooleanArray(w * h)
        // 两行普通正文（版心 10..390）
        drawGlyphRow(mask, w, 10, 22, 10, 390)
        drawGlyphRow(mask, w, 45, 22, 10, 390)
        // 三行缩进引用（80..390）
        drawGlyphRow(mask, w, 80, 22, 80, 390)
        drawGlyphRow(mask, w, 115, 22, 80, 390)
        drawGlyphRow(mask, w, 150, 22, 80, 390)
        val lines = listOf(
            TextLine(RectI(10, 10, 390, 32)),
            TextLine(RectI(10, 45, 390, 67)),
            TextLine(RectI(80, 80, 390, 102)),
            TextLine(RectI(80, 115, 390, 137)),
            TextLine(RectI(80, 150, 390, 172)),
        )
        val result = splitter.split(mask, w, h, block(*lines.toTypedArray()), metrics())
        val updated = result.blocks.flatMap { (it as Block.Paragraph).lines }

        assertFalse("整页不应判 weak: clean=${result.cleanRatio}", result.weak)
        assertTrue("普通正文应被切", updated[0].split && updated[1].split)
        assertTrue("缩进组也应被切", updated[2].split && updated[3].split && updated[4].split)
        // 缩进组的列应当在它自己的中线（(80+390)/2 = 235）附近，而不是页面中线 200。
        assertTrue("缩进组切点: ${updated[2].cutX}", abs(updated[2].cutX - 235) <= 30)
        assertTrue("正文切点: ${updated[0].cutX}", abs(updated[0].cutX - 200) <= 30)
    }

    @Test
    fun `lone indented row still keeps the page column`() {
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
        assertFalse("孤立缩进行不应被单独劈开", updated[2].split)
        assertEquals(-1, updated[2].cutX)
    }

    @Test
    fun `centered rows never form a group`() {
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 22, 10, 390)
        drawGlyphRow(mask, w, 45, 22, 10, 390)
        // 两行居中标题：左右留白对称，各自 left 不同
        drawGlyphRow(mask, w, 80, 22, 100, 300)
        drawGlyphRow(mask, w, 115, 22, 110, 290)
        val lines = listOf(
            TextLine(RectI(10, 10, 390, 32)),
            TextLine(RectI(10, 45, 390, 67)),
            TextLine(RectI(100, 80, 300, 102)),
            TextLine(RectI(110, 115, 290, 137)),
        )
        val result = splitter.split(mask, w, h, block(*lines.toTypedArray()), metrics())
        val updated = result.blocks.flatMap { (it as Block.Paragraph).lines }
        assertTrue(updated[2].centered && updated[3].centered)
        assertFalse("居中标题不切", updated[2].split || updated[3].split)
    }
}
