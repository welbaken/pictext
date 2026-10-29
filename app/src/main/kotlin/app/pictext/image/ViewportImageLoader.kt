package app.pictext.image

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import app.pictext.core.model.RectI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 一次视口解码的结果：位图 + 它覆盖的源图矩形。 */
data class ViewportFrame(val bitmap: Bitmap, val src: RectI)

/**
 * 原图模式的视口解码器。
 *
 * @param offset 视口左上角在"缩放后整图"中的像素坐标
 * @param scale 缩放后整图相对源图的像素比例（`screenW/srcW * zoom`）
 */
class ViewportImageLoader(private val source: SourceImage) {

    suspend fun load(
        offset: Offset,
        scale: Float,
        viewport: IntSize,
    ): ViewportFrame? {
        if (viewport.width <= 0 || viewport.height <= 0 || scale <= 0f) return null
        val left = (offset.x / scale).toInt().coerceIn(0, max(0, source.width - 1))
        val top = (offset.y / scale).toInt().coerceIn(0, max(0, source.height - 1))
        val right = ceil((offset.x + viewport.width) / scale).toInt()
            .coerceIn(left + 1, source.width)
        val bottom = ceil((offset.y + viewport.height) / scale).toInt()
            .coerceIn(top + 1, source.height)
        val src = RectI(left, top, right, bottom)
        // 解码到至少一屏的像素密度即可；不追求超过屏幕的源像素。
        val sample = min(
            max(1, src.width / max(1, viewport.width)),
            max(1, src.height / max(1, viewport.height)),
        ).coerceIn(1, 16)
        val bitmap = withContext(Dispatchers.Default) { source.decodeRegion(src, sample) } ?: return null
        return ViewportFrame(bitmap, src)
    }
}
