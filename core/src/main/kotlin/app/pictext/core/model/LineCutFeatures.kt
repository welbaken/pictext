package app.pictext.core.model

/**
 * 一条文字行的**列墨量剖面**（绝对 S 列坐标）。
 *
 * 规划期要按需要的段数在任意位置挑刀，而分析只做一次、旋转/分屏后只重规划，
 * 所以剖面必须跟着 [TextLine] 存进 `PageLayout`，不能只活在分析期。
 *
 * 墨量用 `Byte` 存（一行内单列墨点数不会超过行高 255），比 `IntArray` 省 4 倍内存；
 * 判据只需要和 0 / 1.5 / 阈值比较，1 的量化足够。
 */
class InkProfile(val left: Int, val counts: ByteArray) {

    val width: Int get() = counts.size

    val right: Int get() = left + counts.size

    /** 绝对列坐标 [x] 处的墨点数；越界返回 0。 */
    fun inkAt(x: Int): Int {
        val i = x - left
        if (i < 0 || i >= counts.size) return 0
        return counts[i].toInt() and 0xFF
    }

    /** 区间 `[x0, x1)` 的总墨量。 */
    fun inkIn(x0: Int, x1: Int): Int {
        var sum = 0
        val a = maxOf(x0, left)
        val b = minOf(x1, right)
        for (x in a until b) sum += counts[x - left].toInt() and 0xFF
        return sum
    }

    /** 整行墨量。 */
    val totalInk: Int by lazy { counts.sumOf { it.toInt() and 0xFF } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InkProfile) return false
        return left == other.left && counts.contentEquals(other.counts)
    }

    override fun hashCode(): Int = 31 * left + counts.contentHashCode()

    override fun toString(): String = "InkProfile(left=$left, width=$width, ink=$totalInk)"

    companion object {
        fun of(left: Int, ink: IntArray): InkProfile =
            InkProfile(left, ByteArray(ink.size) { ink[it].coerceIn(0, 255).toByte() })
    }
}

/**
 * 一条文字行的切点特征。区域细化期一次算完，规划期只读。
 *
 * 三级切点（docs/07 §4.8.2）：**硬缝 → 软缝 → 强制**。
 *
 * @param hardGaps 零墨列组（绝对 S 列区间，闭区间），宽度 ≥ `max(2, 0.10×行高)`；
 * @param softGaps 低墨列组（1–2px 内墨量 ≤1.5 的局部最小），宽度 ≥1；
 * @param minInkColumn 全行墨量最小的内部列；`-1` 表示没有可用列。
 * @param cjkDominant 宽墨簇（≥0.6×行高）占比 ≥60%，即“汉字/假名正文”的样子；
 * @param latinLike 拉丁/等宽行：墨簇窄且密（沿用 v1 P1-1 的两个判据）；
 * @param hasUrlOrCode 行内含 URL 特征（`://`、`www.`、`@`、路径分隔）或代码特征；
 * @param digitHeavy 数字占比高（价格、电话、长数字）；
 * @param sensitive 敏感行：`latinLike || hasUrlOrCode || digitHeavy`，**禁止强制切**；
 * @param density 行内墨密度 `ink / (行高 × 行宽)`；
 * @param maxRun 行内最长竖直墨迹（相对行高的倍数要在 `maxRunRatio` 里看）。
 */
data class LineCutFeatures(
    val hardGaps: List<IntRange> = emptyList(),
    val softGaps: List<IntRange> = emptyList(),
    val minInkColumn: Int = -1,
    val cjkDominant: Boolean = false,
    val latinLike: Boolean = false,
    val hasUrlOrCode: Boolean = false,
    val digitHeavy: Boolean = false,
    val sensitive: Boolean = false,
    val density: Float = 0f,
    val maxRun: Int = 0,
    val profile: InkProfile? = null,
) {
    /** 这一行有没有任何可用刀口（硬缝或软缝）。 */
    val hasSafeCut: Boolean get() = hardGaps.isNotEmpty() || softGaps.isNotEmpty()

    companion object {
        val NONE = LineCutFeatures()
    }
}
