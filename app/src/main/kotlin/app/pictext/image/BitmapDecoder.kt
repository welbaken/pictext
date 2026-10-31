package app.pictext.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.AnalysisScale
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
 * 定标规则（P1）是**宽度优先**，与 CLI/测试共用 [AnalysisScale]：长图不再因为“长边 1600”
 * 被压成一条。像素超预算（[WHOLE_DECODE_MAX_PIXELS]）时改走条带解码（P2）：
 * 用 `BitmapRegionDecoder` 一行带一行带地解码，宽度不变，峰值只有一条带的 ARGB。
 *
 * 灰度化按 [GRAY_BAND_ROWS] 行分块写进目标数组，避免 `getPixels` 给整张图开 4 字节/像素的
 * 临时数组（690×10305 就是 28MB）。
 */
object BitmapDecoder {

    /** 整图 ARGB 解码的像素预算；超过就走条带（1080×20000 的整图 ARGB 是 86MB）。 */
    private const val WHOLE_DECODE_MAX_PIXELS = 8_000_000L

    /** 单条带解码的像素预算，超了就抬 inSampleSize。 */
    private const val STRIP_DECODE_MAX_PIXELS = 8_000_000L

    /** 灰度化时一次 `getPixels` 的行数。 */
    private const val GRAY_BAND_ROWS = 256

    fun decodeForAnalysis(
        file: File,
        options: AnalysisOptions = AnalysisOptions(),
    ): DecodedImage {
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

        // P1：宽度优先，长图不再按长边降采样。
        val size = AnalysisScale.size(srcW, srcH, options)

        // P2：整图解码超预算就切条带；旋转过的图退回整图路径（长图截图极少带旋转，
        // 真带旋转时由整图路径的重试逻辑降级，不会崩）。
        val gray = if (srcW.toLong() * srcH <= WHOLE_DECODE_MAX_PIXELS ||
            orientation != ExifInterface.ORIENTATION_NORMAL
        ) {
            decodeWhole(file, size, orientation, rawW, rawH)
        } else {
            decodeInStrips(file, size, rawW, rawH, options)
        }
        return DecodedImage(gray, srcW, srcH)
    }

    private fun decodeWhole(
        file: File,
        size: app.pictext.core.analyze.AnalysisSize,
        orientation: Int,
        rawW: Int,
        rawH: Int,
    ): GrayImage {
        // 解到至少 2× 分析尺寸再缩，避免缩小时锯齿；同时保证不超过整图预算。
        var sample = 1
        while (rawW / sample > 2 * size.width || rawH / sample > 2 * size.height) sample *= 2

        var lastError: Throwable? = null
        repeat(4) {
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
                        val transformed =
                            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        if (transformed !== decoded) {
                            decoded.recycle()
                            decoded = transformed
                        }
                    }
                    decoded = flattenAlpha(decoded)
                    decoded = scaleTo(decoded, size.width, size.height)
                    return toGray(decoded)
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

    /**
     * 条带解码（P2）：`BitmapRegionDecoder` 按源图行带解码，缩放后灰度化写进整图。
     *
     * 宽度全程等于分析宽（不压宽度），峰值 = 一条带的 ARGB + 整张灰度图。
     */
    private fun decodeInStrips(
        file: File,
        size: app.pictext.core.analyze.AnalysisSize,
        rawW: Int,
        rawH: Int,
        options: AnalysisOptions,
    ): GrayImage {
        val decoder = try {
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
                ?: throw DecodeException(FailureKind.DECODE_FAILED, "cannot open region decoder")
        } catch (e: DecodeException) {
            throw e
        } catch (t: Throwable) {
            throw DecodeException(FailureKind.DECODE_FAILED, "cannot open region decoder", t)
        }
        try {
            val data = ByteArray(size.width * size.height)
            val scale = size.factor
            val stripAnalysisH = max(1, options.stripHeight)
            val stripRawH = max(1, (stripAnalysisH / scale).roundToInt())
            var backgroundColor = Color.WHITE
            var first = true
            var srcTop = 0
            while (srcTop < rawH) {
                val rows = min(stripRawH, rawH - srcTop)
                val destTop = (srcTop * scale).roundToInt().coerceIn(0, size.height - 1)
                val destBottom = if (srcTop + rows >= rawH) {
                    size.height
                } else {
                    ((srcTop + rows) * scale).roundToInt().coerceIn(destTop + 1, size.height)
                }
                var bitmap = decodeStrip(decoder, srcTop, rows, rawW, size.width)
                try {
                    bitmap = flattenAlpha(bitmap)
                    bitmap = scaleTo(bitmap, size.width, max(1, destBottom - destTop))
                    if (first) {
                        backgroundColor = borderColor(bitmap)
                        first = false
                    }
                    writeGray(bitmap, data, size.width, destTop)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                srcTop += rows
            }
            return GrayImage(size.width, size.height, data, backgroundColor)
        } finally {
            runCatching { decoder.recycle() }
        }
    }

    private fun decodeStrip(
        decoder: BitmapRegionDecoder,
        top: Int,
        rows: Int,
        rawW: Int,
        targetWidth: Int,
    ): Bitmap {
        var sample = 1
        while (rawW.toLong() * (rows / sample) > STRIP_DECODE_MAX_PIXELS) sample *= 2
        // 别把宽度采到目标宽以下：宁可多占一点内存，也不要把字缝采没了。
        while (sample > 1 && rawW / sample < targetWidth) sample /= 2
        repeat(3) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decoder.decodeRegion(Rect(0, top, rawW, top + rows), opts)?.let { return it }
            } catch (e: OutOfMemoryError) {
                // 继续放大采样
            } catch (t: Throwable) {
                throw DecodeException(FailureKind.DECODE_FAILED, "region decode failed at $top", t)
            }
            sample *= 2
        }
        throw DecodeException(FailureKind.DECODE_FAILED, "region decode failed at $top")
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

    private fun scaleTo(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        if (bitmap.width == w && bitmap.height == h) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun toGray(bitmap: Bitmap): GrayImage {
        val w = bitmap.width
        val h = bitmap.height
        val data = ByteArray(w * h)
        writeGray(bitmap, data, w, 0)
        return GrayImage(w, h, data, borderColor(bitmap))
    }

    /** 分块 `getPixels` → 灰度，写进 [dest] 的第 [destTop] 行起。 */
    private fun writeGray(bitmap: Bitmap, dest: ByteArray, destWidth: Int, destTop: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val rows = min(h, GRAY_BAND_ROWS)
        val band = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
            bitmap.getPixels(band, 0, w, 0, y, w, n)
            Grayscale.writeBand(dest, destWidth, destTop + y, w, n, band)
            y += n
        }
    }

    /** 边界像素的中位色，作为阅读页底色。 */
    private fun borderColor(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
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
            add(bitmap.getPixel(x, 0))
            add(bitmap.getPixel(x, h - 1))
        }
        for (y in 0 until h) {
            add(bitmap.getPixel(0, y))
            add(bitmap.getPixel(w - 1, y))
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
