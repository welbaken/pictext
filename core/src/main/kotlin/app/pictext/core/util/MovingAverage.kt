package app.pictext.core.util

/** 一维移动平均。 */
object MovingAverage {
    /** `[1,1,1]/3` 平滑；边缘使用端点值填充，与原型 `smooth1d(..., 3)` 一致。 */
    fun smooth3(values: FloatArray): FloatArray {
        val n = values.size
        if (n == 0) return FloatArray(0)
        if (n < 3) return values.copyOf()
        val out = FloatArray(n)
        out[0] = (values[0] + values[0] + values[1]) / 3f
        for (i in 1 until n - 1) {
            out[i] = (values[i - 1] + values[i] + values[i + 1]) / 3f
        }
        out[n - 1] = (values[n - 2] + values[n - 1] + values[n - 1]) / 3f
        return out
    }
}
