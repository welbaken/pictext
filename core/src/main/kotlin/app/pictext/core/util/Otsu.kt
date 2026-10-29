package app.pictext.core.util

/** Otsu 阈值。 */
object Otsu {
    /** 对 0..255 直方图求类间方差最大的阈值；空直方图返回 0。 */
    fun threshold(histogram: IntArray): Int {
        var total = 0L
        for (v in histogram) total += v
        if (total == 0L) return 0
        val sumAll = histogram.indices.sumOf { it.toLong() * histogram[it] }
        var sumB = 0L
        var weightB = 0L
        var best = 0.0
        var bestLo = 0
        var bestHi = 0
        for (t in 0..255) {
            weightB += histogram[t]
            if (weightB == 0L) continue
            val weightF = total - weightB
            if (weightF == 0L) break
            sumB += t.toLong() * histogram[t]
            val meanB = sumB.toDouble() / weightB
            val meanF = (sumAll - sumB).toDouble() / weightF
            val d = meanB - meanF
            val between = weightB.toDouble() * weightF.toDouble() * d * d
            if (between > best + 1e-9) {
                best = between
                bestLo = t
                bestHi = t
            } else if (best > 0.0 && between >= best - 1e-9) {
                // 双峰之间的平台取中点，避免阈值贴着某一类
                bestHi = t
            }
        }
        return (bestLo + bestHi) / 2
    }

    /** 对字节数组（无符号 0..255）求 Otsu 阈值。 */
    fun threshold(values: ByteArray): Int {
        val h = IntArray(256)
        for (b in values) h[b.toInt() and 0xFF]++
        return threshold(h)
    }
}
