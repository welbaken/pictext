package app.pictext.core.analyze

import app.pictext.core.util.MovingAverage
import app.pictext.core.util.Runs
import app.pictext.core.util.Stats
import kotlin.math.max

/** 一条原始行带，坐标为 A；`y1/x1` 为开区间。 */
data class RowBand(
    val y0: Int,
    val y1: Int,
    val x0: Int,
    val x1: Int,
    val inkCount: Int,
) {
    val height: Int get() = y1 - y0
    val width: Int get() = x1 - x0
    val top: Int get() = y0
    val bottom: Int get() = y1
}

data class RowSegmentResult(
    val bands: List<RowBand>,
    val medianHeight: Float,
    /** 被判为“细竖直装饰线”的列（引用竖线、分隔线）；已从 [contentMask] 清除。 */
    val decorationColumns: BooleanArray = BooleanArray(0),
    /** 去掉装饰列之后的墨迹图；后续所有步骤都用它，避免一条竖线把多行粘成一块。 */
    val contentMask: BooleanArray = BooleanArray(0),
)

/**
 * Step 4：水平投影切行。
 *
 * 阈值必须是绝对墨量（`max(3, 0.003 × W)`），不能用相对峰值：图注、页码的墨量只有
 * 正文的几分之一，相对阈值会把整行小字判成空白，直接丢内容。
 *
 * v1.1 的两个护栏：
 * 1. **合并总高度上限**（`rowMergeHeightRatio = 1.25`）：只允许“同一个字的上下结构”合并，
 *    不允许把两行正文并成一个大带。没有这个上限时，行间距 0.45×行高 的紧排正文会被
 *    整个吞进一个带里，随后又因为“带太高”被判成图块——这是 Twitter 三个伪图块的上游根因。
 * 2. **细竖直装饰列**（引用竖线、分隔线）：这类列在整段引用里每一行都有墨，会让行投影
 *    永远不出现空白行，同样把多行粘成一个带。检测出来后从内容墨迹图里清除，
 *    行投影与横向间隙计算都不再受它影响。
 */
class RowSegmenter(private val options: AnalysisOptions = AnalysisOptions()) {

    fun segment(mask: BooleanArray, width: Int, height: Int): RowSegmentResult {
        if (width <= 0 || height <= 0 || mask.isEmpty()) {
            return RowSegmentResult(emptyList(), 0f, BooleanArray(0), mask)
        }

        val threshold = max(3f, options.rowMinInkPx * width)

        // 第一遍：整图投影，只为拿到“典型行高”与候选装饰列。
        val firstInk = project(mask, width, height)
        val firstSmooth = MovingAverage.smooth3(firstInk)
        val firstRuns = Runs.runsOf(BooleanArray(height) { firstSmooth[it] >= threshold })
        if (firstRuns.isEmpty()) return RowSegmentResult(emptyList(), 0f, BooleanArray(0), mask)
        val roughMedH = Stats.robustMedian(IntArray(firstRuns.size) { firstRuns[it].last - firstRuns[it].first + 1 })

        // 第二遍：忽略细竖直装饰列后重新投影。
        val decoration = detectDecorationColumns(mask, width, height, roughMedH)
        val content = if (decoration.isEmpty() || !decoration.any { it }) {
            mask
        } else {
            clearColumns(mask, width, height, decoration)
        }

        val rowInk = project(content, width, height)
        val smooth = MovingAverage.smooth3(rowInk)
        val raw = Runs.runsOf(BooleanArray(height) { smooth[it] >= threshold })
        if (raw.isEmpty()) return RowSegmentResult(emptyList(), 0f, decoration, content)

        var medH = Stats.robustMedian(IntArray(raw.size) { raw[it].last - raw[it].first + 1 })
        if (medH <= 0f) medH = 1f
        val maxGap = max(2, (options.rowMergeGapRatio * medH).toInt())
        val maxHeight = max(2, (options.rowMergeHeightRatio * medH).toInt())
        val merged = mergeWithHeightCap(raw, maxGap, maxHeight)
        val kept = merged.filter { (it.last - it.first + 1) >= options.rowMinHeightRatio * medH }
        if (kept.isEmpty()) return RowSegmentResult(emptyList(), 0f, decoration, content)

        val keptHeights = IntArray(kept.size) { kept[it].last - kept[it].first + 1 }
        medH = Stats.robustMedian(keptHeights, fallback = medH.let { it.toInt() })

        val bands = ArrayList<RowBand>(kept.size)
        for (r in kept) {
            val y0 = r.first
            val y1 = r.last + 1
            var x0 = width
            var x1 = 0
            var ink = 0
            for (y in y0 until y1) {
                val base = y * width
                for (x in 0 until width) {
                    if (content[base + x]) {
                        ink++
                        if (x < x0) x0 = x
                        if (x + 1 > x1) x1 = x + 1
                    }
                }
            }
            if (x0 > x1) x0 = 0
            bands.add(RowBand(y0, y1, x0, x1, ink))
        }
        return RowSegmentResult(bands, medH, decoration, content)
    }

