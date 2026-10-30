package app.pictext.core.layout

import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import kotlin.math.max
import kotlin.math.min

/**
 * 模型的一次原始输出，坐标是**模型输入空间**（0..`modelInput`）。
 *
 * 用 Float 而不是 Int：PicoDet 的 stride 是 8/16/32/64，反算到源图会有 18–144px 的量化误差，
 * 提前取整只会把误差叠进映射。
 */
data class Detection(
    val labelId: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val isValid: Boolean get() = right > left && bottom > top
}

/**
 * 映射回 S 坐标的检测框，是 [DetectionPostprocessor] 的产物、[RegionBuilder] 的输入。
 *
 * @param tileIndex 哪个 tile 检出的，重复框调试用。
 * @param kind 由 [ModelLabels] 映射出的语义类别。
 */
data class MappedDetection(
    val labelId: Int,
    val score: Float,
    val bounds: RectI,
    val tileIndex: Int,
    val label: String = "",
    val kind: RegionKind = RegionKind.OTHER,
) {
    val area: Int get() = max(0, bounds.width) * max(0, bounds.height)
}

/** 两个矩形的交并比（半开区间）。任一为空时返回 0。 */
fun iou(a: RectI, b: RectI): Float {
    val inter = a.intersect(b)
    if (inter.isEmpty) return 0f
    val ia = inter.width.toLong() * inter.height
    val union = a.width.toLong() * a.height + b.width.toLong() * b.height - ia
    return if (union <= 0L) 0f else ia.toFloat() / union
}

/** `A ∩ B` 占 `B` 的比例，用于包含关系判定（§4.4.2）。 */
fun overlapOfBInA(a: RectI, b: RectI): Float {
    if (b.isEmpty) return 0f
    val inter = a.intersect(b)
    if (inter.isEmpty) return 0f
    return inter.width.toFloat() * inter.height / (b.width.toFloat() * b.height)
}

/** `A ∩ B` 占较窄者的比例，用于“两个框横向重叠 ≥50%”。 */
fun overlapOfNarrower(a: RectI, b: RectI): Float {
    val inter = a.intersect(b)
    if (inter.isEmpty) return 0f
    val interW = inter.width.toFloat()
    val narrow = min(a.width, b.width).toFloat()
    return if (narrow <= 0f) 0f else interW / narrow
}

/**
 * 类别内非极大值抑制。
 *
 * 按分数降序贪心保留；只抑制**同类别**的框（`cross_class = false`，默认），
 * 因为 `text` 套在 `image` 里是合法的图注/叠字关系，跨类别抑制会丢内容。
 */
object Nms {

    fun suppress(detections: List<MappedDetection>, iouThreshold: Float): List<MappedDetection> {
        if (detections.size <= 1) return detections
        val sorted = detections.sortedByDescending { it.score }
        val kept = ArrayList<MappedDetection>(sorted.size)
        for (d in sorted) {
            var suppressed = false
            for (k in kept) {
                if (k.labelId != d.labelId) continue
                if (iou(k.bounds, d.bounds) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) kept.add(d)
        }
        return kept
    }

    /** 同类且几乎重合才算重复；用于跨 tile 去重（重叠区同一目标被检出两次）。 */
    fun dedupeAcrossTiles(detections: List<MappedDetection>, iouThreshold: Float): List<MappedDetection> =
        suppress(detections, iouThreshold)
}
