package app.pictext.core.util

/** 小规模统计工具，全部基于整数直方图，避免像素循环里的对象分配。 */
object Stats {
    /** 中位数；空数组返回 [fallback]。 */
    fun median(values: IntArray, fallback: Int = 0): Float {
        if (values.isEmpty()) return fallback.toFloat()
        val copy = values.copyOf()
        copy.sort()
        val n = copy.size
        return if (n % 2 == 1) copy[n / 2].toFloat()
        else (copy[n / 2 - 1].toFloat() + copy[n / 2].toFloat()) / 2f
    }

    fun median(values: List<Int>, fallback: Int = 0): Float {
        if (values.isEmpty()) return fallback.toFloat()
        return median(values.toIntArray(), fallback)
    }

    /**
     * 稳健中位数：排序后去掉最高/最低各 [trimRatio] 再取中位数。
     *
     * 头像、大标题、装饰线会造出极少数超高行带，直接取中位数会被它们拉偏；
     * 去掉两端 10% 后得到的“典型行高”更接近正文。
     */
    fun robustMedian(values: IntArray, fallback: Int = 0, trimRatio: Float = 0.10f): Float {
        if (values.isEmpty()) return fallback.toFloat()
        if (values.size < 5) return median(values, fallback)
        val copy = values.copyOf()
        copy.sort()
        val trim = (copy.size * trimRatio).toInt().coerceIn(0, (copy.size - 1) / 2)
        val lo = trim
        val hiExclusive = copy.size - trim
        val n = hiExclusive - lo
        if (n <= 0) return median(values, fallback)
        return if (n % 2 == 1) copy[lo + n / 2].toFloat()
        else (copy[lo + n / 2 - 1].toFloat() + copy[lo + n / 2].toFloat()) / 2f
    }

    /** 变异系数 `σ/μ`；样本为空或均值为 0 时返回 [Float.MAX_VALUE]。 */
    fun coefficientOfVariation(values: IntArray): Float {
        if (values.size < 2) return Float.MAX_VALUE
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        if (mean <= 0.0) return Float.MAX_VALUE
        var acc = 0.0
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        val sd = kotlin.math.sqrt(acc / (values.size - 1))
        return (sd / mean).toFloat()
    }

    /** 按整数值的 0..255 直方图取分位数（最近秩）。 */
    fun percentile(histogram: IntArray, percentile: Int): Int {
        var total = 0L
        for (v in histogram) total += v
        if (total == 0L) return 0
        val target = (total * percentile.coerceIn(0, 100) + 99) / 100
        var acc = 0L
        for (v in histogram.indices) {
            acc += histogram[v]
            if (acc >= target) return v
        }
        return 255
    }

    fun histogram(values: ByteArray, out: IntArray = IntArray(256)): IntArray {
        java.util.Arrays.fill(out, 0)
        for (b in values) out[b.toInt() and 0xFF]++
        return out
    }
}
