package app.pictext.render

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 延迟一帧再回收位图。
 *
 * Compose 的绘制命令（display list）可能仍持有上一帧的 [Bitmap]：在 `onDispose` 里立刻
 * `recycle()`，极端时序下会抛 `Canvas: trying to use a recycled bitmap`（进程级崩溃）。
 * 这里把回收排到当前帧消息之后（`Handler.post`），此时新的 display list 已经不含这张位图，
 * 而内存回收只晚一帧——对"快速滚动时把几十张瓦片丢给 finalizer"的原始问题没有影响。
 *
 * 只用于**显示中的**瓦片/视口帧；`TileRenderer` 内部画进软件 Bitmap 的条带是同步拷贝，
 * 仍由 `SourceStripCache` 自己回收即可。
 */
object BitmapRecycler {

    private val main = Handler(Looper.getMainLooper())

    fun recycle(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        main.post {
            if (!bitmap.isRecycled) {
                runCatching { bitmap.recycle() }
                    .onFailure { Log.w("PicText", "bitmap recycle failed", it) }
            }
        }
    }
}