    private fun project(mask: BooleanArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(height)
        for (y in 0 until height) {
            val base = y * width
            var c = 0
            for (x in 0 until width) {
                if (mask[base + x]) c++
            }
            out[y] = c.toFloat()
        }
        return out
    }

    private fun clearColumns(mask: BooleanArray, width: Int, height: Int, decoration: BooleanArray): BooleanArray {
        val out = mask.copyOf()
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) if (decoration[x]) out[base + x] = false
        }
        return out
    }

    /**
     * 合并相邻游程，但同时限制合并后的总高度。
     *
     * `maxGap` 负责“同一个字的上下结构”（两点水、上下结构的空隙）；
     * `maxHeight` 负责“不要把两行正文并成一个带”。两者同时成立才合并。
     */
    internal fun mergeWithHeightCap(runs: List<IntRange>, maxGap: Int, maxHeight: Int): List<IntRange> {
        if (runs.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>(runs.size)
        var first = runs[0].first
        var last = runs[0].last
        for (i in 1 until runs.size) {
            val r = runs[i]
            val gap = r.first - last - 1
            val mergedHeight = r.last - first + 1
            if (gap <= maxGap && mergedHeight <= maxHeight) {
                last = r.last
            } else {
                out.add(first..last)
                first = r.first
                last = r.last
            }
        }
        out.add(first..last)
        return out
    }

    /**
     * 找出“细、长、贴边留白”的竖直列组：引用竖线、分隔线。
     *
     * 三个条件缺一不可，避免误伤正文和表格：
     * 1. 该列存在远超“典型字母/汉字竖笔”的连续竖直墨迹——典型值取全图各列最长游程的中位数，
     *    所以不依赖行高估计（行高本身会被这条线带偏）；
     * 2. 列组宽度 ≤ max(3, 0.5% 页宽)——表格竖线、照片边缘通常更宽；
     * 3. 列组至少一侧有 ≥ 0.5×典型字高的纯空白列——表格边框紧贴单元格内容，两侧都不满足。
     */
    private fun detectDecorationColumns(
        mask: BooleanArray,
        width: Int,
        height: Int,
        medH: Float,
    ): BooleanArray {
        val result = BooleanArray(width)
        if (width <= 0 || height <= 0) return result
        val longest = IntArray(width)
        val run = IntArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                run[x] = if (mask[base + x]) run[x] + 1 else 0
                if (run[x] > longest[x]) longest[x] = run[x]
            }
        }
        val positive = longest.filter { it > 0 }
        if (positive.isEmpty()) return result
        val typical = positive.sorted()[positive.size / 2]
        // 行高会被“被竖线粘住的带”抬高，所以取 medH 与典型竖笔长度的较小者，再给一个下限。
        val glyph = minOf(medH.toInt().takeIf { it > 0 } ?: typical, typical).coerceAtLeast(4)
        val minRun = max(10, max((0.15f * height).toInt(), 3 * glyph))
        val colInk = IntArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) if (mask[base + x]) colInk[x]++
        }
        val maxWidth = max(3, (options.decorMaxWidthRatio * width).toInt())
        val minBlank = max(2, (options.decorMinBlankRatio * glyph).toInt())

        var x = 0
        while (x < width) {
            if (longest[x] < minRun) {
                x++
                continue
            }
            var x1 = x
            while (x1 + 1 < width && longest[x1 + 1] >= minRun) x1++
            val groupWidth = x1 - x + 1
            if (groupWidth <= maxWidth) {
                var leftBlank = 0
                var i = x - 1
                while (i >= 0 && colInk[i] == 0 && leftBlank < minBlank) {
                    leftBlank++
                    i--
                }
                var rightBlank = 0
                i = x1 + 1
                while (i < width && colInk[i] == 0 && rightBlank < minBlank) {
                    rightBlank++
                    i++
                }
                if (leftBlank >= minBlank || rightBlank >= minBlank) {
                    for (k in x..x1) result[k] = true
                }
            }
            x = x1 + 1
        }
        return result
    }
}
