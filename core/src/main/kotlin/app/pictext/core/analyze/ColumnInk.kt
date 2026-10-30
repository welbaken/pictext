package app.pictext.core.analyze

import app.pictext.core.util.Stats
import kotlin.math.max
import kotlin.math.min

/**
 * 一条文字行的**列墨量剖面**：每一列在该行带内的墨点数。
 *
 * 找切点（字缝）、判断“这一刀会不会劈开西文单词”、以及拉丁实验的零墨空白，
 * 都基于同一份剖面，避免每个算法各扫一遍像素。
 */
class ColumnInk(val left: Int, val counts: IntArray) {

    val width: Int get() = counts.size

    private val clusterCache: List<IntRange> by lazy { computeClusters() }

    /** 整行空白组宽度中位数（拉丁行里约等于字母缝，中文行里约等于字缝）。 */
    val blankRunMedian: Int by lazy {
        val runs = ArrayList<Int>()
        var run = 0
        for (i in counts.indices) {
            if (counts[i] == 0) {
                run++
            } else {
                if (run > 0) runs.add(run)
                run = 0
            }
        }
        if (run > 0) runs.add(run)
        if (runs.isEmpty()) 0 else runs.sorted()[runs.size / 2]
    }

    /** 墨簇（连续的“有墨列”）在剖面内的区间。 */
    fun clusters(): List<IntRange> = clusterCache

    /**
     * 低墨列阈值：`max(1, 0.10 × 非零列墨量中位数)`。
     *
     * 找字缝时用它判“低墨列组”。一次分析里同一条行会被找好几次刀（×2 一刀、×4 二次
     * 重排两刀），所以缓存下来，避免每次都排序整条剖面。
     */
    val lowInkThreshold: Float by lazy {
        val positive = counts.filter { it > 0 }.sorted()
        if (positive.isEmpty()) 1f else max(1f, 0.10f * positive[positive.size / 2])
    }

    /** 墨簇宽度中位数；没有墨簇时为 0。 */
    fun medianClusterWidth(): Float {
        val c = clusterCache
        if (c.isEmpty()) return 0f
        return Stats.median(IntArray(c.size) { c[it].last - c[it].first + 1 })
    }

    /**
     * 每“一个字宽”里有几个墨簇。
     *
     * 中文/日文一行大约 1 个墨簇/字；拉丁字母（以及等宽代码）通常 2–4 个/字宽。
     * 只靠“簇很窄”会把粗体中文误判成拉丁文（粗体笔画之间也会留下窄缝），
     * 两个条件一起用才稳。
     */
    fun clustersPerEm(unit: Float): Float {
        if (unit <= 0f || width <= 0) return 0f
        val ems = width / unit
        if (ems <= 0f) return 0f
        return clusterCache.size / ems
    }

    /**
     * [x] 处的切点是否“劈开了一个字/单词”。
     *
     * 判据：切点两侧**紧邻的两个墨簇**都明显窄于典型汉字簇。
     * 汉字/假名的一簇约 1 个行高；拉丁字母、汉字偏旁（弓/丨、扌/丁）只有 0.2–0.6 个行高。
     * 只有两侧都窄才判为劈开——切在“，”和汉字之间时，汉字一侧仍是一个整簇，不会误伤。
     *
     * 两侧都窄时还有一条豁免：空白本身 ≥ 半个字宽，说明那是标点/空格边界而不是笔画缝隙。
     *
     * @param gapExemptPx 额外豁免门槛（拉丁行用“1.5×本行空白中位数”，区分词间空格与字母缝）；
     *   中文行传 0，因为汉字偏旁之间也常有 1–3px 的缝。
     */
    fun splitsWord(x: Int, unit: Float, gapExemptPx: Int = 0): Boolean {
        if (counts.isEmpty() || unit <= 0f) return false
        val idx = (x - left).coerceIn(0, counts.size - 1)
        var leftEdge = idx
        var rightEdge = idx
        var gapWidth = 0
        if (counts[idx] == 0) {
            while (leftEdge > 0 && counts[leftEdge - 1] == 0) leftEdge--
            while (rightEdge + 1 < counts.size && counts[rightEdge + 1] == 0) rightEdge++
            gapWidth = rightEdge - leftEdge + 1
            if (gapExemptPx > 0 && gapWidth >= gapExemptPx) return false
        }
        var i = if (counts[idx] == 0) leftEdge - 1 else idx
        while (i >= 0 && counts[i] == 0) i--
        var j = if (counts[idx] == 0) rightEdge + 1 else idx
        while (j < counts.size && counts[j] == 0) j++
        if (i < 0 || j >= counts.size) return false
        val narrow = NARROW_RATIO * unit
        val leftWide = runWidth(i) > narrow
        val rightWide = runWidth(j) > narrow
        if (leftWide || rightWide) return false
        return gapWidth < 0.5f * unit
    }

    private fun runWidth(at: Int): Int {
        var a = at
        while (a > 0 && counts[a - 1] > 0) a--
        var b = at
        while (b + 1 < counts.size && counts[b + 1] > 0) b++
        return b - a + 1
    }

    private fun computeClusters(): List<IntRange> {
        val out = ArrayList<IntRange>(counts.size / 4 + 1)
        var start = -1
        for (i in counts.indices) {
            if (counts[i] > 0) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out.add(start until i)
                start = -1
            }
        }
        if (start >= 0) out.add(start until counts.size)
        return out
    }

    companion object {
        /** 窄于 0.62 个行高的墨簇按“拉丁字母”处理。 */
        private const val NARROW_RATIO = 0.62f

        fun of(mask: BooleanArray, width: Int, band: app.pictext.core.model.RectI): ColumnInk {
            val bw = max(0, band.width)
            val counts = IntArray(bw)
            for (y in band.top until band.bottom) {
                val base = y * width
                for (x in band.left until band.right) {
                    if (mask[base + x]) counts[x - band.left]++
                }
            }
            return ColumnInk(band.left, counts)
        }
    }
}
