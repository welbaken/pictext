package app.pictext.core.analyze

import app.pictext.core.util.Runs
import app.pictext.core.util.Stats
import kotlin.math.max

/**
 * P0-2 的第一步：把“仍然太高”的行带再分解成多行。
 *
 * P0-1 的合并护栏按总高度拦住了两行正文，但紧排的标题（两行 34px 字、行距 0）本来就是
 * 一个连续墨带，只能靠带内的行投影谷值再切。规则很保守：
 *
 * 1. 谷值行的墨量 < `rowValleyInkRatio × 带内行墨中位数`；
 * 2. 子行高度落在 `[0.5, 1.8] × 行高中位数`；
 * 3. 子行间距的变异系数 < 0.25，且子行宽度不能比带窄太多；
 * 4. 任何一条不满足就返回 `null`，调用方保持原状（图块/UI），**绝不硬切**。
 *
 * 宁可把一段紧排标题留在原地，也不要把照片、图标切成横条。
 */
class RowDecomposer(private val options: AnalysisOptions = AnalysisOptions()) {

    fun decompose(
        mask: BooleanArray,
        width: Int,
        band: RowBand,
        medianHeight: Float,
    ): List<RowBand>? {
        val bandHeight = band.y1 - band.y0
        if (bandHeight <= 0 || medianHeight <= 0f || band.width <= 0) return null

        val rowInk = FloatArray(bandHeight)
        for (y in 0 until bandHeight) {
            val base = (band.y0 + y) * width
            var c = 0
            for (x in band.x0 until band.x1) if (mask[base + x]) c++
            rowInk[y] = c.toFloat()
        }
        // 不做平滑：行带之所以被合并，恰恰是因为 1–2 行的空白被平滑抹掉了，
        // 这里要的正是这些原始谷值。
        val smooth = rowInk
        val positive = smooth.filter { it > 0f }.sorted()
        if (positive.size < 2) return null
        val median = positive[positive.size / 2]
        val valley = max(1f, options.rowValleyInkRatio * median)
        val flags = BooleanArray(bandHeight) { smooth[it] >= valley }
        val runs = Runs.runsOf(flags)
        if (runs.size < 2) return null

        val subBands = ArrayList<RowBand>(runs.size)
        for (r in runs) {
            val y0 = band.y0 + r.first
            val y1 = band.y0 + r.last + 1
            val h = y1 - y0
            if (h < options.rowSubMinHeightRatio * medianHeight) continue
            if (h > options.rowSubMaxHeightRatio * medianHeight) return null
            var x0 = band.x1
            var x1 = band.x0
            var ink = 0
            for (y in y0 until y1) {
                val base = y * width
                for (x in band.x0 until band.x1) {
                    if (mask[base + x]) {
                        ink++
                        if (x < x0) x0 = x
                        if (x + 1 > x1) x1 = x + 1
                    }
                }
            }
            if (x1 <= x0) return null
            subBands.add(RowBand(y0, y1, x0, x1, ink))
        }
        if (subBands.size < 2) return null

        // 子行不能比原带窄太多：正文行彼此宽度接近，照片/图标的碎片不会。
        val bandWidth = band.width
        for (sub in subBands) {
            if (sub.width < 0.25f * bandWidth) return null
        }
        val widths = IntArray(subBands.size) { subBands[it].width }
        if (Stats.median(widths) < 0.5f * bandWidth) return null

        // 行距（子行上沿到上沿）必须稳定，否则更像图形而不是文字。
        if (subBands.size >= 3) {
            val pitches = IntArray(subBands.size - 1) { subBands[it + 1].y0 - subBands[it].y0 }
            if (Stats.coefficientOfVariation(pitches) > options.rowSubMaxCv) return null
        } else {
            val pitch = subBands[1].y0 - subBands[0].y0
            if (pitch > 2.4f * medianHeight) return null
        }
        return subBands
    }
}
