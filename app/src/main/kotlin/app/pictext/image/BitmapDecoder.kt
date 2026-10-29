package app.pictext.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import app.pictext.core.model.GrayImage
import app.pictext.core.util.Grayscale
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 分析用图：灰度数据 + 源图显示尺寸（已考虑 EXIF 旋转）。 */
data class DecodedImage(
    val gray: GrayImage,
    val srcWidth: Int,
    val srcHeight: Int,
)

/**
 * 只解码分析用的降采样灰度图，禁止全尺寸解码大图。
 *
 * long side 先按 2 的幂 inSampleSize 降到 ≤ 2×1600，再精确缩到 1600。
 */
object BitmapDecoder {

    private const val ANALYSIS_MAX_SIDE = 1600

    fun decodeForAnalysis(file: File): DecodedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw DecodeException(FailureKind.UNREADABLE, "cannot read image bounds")
        }
        val mime = bounds.outMimeType.orEmpty()
        if (Build.VERSION.SDK_INT < 28 && (mime.contains("heif", true) || mime.contains("heic", true))) {
            throw DecodeException(FailureKind.UNSUPPORTED_FORMAT, "HEIF requires API 28")
        }
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        val srcW = ImageOrientation.displayWidth(rawW, rawH, orientation)
        val srcH = ImageOrientation.displayHeight(rawW, rawH, orientation)

        var sample = 1
        while (max(rawW, rawH) / sample > 2 * ANALYSIS_MAX_SIDE) sample *= 2

        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                    ?: throw DecodeException(FailureKind.DECODE_FAILED, "BitmapFactory returned null")
                try {
                    if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                        val matrix = ImageOrientation.matrix(orientation)
                        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        if (transformed !== decoded) {
                            decoded.recycle()
                            decoded = transformed
                        }
                    }
                    decoded = flattenAlpha(decoded)
                    decoded = scaleToAnalysis(decoded)
                    val gray = toGray(decoded)
                    return DecodedImage(gray, srcW, srcH)
                } finally {
                    if (!decoded.isRecycled) decoded.recycle()
                }
            } catch (e: OutOfMemoryError) {
                lastError = e
                sample *= 2
            } catch (e: DecodeException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                sample *= 2
            }
        }
        throw DecodeException(FailureKind.DECODE_FAILED, "decode failed after retries", lastError)
    }

    private fun flattenAlpha(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint())
        bitmap.recycle()
        return out
    }

    private fun scaleToAnalysis(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= ANALYSIS_MAX_SIDE) return bitmap
        val scale = ANALYSIS_MAX_SIDE.toFloat() / maxSide
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun toGray(bitmap: Bitmap): GrayImage {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val bg = borderColor(pixels, w, h)
        return Grayscale.fromArgb(w, h, pixels, bg)
    }

    /** 边界像素的中位色，作为阅读页底色。 */
    fun borderColor(pixels: IntArray, w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return Color.WHITE
        val rh = IntArray(256)
        val gh = IntArray(256)
        val bh = IntArray(256)
        var count = 0
        fun add(c: Int) {
            rh[(c ushr 16) and 0xFF]++
            gh[(c ushr 8) and 0xFF]++
            bh[c and 0xFF]++
            count++
        }
        for (x in 0 until w) {
            add(pixels[x])
            add(pixels[(h - 1) * w + x])
        }
        for (y in 0 until h) {
            add(pixels[y * w])
            add(pixels[y * w + w - 1])
        }
        if (count == 0) return Color.WHITE
        return Color.argb(255, median(rh, count), median(gh, count), median(bh, count))
    }

    private fun median(hist: IntArray, count: Int): Int {
        val target = (count + 1) / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }
}
