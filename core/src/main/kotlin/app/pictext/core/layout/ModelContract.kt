package app.pictext.core.layout

/**
 * 模型契约：预处理/后处理的所有数字都从这里来，不允许散落在实现里。
 *
 * 默认值**已按官方 PP-DocLayout-S 的 `inference.yml` / `config.json` 核对**（不是社区 ONNX 口径）：
 *
 * ```text
 * Preprocess:
 *   - Resize        target_size: [480, 480], keep_ratio: false, interp: 2 (双线性)
 *   - NormalizeImage is_scale: true, mean: [0.485, 0.456, 0.406], std: [0.229, 0.224, 0.225]
 *   - Permute       HWC → CHW
 * NMS: MultiClassNMS keep_top_k 100, nms_top_k 1000, nms_threshold 0.5, score_threshold 0.3
 * label_list: 23 类（见 ModelLabels.LABELS）
 * ```
 *
 * 仍需在 M0 用实际 ONNX 确认的项：输入名（`image` + `scale_factor` 两路）、
 * 输出张量的形状与字段顺序、坐标是像素还是 0–1。它们集中记在
 * `models/doclayout/contract.json` 的 `unverified` 段里。
 *
 * @param name 模型名，写进 `LayoutSummary.model`。
 * @param modelInput 方形输入边长。
 * @param keepRatio true 时按比例缩放 + 居中 padding；官方是 false（直接拉伸）。
 * @param mean/std RGB 三通道，`((v/255) - mean) / std`。
 * @param padValue 需要 padding 时用的灰度值（0–255）。
 */
data class ModelContract(
    val name: String = "PP-DocLayout-S",
    val modelInput: Int = 480,
    val keepRatio: Boolean = false,
    val mean: List<Float> = listOf(0.485f, 0.456f, 0.406f),
    val std: List<Float> = listOf(0.229f, 0.224f, 0.225f),
    val padValue: Int = 114,
    val scoreThreshold: Float = 0.40f,
    val nmsIou: Float = 0.50f,
    val labels: List<String> = ModelLabels.LABELS,
) {
    init {
        require(mean.size == 3 && std.size == 3) { "mean/std must have 3 channels" }
        require(std.all { it > 0f }) { "std must be positive" }
    }

    fun labels(): ModelLabels = ModelLabels(labels)

    /** 把 [0,255] 的灰度/RGB 值归一化；供预处理与测试共用同一公式。 */
    fun normalize(channel: Int, value255: Float): Float {
        val c = channel.coerceIn(0, 2)
        return ((value255 / 255f) - mean[c]) / std[c]
    }
}
