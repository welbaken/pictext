package app.pictext.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import app.pictext.core.model.PageLayout
import app.pictext.core.model.RectI
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.image.SourceImage
import app.pictext.image.SourceStripCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 按 1024px 高的瓦片渲染计划。任何一张瓦片都只解码自己需要的源图条带，
 * 峰值内存与文档总高度无关。
 *
 * 渲染失败（条带解码 nil / 越界 / OOM）**不会抛出**，只会让相应区域留底色——
 * 这是刻意的（失败不能带走进程），但不能是静默的：每条失败都会带 src/dest 进 logcat，
 * 否则用户看到的"一块空白"将无从定位。
 */
class TileRenderer(
    private val layout: PageLayout,
    val plan: ReflowPlan,
    private val source: SourceImage,
    private val strips: SourceStripCache,
) {
    private val mutex = Mutex()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    suspend fun render(tileIndex: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.Default) {
            val top = plan.tileTop(tileIndex)
            val h = plan.tileHeight(tileIndex).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(plan.outputWidth, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(plan.backgroundColor)
            val tileRect = RectI(0, top, plan.outputWidth, top + h)
            val visible = plan.itemsIntersecting(tileRect)
            var failed = 0
            for (item in visible) {
                runCatching { drawItem(canvas, item, top) }.onFailure { e ->
                    failed++
                    Log.w(TAG, "tile $tileIndex: draw failed src=${item.src} dest=${item.dest}", e)
                }
            }
            if (failed > 0) {
                Log.w(
                    TAG,
                    "tile $tileIndex: $failed/${visible.size} 个渲染项失败，对应区域是底色（内容缺失）",
                )
            }
            bitmap
        }
    }

    private fun drawItem(canvas: Canvas, item: RenderItem, tileTop: Int) {
        if (item.src.isEmpty || item.dest.isEmpty) return
        val destTop = item.dest.top - tileTop
        val destBottom = item.dest.bottom - tileTop
        if (destBottom <= 0 || destTop >= plan.tileHeightPx) return

        var srcY = item.src.top
        while (srcY < item.src.bottom) {
            val strip = strips.stripFor(srcY)
            if (strip == null) {
                Log.w(TAG, "strip decode failed at srcY=$srcY（item src=${item.src}），该段留底色")
                break
            }
            val stripTop = strip.srcRect.top
            val stripBottom = strip.srcRect.bottom
            val subTop = max(item.src.top, stripTop)
            val subBottom = min(item.src.bottom, stripBottom)
            if (subBottom <= subTop) {
                // 防止条带边界死循环
                val next = stripBottom
                if (next <= srcY) break
                srcY = next
                continue
            }
            val subSrc = RectI(item.src.left, subTop, item.src.right, subBottom)
            val srcRect = strip.toBitmapRect(subSrc)

            val fracTop = (subTop - item.src.top).toFloat() / max(1, item.src.height)
            val fracBottom = (subBottom - item.src.top).toFloat() / max(1, item.src.height)
            val dTop = item.dest.top + (fracTop * item.dest.height).roundToInt()
            val dBottom = item.dest.top + (fracBottom * item.dest.height).roundToInt()
            val dest = Rect(
                item.dest.left,
                dTop - tileTop,
                item.dest.right,
                max(dTop + 1, dBottom) - tileTop,
            )
            if (dest.bottom > 0 && dest.top < plan.tileHeightPx) {
                canvas.drawBitmap(strip.bitmap, srcRect, dest, paint)
            }
            srcY = stripBottom
        }
    }

    private companion object {
        const val TAG = "PicTextRender"
    }
}
