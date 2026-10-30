package app.pictext.layout

import android.graphics.Bitmap
import app.pictext.core.layout.RegionImage
import app.pictext.core.layout.RegionSampler
import app.pictext.core.layout.TileSampler
import app.pictext.core.model.GrayImage
import app.pictext.core.model.RectI
import app.pictext.core.util.Grayscale
import app.pictext.image.SourceImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android 侧的 tile / ROI 采样：**按需从源图解码**，不经过全页位图。
 *
 * 这是 docs/07 §2.2 的落地：v1 的 `BitmapDecoder` 按长边 ≤1600 定标，1080×20000 的长图会被压成
 * 86px 宽；v2 的每个 tile、每个 ROI 都直接从源图 `BitmapRegionDecoder` 取，峰值内存只跟一个
 * tile（1080×1080 ARGB ≈ 4.6MB）走，而不是 86MB 的全页。
 *
 * [SourceImage] 已经处理了 EXIF 方向：对外坐标是**显示坐标**，所以这里拿到的像素和
 * `PageContext.srcWidth/srcHeight`、模型框、渲染矩形是同一套坐标。
 */
class AndroidTileSampler(
    private val source: SourceImage,
) : TileSampler, RegionSampler {

    override fun sample(src: RectI, outW: Int, outH: Int, background: Int): IntArray {
        if (outW <= 0 || outH <= 0) return IntArray(0)
        val bitmap = decodeScaled(src, outW, outH) ?: return IntArray(outW * outH) { background }
        return try {
            val pixels = IntArray(outW * outH)
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
        val bitmap = decodeScaled(clipped, targetW, targetH)
            ?: return RegionImage(GrayImage(1, 1, ByteArray(1), 0xFFFFFFFF.toInt()), 1f)
        return try {
            val pixels = IntArray(targetW * targetH)
            bitmap.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            val bg = pixels.firstOrNull() ?: 0xFFFFFFFF.toInt()
            RegionImage(
                Grayscale.fromArgb(targetW, targetH, pixels, bg),
                targetW.toFloat() / clipped.width,
            )
        } finally {
            bitmap.recycle()
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
}
