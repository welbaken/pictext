package app.pictext.core.layout

import app.pictext.core.model.RectI

/**
 * 检测后处理：**阈值过滤 → 裁剪到有效区 → 映射到 S → 类别内 NMS**（docs/07 V2-10）。
 *
 * 不做跨 tile 去重（那是 [RegionBuilder] 的职责）：这里只保证“一个 tile 的输出是干净的”。
 *
 * 坐标链见 [Tile]；映射时框的四边都各自线性变换，再四舍五入到 S 像素，
 * 因此 1080/480 的比例下误差 ≤1px。
 */
class DetectionPostprocessor(
    private val options: LayoutOptions = LayoutOptions(),
    private val labels: ModelLabels = ModelLabels(),
) {

    fun postprocess(tile: Tile, detections: List<Detection>, page: RectI): List<MappedDetection> {
        if (detections.isEmpty()) return emptyList()
        val valid = tile.validModelRect()
        val out = ArrayList<MappedDetection>(detections.size)
        for (d in detections) {
            if (d.score < options.scoreThreshold) continue
            if (!d.isValid) continue
            // 只保留与有效区相交的部分：填充区里不可能有真目标，模型偶尔会在那里瞎猜。
            val l = d.left.coerceIn(valid.left.toFloat(), valid.right.toFloat())
            val t = d.top.coerceIn(valid.top.toFloat(), valid.bottom.toFloat())
            val r = d.right.coerceIn(valid.left.toFloat(), valid.right.toFloat())
            val b = d.bottom.coerceIn(valid.top.toFloat(), valid.bottom.toFloat())
            if (r - l <= 0.5f || b - t <= 0.5f) continue
            val bounds = tile.sourceRect(l, t, r, b, page)
            if (bounds.isEmpty) continue
            out.add(
                MappedDetection(
                    labelId = d.labelId,
                    score = d.score,
                    bounds = bounds,
                    tileIndex = tile.index,
                    label = labels.labelOf(d.labelId),
                    kind = labels.kindOfId(d.labelId),
                ),
            )
        }
        return Nms.suppress(out, options.nmsIou)
    }
}
