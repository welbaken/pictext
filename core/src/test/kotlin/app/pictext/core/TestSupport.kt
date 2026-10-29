package app.pictext.core

import app.pictext.core.analyze.RowBand
import app.pictext.core.model.GrayImage
import app.pictext.core.model.RectI
import app.pictext.core.util.Grayscale

fun grayPage(w: Int, h: Int, value: Int = 255): GrayImage =
    GrayImage.solid(w, h, value)

fun GrayImage.fillRect(x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
    for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) {
        for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
            data[y * width + x] = value.coerceIn(0, 255).toByte()
        }
    }
}

fun maskFromRects(w: Int, h: Int, vararg rects: RectI): BooleanArray {
    val mask = BooleanArray(w * h)
    for (r in rects) {
        for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(h)) {
            for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(w)) {
                mask[y * w + x] = true
            }
        }
    }
    return mask
}

fun maskFill(mask: BooleanArray, w: Int, y0: Int, y1: Int, x0: Int, x1: Int) {
    for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(mask.size / w)) {
        for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
            if (x in 0 until w) mask[y * w + x] = true
        }
    }
}

/**
 * 画一行“伪文字”：每 `glyph` 列一个字形（两竖笔 + 一横），密度接近真实正文，
 * 不会被密度判据当成图块。用于合成行结构/角色/切分用例。
 */
fun drawGlyphRow(mask: BooleanArray, w: Int, y0: Int, height: Int, x0: Int, x1: Int) {
    val h = mask.size / w
    val pad = maxOf(2, height / 8)
    val glyph = maxOf(8, height)
    var x = x0
    while (x + glyph <= x1) {
        for (dy in pad until height - pad) {
            val y = y0 + dy
            if (y !in 0 until h) continue
            for (dx in 2 until 5) {
                val xx = x + dx
                if (xx in 0 until w) mask[y * w + xx] = true
            }
            for (dx in glyph - 4 until glyph - 1) {
                val xx = x + dx
                if (xx in 0 until w) mask[y * w + xx] = true
            }
        }
        val my = y0 + height / 2
        if (my in 0 until h) {
            for (dx in 4 until glyph - 2) {
                val xx = x + dx
                if (xx in 0 until w) mask[my * w + xx] = true
            }
        }
        x += glyph + 2
    }
}

/** 由 mask 的某个矩形区域生成行带（墨量按 mask 统计）。 */
fun bandOf(mask: BooleanArray, w: Int, y0: Int, y1: Int, x0: Int, x1: Int): RowBand {
    var ink = 0
    for (y in y0 until y1) {
        val base = y * w
        for (x in x0 until x1) if (mask[base + x]) ink++
    }
    return RowBand(y0, y1, x0, x1, ink)
}

fun grayFromMask(mask: BooleanArray, w: Int, h: Int): GrayImage {
    val argb = IntArray(w * h) { if (mask[it]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Grayscale.fromArgb(w, h, argb)
}
