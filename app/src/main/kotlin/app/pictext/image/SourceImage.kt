package app.pictext.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.media.ExifInterface
import app.pictext.core.model.RectI
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 源图区域解码器。内部持有 `BitmapRegionDecoder`（非线程安全，所有访问已加锁）。
 *
 * 对外坐标是**显示坐标**（已应用 EXIF 方向）；`decodeRegion` 负责映射到文件原始
 * 坐标并在需要时旋转/镜像结果。
 */
class SourceImage(file: File) : Closeable {

    private val decoder: BitmapRegionDecoder = try {
        BitmapRegionDecoder.newInstance(file.absolutePath, false)
            ?: throw DecodeException(FailureKind.DECODE_FAILED, "cannot open region decoder")
    } catch (e: DecodeException) {
        throw e
    } catch (t: Throwable) {
        throw DecodeException(FailureKind.DECODE_FAILED, "cannot open region decoder", t)
    }

    private val rawWidth = decoder.width
    private val rawHeight = decoder.height
    private val orientation: Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val width: Int = ImageOrientation.displayWidth(rawWidth, rawHeight, orientation)
    val height: Int = ImageOrientation.displayHeight(rawWidth, rawHeight, orientation)

    /** 解码显示坐标中的 [src]；返回的 Bitmap 尺寸约为 `src.width/sample × src.height/sample`。 */
    @Synchronized
    fun decodeRegion(src: RectI, sample: Int = 1): Bitmap? {
        val clipped = src.clampTo(width, height)
        if (clipped.isEmpty) return null
        return decodeClipped(clipped, sample.coerceAtLeast(1), allowRetry = true)
    }

    private fun decodeClipped(src: RectI, sample: Int, allowRetry: Boolean): Bitmap? {
        val rawRect = ImageOrientation.toRawRect(src, orientation, rawWidth, rawHeight)
        if (rawRect.isEmpty) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = try {
            decoder.decodeRegion(
                android.graphics.Rect(rawRect.left, rawRect.top, rawRect.right, rawRect.bottom),
                opts,
            )
        } catch (e: OutOfMemoryError) {
            if (allowRetry) return decodeClipped(src, sample * 2, false) else return null
        } catch (t: Throwable) {
            return null
        }
        if (raw == null) {
            return if (allowRetry) decodeClipped(src, sample * 2, false) else null
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return raw
        return try {
            rotateToDisplay(raw, rawRect, src, sample)
        } catch (t: Throwable) {
            raw.recycle()
            null
        }
    }

    /**
     * 把原始坐标下的一块区域重排成显示坐标下的条带。逐像素映射比直接给
     * `BitmapRegionDecoder` 加 Matrix 更可控，也能处理 2/3/4/5/6/7/8 全部方向。
     */
    private fun rotateToDisplay(raw: Bitmap, rawRect: RectI, src: RectI, sample: Int): Bitmap {
        val outW = max(1, (src.width.toFloat() / sample).roundToInt())
        val outH = max(1, (src.height.toFloat() / sample).roundToInt())
        val rawPixels = IntArray(raw.width * raw.height)
        raw.getPixels(rawPixels, 0, raw.width, 0, 0, raw.width, raw.height)
        val out = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val gy = (src.top + oy * sample).coerceIn(src.top, src.bottom - 1)
            for (ox in 0 until outW) {
                val gx = (src.left + ox * sample).coerceIn(src.left, src.right - 1)
                val (rx, ry) = ImageOrientation.mapToRaw(gx, gy, orientation, rawWidth, rawHeight)
                val lx = (rx - rawRect.left).toFloat()
                val ly = (ry - rawRect.top).toFloat()
                val bx = (lx * raw.width / max(1, rawRect.width)).toInt().coerceIn(0, raw.width - 1)
                val by = (ly * raw.height / max(1, rawRect.height)).toInt().coerceIn(0, raw.height - 1)
                out[oy * outW + ox] = rawPixels[by * raw.width + bx]
            }
        }
        raw.recycle()
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, outW, 0, 0, outW, outH)
        return bitmap
    }

    override fun close() {
        runCatching { decoder.recycle() }
    }
}
