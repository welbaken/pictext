package app.pictext.image

import android.graphics.Bitmap
import android.graphics.Rect
import app.pictext.core.model.RectI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一条已解码的源图条带（显示坐标，全宽）。 */
class Strip(
    val srcRect: RectI,
    val bitmap: Bitmap,
) {
    /** 显示坐标 [src] 映射到条带位图上的矩形。 */
    fun toBitmapRect(src: RectI): Rect {
        val w = max(1, srcRect.width)
        val h = max(1, srcRect.height)
        val l = ((src.left - srcRect.left).toFloat() * bitmap.width / w).roundToInt().coerceIn(0, bitmap.width)
        val t = ((src.top - srcRect.top).toFloat() * bitmap.height / h).roundToInt().coerceIn(0, bitmap.height)
        val r = ((src.right - srcRect.left).toFloat() * bitmap.width / w).roundToInt().coerceIn(0, bitmap.width)
        val b = ((src.bottom - srcRect.top).toFloat() * bitmap.height / h).roundToInt().coerceIn(0, bitmap.height)
        return Rect(l, t, max(l + 1, r), max(t + 1, b))
    }
}

/**
 * 按 512 源行切条解码，LRU 上限默认 32MB。
 *
 * `sample` 选择保证条带宽度至少约 1.5 倍输出宽度（最高 2400px），
 * 既不会为 8000px 扫描件解出巨大条带，也不会让 1080 输出显得糊。
 */
class SourceStripCache(
    private val source: SourceImage,
    val outputWidth: Int,
    private val stripSrcHeight: Int = 512,
    private val maxBytes: Long = 32L * 1024 * 1024,
) {
    private val lock = Any()
    private val cache = object : LinkedHashMap<Int, Strip>(8, 0.75f, true) {}
    private var bytes = 0L

    private val targetWidth: Int = run {
        val want = (1.5f * outputWidth).roundToInt().coerceAtMost(2400)
        source.width.coerceIn(1, max(1, want))
    }

    private val sample: Int = run {
        var s = 1
        while (s * 2 <= 16 && source.width / (s * 2) >= targetWidth) s *= 2
        s
    }

    /** 取得包含显示坐标 y 的条带；失败返回 null（调用方用底色填充）。 */
    fun stripFor(srcY: Int): Strip? {
        val index = (srcY.coerceIn(0, max(0, source.height - 1))) / stripSrcHeight
        synchronized(lock) {
            cache[index]?.let { return it }
        }
        val top = index * stripSrcHeight
        val rect = RectI(0, top, source.width, min(source.height, top + stripSrcHeight))
        val bitmap = source.decodeRegion(rect, sample) ?: return null
        val strip = Strip(rect, bitmap)
        synchronized(lock) {
            cache[index] = strip
            bytes += bitmap.byteCount
            trimLocked()
        }
        return strip
    }

    private fun trimLocked() {
        val iterator = cache.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= entry.value.bitmap.byteCount
            entry.value.bitmap.recycle()
            iterator.remove()
        }
    }

    fun clear() {
        synchronized(lock) {
            for (strip in cache.values) strip.bitmap.recycle()
            cache.clear()
            bytes = 0
        }
    }
}
