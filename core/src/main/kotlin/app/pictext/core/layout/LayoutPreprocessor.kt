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

    /** 有效区（去掉 padding 后）的边长；测试用它断言“同一 tile 重复采样结果一致”。 */
    fun validSize(tile: Tile): Int = tile.innerWidth * tile.innerHeight

    /**
     * @param argb 采样结果，长度必须 ≥ `width × height`。
     * @param width/height tile 的源尺寸（= `tile.src.width/height`）。
     * @param tile 目标 tile；用它决定缩放与居中 padding。
     * @param out 复用的输入缓冲，尺寸必须是 `contract.modelInput` 见方。
     * @param grayOut 可选：同时产出模型尺寸的灰度（`modelInput²`），供覆盖审计用。
     *   和彩色输入走同一套双线性采样，保证“审计看到的墨迹”就是“模型看到的像素”。
     */
    fun process(
        argb: IntArray,
        width: Int,
        height: Int,
        tile: Tile,
        out: LayoutInput,
        grayOut: ByteArray? = null,
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

        val pad = contract.padValue
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
                data[rPlane + idx] = contract.normalize(0, r)
                data[gPlane + idx] = contract.normalize(1, g)
                data[bPlane + idx] = contract.normalize(2, b)
                if (grayOut != null) {
                    grayOut[idx] = ((77 * r + 150 * g + 29 * b) / 256f).roundToInt()
                        .coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun mix(p00: Int, p10: Int, p01: Int, p11: Int, tx: Float, ty: Float): Float {
        val top = p00 + (p10 - p00) * tx
        val bottom = p01 + (p11 - p01) * tx
        return top + (bottom - top) * ty
    }

    companion object {
        /** 反向公式：模型坐标 → tile 源像素坐标，供调试叠加。 */
        fun modelToTile(tile: Tile, modelX: Int, modelY: Int, width: Int, height: Int): FloatArray {
            val x = (modelX - tile.padX + 0.5f) * width / tile.innerWidth - 0.5f
            val y = (modelY - tile.padY + 0.5f) * height / tile.innerHeight - 0.5f
            return floatArrayOf(x, y)
        }

        /** 归一化值的逆变换，用于把构建出来的输入还原成灰度（覆盖审计/调试）。 */
        fun denormalizeGray(contract: ModelContract, r: Float, g: Float, b: Float): Int {
            val rr = (r * contract.std[0] + contract.mean[0]) * 255f
            val gg = (g * contract.std[1] + contract.mean[1]) * 255f
            val bb = (b * contract.std[2] + contract.mean[2]) * 255f
            val y = (77 * rr + 150 * gg + 29 * bb) / 256f
            return y.roundToInt().coerceIn(0, 255)
        }
    }
}
