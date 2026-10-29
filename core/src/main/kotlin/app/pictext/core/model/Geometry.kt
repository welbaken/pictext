package app.pictext.core.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 轴对齐整数矩形，半开区间 `[left, right) × [top, bottom)`。
 *
 * 所有版面坐标都使用这一类型；空矩形用 `EMPTY` 表示。约定 `right >= left`、
 * `bottom >= top`，构造时不做归一化，由调用方保证。
 */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** 按比例缩放，四边分别取整。 */
    fun scale(f: Float): RectI = RectI(
        (left * f).roundToInt(),
        (top * f).roundToInt(),
        (right * f).roundToInt(),
        (bottom * f).roundToInt(),
    )

    /** 交集；不相交时返回 [EMPTY]。 */
    fun intersect(o: RectI): RectI {
        val l = max(left, o.left)
        val t = max(top, o.top)
        val r = min(right, o.right)
        val b = min(bottom, o.bottom)
        return if (r <= l || b <= t) RectI.EMPTY else RectI(l, t, r, b)
    }

    /** 并集；空矩形不参与计算。 */
    fun union(o: RectI): RectI {
        if (isEmpty) return o
        if (o.isEmpty) return this
        return RectI(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    }

    fun offset(dx: Int = 0, dy: Int = 0): RectI = RectI(left + dx, top + dy, right + dx, bottom + dy)

    /** 裁剪到 `[0,w) × [0,h)` 内；完全越界时返回 [EMPTY]。 */
    fun clampTo(w: Int, h: Int): RectI = intersect(RectI(0, 0, max(0, w), max(0, h)))

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    companion object {
        val EMPTY = RectI(0, 0, 0, 0)

        fun ofSize(w: Int, h: Int): RectI = RectI(0, 0, max(0, w), max(0, h))
    }
}

/**
 * 把 A 坐标（分析图）矩形的四边向外换算到 S 坐标（源图），避免缩放后边缘变窄。
 */
internal fun RectI.toSourceOutward(scale: Float, srcWidth: Int, srcHeight: Int): RectI {
    if (scale <= 0f) return RectI.EMPTY
    val l = floor(left / scale).toInt().coerceIn(0, srcWidth)
    val t = floor(top / scale).toInt().coerceIn(0, srcHeight)
    val r = ceil(right / scale).toInt().coerceIn(0, srcWidth)
    val b = ceil(bottom / scale).toInt().coerceIn(0, srcHeight)
    return if (r <= l || b <= t) RectI.EMPTY else RectI(l, t, r, b)
}
