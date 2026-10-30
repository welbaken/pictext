package app.pictext.core.layout

import app.pictext.core.model.GrayImage
import app.pictext.core.model.RectI
import app.pictext.core.util.Grayscale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 从源图按 S 矩形采样 ARGB（**不经过全页分析图**）。
 *
 * 这是 docs/07 §2.2 的落地：v1 的分析图按长边 ≤1600 定标，1080×20000 的长图只剩 86px 宽，
 * 模型和字缝都会失效。v2 的每个 tile / 每个 ROI 都直接从源图取样，内存峰值只跟一个 tile 走。
 *
 * 实现方（`:app` / `:cli`）负责解码：
 * - App：`BitmapRegionDecoder.decodeRegion` + `inSampleSize`；
 * - CLI/测试：`BufferedImage.getSubimage` 或 [GrayPageTileSampler]。
 */
interface TileSampler {
    /** 采样 [src]（S 坐标）到 `outW × outH` 的 ARGB；越界部分用 [background] 填充。 */
    fun sample(src: RectI, outW: Int, outH: Int, background: Int): IntArray
}

/** 区域细化用的灰度采样结果。[scale] = `gray.width / src.width`。 */
data class RegionImage(val gray: GrayImage, val scale: Float)

/**
 * 从源图采一块灰度的 ROI。
 *
 * 与 [TileSampler] 分开是因为细化不需要颜色，也不想为了一个 ROI 先解一张 ARGB。
 * 实现方允许降采样（[maxWidth] / [maxPixels] 是上限，不是精确尺寸），
 * 但必须把实际倍率放进 `RegionImage.scale`，否则行带坐标换算会系统性偏移。
 */
interface RegionSampler {
    fun gray(src: RectI, maxWidth: Int, maxPixels: Int): RegionImage
}

/**
 * 全页灰度图上的 [TileSampler] 实现：桌面 CLI 与单测用。
 *
 * Android 侧不应使用它——那里必须用 `BitmapRegionDecoder` 逐 tile 解码，
 * 否则长图会先被解成一张 1080×20000 的位图。
 */
class GrayPageTileSampler(private val page: GrayImage) : TileSampler {

    override fun sample(src: RectI, outW: Int, outH: Int, background: Int): IntArray {
        if (outW <= 0 || outH <= 0) return IntArray(0)
        val bgGray = (background and 0xFF)
        val out = IntArray(outW * outH) { grayArgb(page.backgroundColor and 0xFF) }
        val sx = src.width.toFloat() / outW
        val sy = src.height.toFloat() / outH
        for (y in 0 until outH) {
            val fy = src.top + (y + 0.5f) * sy - 0.5f
            val y0 = kotlin.math.floor(fy).toInt()
            val ty = fy - y0
            for (x in 0 until outW) {
                val fx = src.left + (x + 0.5f) * sx - 0.5f
                val x0 = kotlin.math.floor(fx).toInt()
                val tx = fx - x0
                val p00 = grayAt(page, x0, y0, bgGray)
                val p10 = grayAt(page, x0 + 1, y0, bgGray)
                val p01 = grayAt(page, x0, y0 + 1, bgGray)
                val p11 = grayAt(page, x0 + 1, y0 + 1, bgGray)
                val v = bilinear(p00, p10, p01, p11, tx, ty)
                out[y * outW + x] = grayArgb(v)
            }
        }
        return out
    }

    private fun grayAt(img: GrayImage, x: Int, y: Int, fallback: Int): Int =
        if (x < 0 || y < 0 || x >= img.width || y >= img.height) fallback else img[x, y]
}

/** 全页灰度图上的 [RegionSampler] 实现：按 `maxWidth` / `maxPixels` 降采样后返回。 */
class GrayPageRegionSampler(private val page: GrayImage) : RegionSampler {

    override fun gray(src: RectI, maxWidth: Int, maxPixels: Int): RegionImage {
        val crop = src.clampTo(page.width, page.height)
        if (crop.isEmpty) {
            return RegionImage(GrayImage(1, 1, ByteArray(1), page.backgroundColor), 1f)
        }
        val area = crop.width.toLong() * crop.height
        var r = 1f
        if (maxWidth > 0 && crop.width > maxWidth) r = minOf(r, maxWidth.toFloat() / crop.width)
        if (maxPixels > 0 && area > maxPixels) {
            r = minOf(r, kotlin.math.sqrt(maxPixels.toDouble() / area).toFloat())
        }
        r = r.coerceAtMost(1f).coerceAtLeast(0.02f)
        val w = max(1, (crop.width * r).roundToInt())
        val h = max(1, (crop.height * r).roundToInt())
        if (w == crop.width && h == crop.height) {
            val data = ByteArray(crop.width * crop.height)
            for (y in 0 until crop.height) {
                val base = (crop.top + y) * page.width + crop.left
                System.arraycopy(page.data, base, data, y * crop.width, crop.width)
            }
            return RegionImage(GrayImage(crop.width, crop.height, data, page.backgroundColor), 1f)
        }
        val data = ByteArray(w * h)
        val sx = crop.width.toFloat() / w
        val sy = crop.height.toFloat() / h
        for (y in 0 until h) {
            val fy = crop.top + (y + 0.5f) * sy - 0.5f
            val y0 = kotlin.math.floor(fy).toInt()
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = crop.left + (x + 0.5f) * sx - 0.5f
                val x0 = kotlin.math.floor(fx).toInt()
                val tx = fx - x0
                val p00 = at(x0, y0)
                val p10 = at(x0 + 1, y0)
                val p01 = at(x0, y0 + 1)
                val p11 = at(x0 + 1, y0 + 1)
                data[y * w + x] = bilinear(p00, p10, p01, p11, tx, ty).coerceIn(0, 255).toByte()
            }
        }
        return RegionImage(GrayImage(w, h, data, page.backgroundColor), w.toFloat() / crop.width)
    }

    private fun at(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= page.width || y >= page.height) {
            page.backgroundColor and 0xFF
        } else {
            page[x, y]
        }
}

private fun bilinear(p00: Int, p10: Int, p01: Int, p11: Int, tx: Float, ty: Float): Int {
    val top = p00 + (p10 - p00) * tx
    val bottom = p01 + (p11 - p01) * tx
    return (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
}

private fun grayArgb(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

/** 把一张 ARGB 图转成 [GrayImage]，供测试/CLI 拼装假页面。 */
fun grayOf(argb: IntArray, w: Int, h: Int, backgroundColor: Int = 0xFFFFFFFF.toInt()): GrayImage =
    Grayscale.fromArgb(w, h, argb, backgroundColor)
