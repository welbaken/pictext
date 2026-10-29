package app.pictext.core

import app.pictext.core.analyze.ColumnInk
import app.pictext.core.analyze.ColumnSplitter
import app.pictext.core.analyze.WordGapFinder
import app.pictext.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1：拉丁/URL 行只能切在“整行零墨且足够宽”的空白上。
 *
 * 这条规则的目的是把“字缝”模型和“单词边界”分开：中文一行里到处是字缝，
 * 英文一行里字缝却落在单词内部。
 */
class LatinSplitTest {

    private val w = 300
    private val h = 40

    /** 一行拉丁文：每个“单词” 30px 宽（内部 2px 字母缝），词间距 8px。 */
    private fun latinLine(words: Int, x0: Int = 10): BooleanArray {
        val mask = BooleanArray(w * h)
        var x = x0
        repeat(words) {
            // 单词内部：3px 字母 + 2px 缝
            var xx = x
            repeat(6) {
                maskFill(mask, w, 8, 26, xx, xx + 3)
                xx += 5
            }
            x += 30 + 8
        }
        return mask
    }

    @Test
    fun `gap finder cuts at the word space, not inside a word`() {
        val mask = latinLine(5)
        val band = RectI(10, 8, 200, 26)
        val ink = ColumnInk.of(mask, w, band)
        val cut = WordGapFinder.findCut(ink, center = 105, win = 30, minGapPx = 2.5f)
        assertNotNull("应能找到词间空白", cut)
        // 词间距 8px，切点必须落在 8px 里的某个位置，而不是单词内部。
        val inWord = (cut!! - 10) % 38
        println("cut=$cut inWord=$inWord")
        assertTrue("cut=$cut 应落在词间空白", inWord in 29..37)
    }

    @Test
    fun `url without spaces yields no cut`() {
        val mask = BooleanArray(w * h)
        // 连续 3–4px 字母，2px 缝，没有大于 2.5px 的空白
        var x = 10
        while (x + 4 <= 280) {
            maskFill(mask, w, 8, 26, x, x + 4)
            x += 6
        }
        val band = RectI(10, 8, 280, 26)
        val ink = ColumnInk.of(mask, w, band)
        assertNull(WordGapFinder.findCut(ink, center = 145, win = 40, minGapPx = 2.5f))
    }

    @Test
    fun `splitsWord is true inside a word and false at a space`() {
        val mask = latinLine(5)
        val band = RectI(10, 8, 200, 26)
        val ink = ColumnInk.of(mask, w, band)
        // 第 1 个词内部的字母缝：x = 10 + 5*k
        assertTrue("词内切点应被拒绝", ink.splitsWord(17, 22f))
        // 词间距中心（第 1 个词结束于 10+30，空白 40..47）。
        // 拉丁行会传入“宽空白豁免”（1.5 × 本行空白中位数），词间空格靠它放行。
        val exempt = maxOf(3, (1.5f * ink.blankRunMedian).toInt())
        assertFalse("词间切点应被放行", ink.splitsWord(44, 22f, exempt))
        // 没有豁免时，两侧都是窄字母簇，保守地判为“劈词”。
        assertTrue(ink.splitsWord(44, 22f))
    }

    @Test
    fun `cjk line never reports a word split`() {
        val cjk = BooleanArray(w * h)
        drawGlyphRow(cjk, w, 8, 22, 10, 290)
        val band = RectI(10, 8, 290, 30)
        val ink = ColumnInk.of(cjk, w, band)
        for (x in 20..280) {
            assertFalse("x=$x", ink.splitsWord(x, 22f))
        }
    }

    @Test
    fun `splitter refuses to cut a latin word when the experiment is off`() {
        val mask = latinLine(6, x0 = 10)
        val splitter = ColumnSplitter()
        val metrics = app.pictext.core.analyze.PageMetrics(
            textLeft = 10,
            textRight = 260,
            medianLineHeight = 22f,
            medianGap = 8f,
            medianPitch = 30f,
            longLineCount = 3,
            roughLeft = 10,
            roughRight = 260,
        )
        val lines = listOf(
            app.pictext.core.model.TextLine(RectI(10, 8, 260, 26)),
            app.pictext.core.model.TextLine(RectI(10, 8, 260, 26)),
        )
        val result = splitter.split(
            mask, w, h,
            listOf(app.pictext.core.model.Block.Paragraph(lines)),
            metrics,
        )
        assertTrue(result.weak)
        assertEquals(1, result.blocks.size)
    }
}
