package app.pictext.core.analyze

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * P1-1：拉丁 / URL 行的安全切分。
 *
 * “字缝”模型对拉丁字母是危险的：字母之间也有低墨列，`inkAtCut ≤ 1.5` 可能在单词内部通过。
 * 这里改用更硬的条件——**整行高度上的零墨列**（不是“墨少”，是“无墨”），并要求空白宽度
 * ≥ `max(2, 0.12 × 行高)`、且不小于该行空白宽度中位数的一半，切点取空白中心。
 *
 * 这条规则同时天然排除了 URL 与代码：`https://xerj.org/get` 内部没有足够宽的空白，
 * 找不到缝就返回 `null`，该行不切，整页在安全门处回退。
 */
object WordGapFinder {

    fun findCut(p: ColumnInk, center: Int, win: Int, minGapPx: Float): Int? {
        if (p.width <= 0) return null
        // 拉丁行里“典型空白”就是字母缝；真正的词间空格明显更宽。
        // 只要求 ≥ 中位数是不够的（字母缝本身就成了中位数），必须乘一个系数。
        val required = max(
            max(2, minGapPx.toInt()),
            (1.5f * p.blankRunMedian).toInt(),
        )

        val lo = max(p.left, center - win)
        val hi = min(p.left + p.width, center + win)
        if (hi <= lo) return null

        var best = -1
        var bestDist = Int.MAX_VALUE
        var i = lo - p.left
        val end = hi - p.left
        while (i < end) {
            if (p.counts[i] != 0) {
                i++
                continue
            }
            val start = i
            while (i < end && p.counts[i] == 0) i++
            if (i - start < required) continue
            val mid = p.left + (start + i - 1) / 2
            val d = abs(mid - center)
            if (d < bestDist) {
                bestDist = d
                best = mid
            }
        }
        return if (best >= 0) best else null
    }
}
