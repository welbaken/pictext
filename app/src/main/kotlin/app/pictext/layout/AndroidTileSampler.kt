package app.pictext.layout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import app.pictext.core.layout.RegionImage
import app.pictext.core.layout.RegionSampler
import app.pictext.core.layout.TileSampler
import app.pictext.core.model.GrayImage
import app.pictext.core.model.RectI
import app.pictext.core.util.Grayscale
import app.pictext.image.SourceImage
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android 侧的 tile / ROI 采样：**按需从源图解码**，不经过全页位图。
 *
 * 这是 docs/07 §2.2 的落地：v1 的 `BitmapDecoder` 按长边 ≤1600 定标，1080×20000 的长图会被压成
 * 86px 宽；v2 的每个 tile、每个 ROI 都直接从源图取，峰值内存只跟一个 tile 走，
 * 而不是全页。
 *
 * [SourceImage] 已经处理了 EXIF 方向：对外坐标是**显示坐标**，所以这里拿到的像素和
 * `PageContext.srcWidth/srcHeight`、模型框、渲染矩形是同一套坐标。
 *
 * ## 为什么还要缓存整页位图（V2-40）
 *
 * `BitmapRegionDecoder.decodeRegion` 对 JPEG 能跳读，对 **PNG 必须从头解码**：一页 24 个
 * 区域细化就是 24 次整图解码，实测 refine 段 707ms 里绝大头在这里。
 * 所以面积 ≤ [PAGE_CACHE_MAX_PIXELS] 的页面第一次采样时把整页解一次（约 4–15MB），
 * 之后的 tile/ROI 全部走 `Canvas.drawBitmap` 裁切缩放——一次解码换 30 次裁切。
 * 超长图（1080×20000）不缓存，仍然按需解码，避免 80MB 的常驻位图。
 */
class AndroidTileSampler(
    private val source: SourceImage,
) : TileSampler, RegionSampler, Closeable {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = false }
    private var page: Bitmap? = null
    private var pageSampled = 1
    private var pageTried = false

    /**
     * 整页缓存（可能为 null：超长图、解码失败）。
     *
     * `pageSampled` 是缓存位图相对源图的降采样倍数；对外坐标需要除以它。
     */
    private fun pageOrNull(): Bitmap? {
        if (pageTried) return page
        pageTried = true
        val area = source.width.toLong() * source.height
        if (area <= 0 || area > PAGE_CACHE_MAX_PIXELS) return null
        // 缓存位图宽度不超过 PAGE_CACHE_WIDTH：比它大的页面按 2 的幂降采样，
        // 保证 ROI 细化拿到的仍是“够用的分辨率”（refineMaxWidth 也是这个量级）。
        var sample = 1
        while (source.width / (sample * 2) >= PAGE_CACHE_WIDTH) sample *= 2
        val bmp = source.decodeRegion(RectI(0, 0, source.width, source.height), sample) ?: return null
        page = bmp
        pageSampled = sample
        return bmp
    }

    override fun sample(src: RectI, outW: Int, outH: Int, background: Int): IntArray {
        if (outW <= 0 || outH <= 0) return IntArray(0)
        val cached = pageOrNull()
        val pixels = IntArray(outW * outH)
        if (cached != null) {
            drawFrom(cached, src, outW, outH, pixels)
            return pixels
        }
        val bitmap = decodeScaled(src, outW, outH) ?: return IntArray(outW * outH) { background }
        return try {
            bitmap.getPixels(pixels, 0, outW, 0, 0, outW, outH)
            pixels
        } finally {
            bitmap.recycle()
        }
    }

    override fun gray(src: RectI, maxWidth: Int, maxPixels: Int): RegionImage {
        val clipped = src.clampTo(source.width, source.height)
        if (clipped.isEmpty) {
            return RegionImage(GrayImage(1, 1, ByteArray(1), 0xFFFFFFFF.toInt()), 1f)
        }
        val area = clipped.width.toLong() * clipped.height
        var targetW = clipped.width
        if (maxWidth > 0) targetW = minOf(targetW, maxWidth)
        if (maxPixels > 0 && area > maxPixels) {
            val f = kotlin.math.sqrt(maxPixels.toDouble() / area).toFloat()
            targetW = minOf(targetW, max(1, (clipped.width * f).roundToInt()))
        }
        val targetH = max(1, (clipped.height.toFloat() * targetW / clipped.width).roundToInt())

        val pixels = IntArray(targetW * targetH)
        val cached = pageOrNull()
        if (cached == null) {
            val bitmap = decodeScaled(clipped, targetW, targetH)
                ?: return RegionImage(GrayImage(1, 1, ByteArray(1), 0xFFFFFFFF.toInt()), 1f)
            try {
                bitmap.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            } finally {
                bitmap.recycle()
            }
        } else {
            drawFrom(cached, clipped, targetW, targetH, pixels)
        }
        val bg = pixels.firstOrNull() ?: 0xFFFFFFFF.toInt()
        return RegionImage(
            Grayscale.fromArgb(targetW, targetH, pixels, bg),
            targetW.toFloat() / clipped.width,
        )
    }

    /** 从缓存位图裁 [src] 并缩放到 `outW × outH`，一次 draw 完成，没有中间位图。 */
    private fun drawFrom(cached: Bitmap, src: RectI, outW: Int, outH: Int, out: IntArray) {
        val s = pageSampled
        val rect = Rect(
            (src.left / s).coerceIn(0, cached.width),
            (src.top / s).coerceIn(0, cached.height),
            ((src.right + s - 1) / s).coerceIn(0, cached.width),
            ((src.bottom + s - 1) / s).coerceIn(0, cached.height),
        )
        if (rect.isEmpty) return
        val dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            Canvas(dst).drawBitmap(cached, rect, Rect(0, 0, outW, outH), paint)
            dst.getPixels(out, 0, outW, 0, 0, outW, outH)
        } finally {
            dst.recycle()
        }
    }

    /**
     * 解码 [src] 并缩放到 `outW × outH`。
     *
     * `inSampleSize` 取 2 的幂：先按 `floor(src.width / outW)` 取整再向下取 2 的幂，
     * 保证“降采样后仍 ≥ 目标尺寸”，最后一步缩放不会放大、不会糊。
     */
    private fun decodeScaled(src: RectI, outW: Int, outH: Int): Bitmap? {
        val clipped = src.clampTo(source.width, source.height)
        if (clipped.isEmpty) return null
        var sample = 1
        val ratio = clipped.width / max(1, outW)
        while (sample * 2 <= ratio) sample *= 2
        var bmp = source.decodeRegion(clipped, sample) ?: return null
        if (bmp.width == outW && bmp.height == outH) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
        if (scaled !== bmp) bmp.recycle()
        bmp = scaled
        return bmp
    }

    override fun close() {
        page?.recycle()
        page = null
    }

    private companion object {
        /** 超过这个像素数就不缓存整页（1080×3600 ≈ 3.9MP，覆盖绝大多数截图）。 */
        const val PAGE_CACHE_MAX_PIXELS = 4_000_000L

        /** 缓存位图的宽度上限：再大的页面按 2 的幂降采样。 */
        const val PAGE_CACHE_WIDTH = 1080
    }
}
