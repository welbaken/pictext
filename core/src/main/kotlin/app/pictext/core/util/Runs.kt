package app.pictext.core.util

/** 连续 `true` 游程工具。区间为闭区间 `first..last`。 */
object Runs {
    /** 返回所有连续 `true` 的闭区间；空数组返回空列表。 */
    fun runsOf(flags: BooleanArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in flags.indices) {
            if (flags[i]) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out.add(start..(i - 1))
                start = -1
            }
        }
        if (start >= 0) out.add(start..(flags.size - 1))
        return out
    }

    /** 合并间隔（空列数）不超过 [maxGap] 的相邻游程。 */
    fun merge(runs: List<IntRange>, maxGap: Int): List<IntRange> {
        if (runs.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>(runs.size)
        var curFirst = runs[0].first
        var curLast = runs[0].last
        for (i in 1 until runs.size) {
            val r = runs[i]
            if (r.first - curLast - 1 <= maxGap) {
                if (r.last > curLast) curLast = r.last
            } else {
                out.add(curFirst..curLast)
                curFirst = r.first
                curLast = r.last
            }
        }
        out.add(curFirst..curLast)
        return out
    }
}
