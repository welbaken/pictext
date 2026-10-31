package app.pictext.core

import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.GrayImage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长图内存回归：1080×20000（21.6MP）。
 *
 * 只在 `:core:longPageMemoryTest`（`-Xmx96m`）里跑，普通 `:core:test` 会排除它。
 *
 * 常驻口径：整页灰度 21.6MB + 2 条并发 ×（ink 3.2 + mask/cleaned 6.5 + `ShortArray` 游程 6.5）
 * ≈ **54MB**，所以 96MB 的堆留了接近 2 倍的余量。
 *
 * 实测（2026-10-31，同机）：
 * - 自动并发上限 2（现值）+ 逐条带取用 + `ShortArray` 游程 → **通过**；
 * - 把自动并发上限改回 4（0.5.1 之前的自动值）→ `OutOfMemoryError` 落在
 *   `BlockClassifier.verticalRuns`，即这条护栏确实能抓住"并行度放大内存"的回退；
 * - 恢复"先把整页切成条带列表"（`StripLayoutAnalyzer` 旧实现）会再多吃 18.7MB 整页拷贝，
 *   同样会顶爆。
 *
 * 注意页面必须用 [drawTextRow] 画"伪文字"而不是实心条：实心横条密度 > 0.62，
 * 会被 `BlockClassifier` 正确判成图块，那样测到的就不是文字链路的开销了。
 */
class LongPageMemoryTest {

    @Test
    fun `1080x20000 page analyzes within 96MB heap`() {
        val w = 1080
        val h = 20_000
        val image = grayPage(w, h, 255)
        var y = 24
        while (y + 16 < h) {
            drawTextRow(image, y, 16, 40, w - 40)
            y += 40
        }

        val layout = LayoutAnalyzer().analyze(image, w, h)

        assertTrue(
            "长图必须走条带分析: ${layout.diagnostics.notes}",
            layout.diagnostics.notes.any { it.startsWith("strip=") },
        )
        assertTrue("行数异常: ${layout.textLines().size}", layout.textLines().size > 400)
        assertTrue("版心不应塌成 0: left=${layout.textLeft} right=${layout.textRight}", layout.textRight > 900)
    }

    /** 与 `TestSupport.drawGlyphRow` 同构，但直接写灰度数组（避免整页 ARGB 临时数组）。 */
    private fun drawTextRow(img: GrayImage, y0: Int, height: Int, x0: Int, x1: Int) {
        val pad = maxOf(2, height / 8)
        val glyph = maxOf(8, height)
        var x = x0
        while (x + glyph <= x1) {
            for (dy in pad until height - pad) {
                val y = y0 + dy
                if (y !in 0 until img.height) continue
                for (dx in 2 until 5) setInk(img, x + dx, y)
                for (dx in glyph - 4 until glyph - 1) setInk(img, x + dx, y)
            }
            val my = y0 + height / 2
            if (my in 0 until img.height) {
                for (dx in 4 until glyph - 2) setInk(img, x + dx, my)
            }
            x += glyph + 2
        }
    }

    private fun setInk(img: GrayImage, x: Int, y: Int) {
        if (x in 0 until img.width && y in 0 until img.height) {
            img.data[y * img.width + x] = 20
        }
    }
}
