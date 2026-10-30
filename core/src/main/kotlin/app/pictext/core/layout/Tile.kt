package app.pictext.core.layout

import app.pictext.core.model.RectI

/**
 * 一张模型输入 tile 的采样与坐标映射。
 *
 * 一个 tile 在源图里取 [src]（S 坐标），缩放成 `[innerWidth] × [innerHeight]`，
 * 再居中放进 `modelInput × modelInput` 的输入张量（[padX] / [padY] 为左侧/顶部填充）。
 * 页面本身比一个 tile 小时才会出现填充，其余情况一律“贴边 + 重叠”，不补边。
 *
 * 坐标链：
 * ```text
 * modelX = padX + (srcX - src.left) × innerWidth / src.width
 * srcX   = src.left + (modelX - padX) × src.width / innerWidth
 * ```
 */
data class Tile(
    val index: Int,
    val row: Int,
    val col: Int,
    val src: RectI,
    val innerWidth: Int,
    val innerHeight: Int,
    val padX: Int,
    val padY: Int,
    val modelInput: Int,
) {
    init {
        require(src.width > 0 && src.height > 0) { "empty tile src: $src" }
        require(innerWidth > 0 && innerHeight > 0) { "empty inner size: ${innerWidth}x$innerHeight" }
        require(padX >= 0 && padY >= 0) { "negative pad: ($padX, $padY)" }
    }

    /** 是否做过居中补边（只有页面小于一个 tile 时才为 true）。 */
    val isPadded: Boolean get() = padX > 0 || padY > 0

    /** 模型输入坐标 → S 坐标（x 方向）。 */
    fun sourceX(modelX: Float): Float =
        src.left + (modelX - padX) * src.width.toFloat() / innerWidth

    /** 模型输入坐标 → S 坐标（y 方向）。 */
    fun sourceY(modelY: Float): Float =
        src.top + (modelY - padY) * src.height.toFloat() / innerHeight

    /** S 坐标 → 模型输入坐标（x 方向），[sourceX] 的逆变换。 */
    fun modelX(srcX: Float): Float =
        padX + (srcX - src.left) * innerWidth.toFloat() / src.width

    /** S 坐标 → 模型输入坐标（y 方向），[sourceY] 的逆变换。 */
    fun modelY(srcY: Float): Float =
        padY + (srcY - src.top) * innerHeight.toFloat() / src.height

    /** S 矩形 → 模型输入矩形（不裁剪；调用方按需 clamp 到 [validModelRect]）。 */
    fun modelRect(r: RectI): RectI = RectI(
        kotlin.math.round(modelX(r.left.toFloat())).toInt(),
        kotlin.math.round(modelY(r.top.toFloat())).toInt(),
        kotlin.math.round(modelX(r.right.toFloat())).toInt(),
        kotlin.math.round(modelY(r.bottom.toFloat())).toInt(),
    )

    /** 模型输入矩形 → S 矩形（向内取整，越界裁剪到 [page] 内）。 */
    fun sourceRect(left: Float, top: Float, right: Float, bottom: Float, page: RectI): RectI {
        val l = sourceX(left)
        val t = sourceY(top)
        val r = sourceX(right)
        val b = sourceY(bottom)
        val x0 = kotlin.math.max(0, kotlin.math.round(l).toInt())
        val y0 = kotlin.math.max(0, kotlin.math.round(t).toInt())
        val x1 = kotlin.math.round(r).toInt()
        val y1 = kotlin.math.round(b).toInt()
        return RectI(x0, y0, x1, y1).clampTo(page.right, page.bottom)
    }

    /** 这个 tile 覆盖的模型空间矩形（去掉 padding 后的有效区）。 */
    fun validModelRect(): RectI = RectI(padX, padY, padX + innerWidth, padY + innerHeight)
}

/** 一页切出来的 tile 网格。 */
data class TileGrid(
    val tiles: List<Tile>,
    val scale: Float,
    val tileW: Int,
    val tileH: Int,
    val cols: Int,
    val rows: Int,
    val modelInput: Int,
) {
    val count: Int get() = tiles.size

    /** tile 覆盖是否完整（相邻 tile 有重叠，因此每一点至少被一个 tile 覆盖）。 */
    fun covers(pageW: Int, pageH: Int): Boolean {
        if (tiles.isEmpty()) return false
        val mask = BooleanArray(pageW * pageH)
        for (t in tiles) {
            for (y in t.src.top until t.src.bottom) {
                val base = y * pageW
                for (x in t.src.left until t.src.right) mask[base + x] = true
            }
        }
        return mask.all { it }
    }
}
