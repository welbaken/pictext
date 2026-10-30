package app.pictext.core.layout

import app.pictext.core.model.RectI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tile 网格（docs/07 §4.1）。
 *
 * ```text
 * scale   = clamp(480 / pageW, minScale, maxScale)
 * tileW   = min(pageW, round(480 / scale))
 * stride  = max(1, tileW - overlapPx)
 * cols    = max(1, ceil((pageW - tileW) / stride) + 1)
 * rows    = max(1, ceil((pageH - tileH) / stride) + 1)
 * x(i)    = min(i * stride, max(0, pageW - tileW))   // 最后一列贴边
 * y(j)    = min(j * stride, max(0, pageH - tileH))   // 最后一行贴边
 * ```
 *
 * 边缘 tile **不补边**，而是往页内平移，靠 tile 重叠 + 全局 NMS 去重；
 * 只有页面本身小于一个 tile 时才居中填充（`Tile.padX/padY > 0`）。
 *
 * 为什么按宽度而不是按长边定标：v1 用 `max(w,h) ≤ 1600`，1080×20000 的长图会被压成
 * 86×1600，模型和字缝都会失效（docs/07 §2.2）。
 */
class LayoutTiler(private val options: LayoutOptions = LayoutOptions()) {

    /** 一次性算好整页网格；调用方自行缓存，不要每个 tile 重算。 */
    fun grid(pageW: Int, pageH: Int): TileGrid {
        val w = max(1, pageW)
        val h = max(1, pageH)
        val model = options.modelInput
        val scale = (model.toFloat() / w).coerceIn(options.minScale, options.maxScale)
        val tileW = min(w, max(1, (model / scale).roundToInt()))
        val tileH = tileW
        val stride = max(1, tileW - options.overlapPx)
        val cols = max(1, ceil((w - tileW).toFloat() / stride).toInt() + 1)
        val rows = max(1, ceil((h - tileH).toFloat() / stride).toInt() + 1)

        val tiles = ArrayList<Tile>(cols * rows)
        var index = 0
        for (j in 0 until rows) {
            val y = min(j * stride, max(0, h - tileH))
            for (i in 0 until cols) {
                val x = min(i * stride, max(0, w - tileW))
                val src = RectI(x, y, x + tileW, y + tileH).clampTo(w, h)
                if (src.isEmpty) continue
                val innerW = max(1, (src.width * scale).roundToInt()).coerceAtMost(model)
                val innerH = max(1, (src.height * scale).roundToInt()).coerceAtMost(model)
                tiles.add(
                    Tile(
                        index = index,
                        row = j,
                        col = i,
                        src = src,
                        innerWidth = innerW,
                        innerHeight = innerH,
                        padX = (model - innerW) / 2,
                        padY = (model - innerH) / 2,
                        modelInput = model,
                    ),
                )
                index++
            }
        }
        return TileGrid(
            tiles = tiles,
            scale = scale,
            tileW = tileW,
            tileH = tileH,
            cols = cols,
            rows = rows,
            modelInput = model,
        )
    }

    /** tile 数是否超过保护上限（超过就回退 v1，记 `layout_too_many_tiles`）。 */
    fun exceedsLimit(grid: TileGrid): Boolean = grid.count > options.maxTiles
}
