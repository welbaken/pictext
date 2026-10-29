package app.pictext.image

import android.graphics.Matrix
import app.pictext.core.model.RectI
import kotlin.math.max
import kotlin.math.min

/**
 * EXIF 方向工具。显示坐标（S）与源文件原始坐标（raw）之间的映射都在这里，
 * 分析图和 SourceImage 必须使用同一套映射，否则渲染会错位。
 */
object ImageOrientation {

    fun swapsAxes(orientation: Int): Boolean = orientation in 5..8

    fun displayWidth(rawW: Int, rawH: Int, orientation: Int): Int =
        if (swapsAxes(orientation)) rawH else rawW

    fun displayHeight(rawW: Int, rawH: Int, orientation: Int): Int =
        if (swapsAxes(orientation)) rawW else rawH

    /** 分析图解码后的整体方向矩阵。 */
    fun matrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            6 -> setRotate(90f)
            7 -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            8 -> setRotate(-90f)
        }
    }

    /** 显示坐标中的一个像素映射到原始文件坐标。 */
    fun mapToRaw(x: Int, y: Int, orientation: Int, rawW: Int, rawH: Int): Pair<Int, Int> =
        when (orientation) {
            2 -> (rawW - 1 - x) to y
            3 -> (rawW - 1 - x) to (rawH - 1 - y)
            4 -> x to (rawH - 1 - y)
            5 -> y to x
            6 -> y to (rawH - 1 - x)
            7 -> (rawH - 1 - y) to (rawW - 1 - x)
            8 -> (rawW - 1 - y) to x
            else -> x to y
        }

    /** 显示坐标矩形映射到原始文件坐标矩形（半开区间）。 */
    fun toRawRect(rect: RectI, orientation: Int, rawW: Int, rawH: Int): RectI {
        if (orientation == 1) return rect
        val a = mapToRaw(rect.left, rect.top, orientation, rawW, rawH)
        val b = mapToRaw(max(rect.left, rect.right - 1), max(rect.top, rect.bottom - 1), orientation, rawW, rawH)
        val c = mapToRaw(rect.left, max(rect.top, rect.bottom - 1), orientation, rawW, rawH)
        val d = mapToRaw(max(rect.left, rect.right - 1), rect.top, orientation, rawW, rawH)
        val x0 = min(min(a.first, b.first), min(c.first, d.first)).coerceIn(0, rawW)
        val x1 = (max(max(a.first, b.first), max(c.first, d.first)) + 1).coerceIn(0, rawW)
        val y0 = min(min(a.second, b.second), min(c.second, d.second)).coerceIn(0, rawH)
        val y1 = (max(max(a.second, b.second), max(c.second, d.second)) + 1).coerceIn(0, rawH)
        return RectI(x0, y0, x1, y1)
    }
}
