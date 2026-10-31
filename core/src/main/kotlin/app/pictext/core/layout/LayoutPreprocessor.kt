package app.pictext.core.layout

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Tile 预处理：`ARGB tile → 480×480 RGB → 归一化 CHW float`。
 *
 * 官方 `inference.yml` 的顺序是 `Resize(keep_ratio=false) → NormalizeImage(is_scale=true) → Permute`，
 * 也就是**直接拉伸**到 480×480，不保比例；只有在页面本身比一个 tile 小时，
 * [Tile.padX] / [Tile.padY] 才不为 0，那部分用 [ModelContract.padValue] 填充（居中，用页面底色）。
 *
 * 输出写进调用方给的 [LayoutInput]，逐 tile 复用同一个缓冲（480×480×3 float = 2.7MB）。
 */
class LayoutPreprocessor(
    private val contract: ModelContract = ModelContract(),
) {

    /**
     * @param argb 采样结果，长度必须 ≥ `width × height`。
     * @param width/height tile 的源尺寸（= `tile.src.width/height`）。
     * @param tile 目标 tile；用它决定缩放与居中 padding。
     * @param out 复用的输入缓冲，尺寸必须是 `contract.modelInput` 见方。
     * @param grayOut 可选：同时产出模型尺寸的灰度（`modelInput²`），供覆盖审计用。
     *   和彩色输入走同一套双线性采样，保证“审计看到的墨迹”就是“模型看到的像素”。
     *   **不受 [invert] 影响**：审计要的是页面真实的墨迹分布（它的背景估计本来就吃得下深底）。
     * @param invert 深色页反相（V2-45）：模型是在浅底文档上训的，深底浅字属于分布外。
     *   反相只作用于**模型输入**（含 padding 填色），坐标不变，检测框照常映射回源图。
     */
    fun process(
        argb: IntArray,
        width: Int,
        height: Int,
        tile: Tile,
        out: LayoutInput,
        grayOut: ByteArray? = null,
        invert: Boolean = false,
    ) {
        val model = contract.modelInput
        require(out.width == model && out.height == model) {
            "LayoutInput is ${out.width}x${out.height}, contract wants ${model}x$model"
        }
        require(argb.size >= width * height) { "argb too small: ${argb.size} < ${width * height}" }
        require(width > 0 && height > 0) { "empty tile image: ${width}x$height" }
        if (grayOut != null) {
            require(grayOut.size >= model * model) { "grayOut too small: ${grayOut.size} < ${model * model}" }
            java.util.Arrays.fill(grayOut, 0, model * model, contract.padValue.toByte())
        }

        val pad = if (invert) 255 - contract.padValue else contract.padValue
        val rPlane = 0
        val gPlane = model * model
        val bPlane = 2 * model * model
        val data = out.data

        // 先把整张输入铺成 padValue 归一化后的值，再覆盖有效区。
        val padR = contract.normalize(0, pad.toFloat())
        val padG = contract.normalize(1, pad.toFloat())
        val padB = contract.normalize(2, pad.toFloat())
        java.util.Arrays.fill(data, rPlane, rPlane + model * model, padR)
        java.util.Arrays.fill(data, gPlane, gPlane + model * model, padG)
        java.util.Arrays.fill(data, bPlane, bPlane + model * model, padB)

        val x0 = tile.padX
        val y0 = tile.padY
        val x1 = (tile.padX + tile.innerWidth).coerceAtMost(model)
        val y1 = (tile.padY + tile.innerHeight).coerceAtMost(model)
        for (my in y0 until y1) {
            val fy = (my - tile.padY + 0.5f) * height / tile.innerHeight - 0.5f
            val sy0 = floor(fy).toInt()
            val ty = fy - sy0
            for (mx in x0 until x1) {
                val fx = (mx - tile.padX + 0.5f) * width / tile.innerWidth - 0.5f
                val sx0 = floor(fx).toInt()
                val tx = fx - sx0
                val p00 = argb[sy0.coerceIn(0, height - 1) * width + sx0.coerceIn(0, width - 1)]
                val p10 = argb[sy0.coerceIn(0, height - 1) * width + (sx0 + 1).coerceIn(0, width - 1)]
                val p01 = argb[(sy0 + 1).coerceIn(0, height - 1) * width + sx0.coerceIn(0, width - 1)]
                val p11 = argb[(sy0 + 1).coerceIn(0, height - 1) * width + (sx0 + 1).coerceIn(0, width - 1)]
                val r = mix((p00 ushr 16) and 0xFF, (p10 ushr 16) and 0xFF, (p01 ushr 16) and 0xFF, (p11 ushr 16) and 0xFF, tx, ty)
                val g = mix((p00 ushr 8) and 0xFF, (p10 ushr 8) and 0xFF, (p01 ushr 8) and 0xFF, (p11 ushr 8) and 0xFF, tx, ty)
                val b = mix(p00 and 0xFF, p10 and 0xFF, p01 and 0xFF, p11 and 0xFF, tx, ty)
                val idx = my * model + mx
                // 灰度永远取原图（审计用）；模型输入在深色页上取反相值。
                if (grayOut != null) {
                    grayOut[idx] = ((77 * r + 150 * g + 29 * b) / 256f).roundToInt()
                        .coerceIn(0, 255).toByte()
                }
                val mr = if (invert) 255f - r else r
                val mg = if (invert) 255f - g else g
                val mb = if (invert) 255f - b else b
                data[rPlane + idx] = contract.normalize(0, mr)
                data[gPlane + idx] = contract.normalize(1, mg)
                data[bPlane + idx] = contract.normalize(2, mb)
            }
        }
    }

    private fun mix(p00: Int, p10: Int, p01: Int, p11: Int, tx: Float, ty: Float): Float {
        val top = p00 + (p10 - p00) * tx
        val bottom = p01 + (p11 - p01) * tx
        return top + (bottom - top) * ty
    }
}
