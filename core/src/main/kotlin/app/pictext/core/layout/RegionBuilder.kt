package app.pictext.core.layout

import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RegionSource
import kotlin.math.max
import kotlin.math.min

/**
 * 把模型框变成“区域”：**全局去重 → 包含去重 → 文本合并**（docs/07 §4.4 的第 1–3 步）。
 *
 * 这里只做几何，不看像素；`figure↔text` 的二次判定在细化之后（[RegionKindFixer]）。
 *
 * 三条不能破的规则：
 * 1. 跨 tile 的重复框只留一个（重叠区同一目标会被两个 tile 各检出一次）；
 * 2. 包含关系按“谁装得下内容”取舍，不是按分数；
 * 3. **横向并排不合并**：A/C 与 B 在同一横带里，y 有重叠也不能并成一个区域——
 *    它们靠阅读顺序（竖向断列）分开，合并就再也分不开了。
 */
class RegionBuilder(
    private val options: LayoutOptions = LayoutOptions(),
    private val labels: ModelLabels = ModelLabels(),
) {

    /**
     * @param detections 已映射到 S 坐标的检测框（可以来自多个 tile）。
     * @param page 页面矩形，用于裁剪。
     * @param lineHeightHint 行高提示（S 像素）；`<=0` 时用“框高的 1/4”作代理。
     */
    fun build(
        detections: List<MappedDetection>,
        page: RectI,
        lineHeightHint: Float = 0f,
    ): List<LayoutRegion> {
        if (detections.isEmpty()) return emptyList()

        // 1) 有效框 + 跨 tile 全局去重（同类别 IoU）。
        val valid = detections
            .mapNotNull { d ->
                val b = d.bounds.clampTo(page.right, page.bottom)
                if (b.width < 2 || b.height < 2) null else d.copy(bounds = b)
            }
        val deduped = Nms.suppress(valid, options.nmsIou)

        // 2) 包含去重：面积大的先占位，被装下 ≥80% 的小框丢弃。
        //    （text 在 figure 里、figure 在 text 里、同族嵌套都走这一条；
        //     真正“这是图还是字”的判断留给细化后的 RegionKindFixer。）
        val byArea = deduped.sortedWith(
            compareByDescending<MappedDetection> { it.bounds.width.toLong() * it.bounds.height }
                .thenByDescending { it.score },
        )
        val kept = ArrayList<MappedDetection>(byArea.size)
        for (d in byArea) {
            val contained = kept.any { k ->
                overlapOfBInA(k.bounds, d.bounds) >= options.containmentRatio
            }
            if (!contained) kept.add(d)
        }

        // 3) 同一列里被拆碎的 TEXT 框合并（模型常把一段切成两个框）。
        val merged = mergeTextFragments(kept, lineHeightHint)

        // 4) 产出区域；id 按阅读顺序无关的“从上到下、从左到右”编号，order 由 ReadingOrder 写。
        val ordered = merged.sortedWith(
            compareBy<MappedDetection> { it.bounds.top }.thenBy { it.bounds.left },
        )
        return ordered.mapIndexed { index, d ->
            LayoutRegion(
                id = index,
                kind = d.kind,
                bounds = d.bounds,
                confidence = d.score,
                source = RegionSource.MODEL,
                label = d.label.ifEmpty { labels.labelOf(d.labelId) },
            )
        }
    }

    /**
     * 文本碎片合并。
     *
     * 触发条件（全部满足）：
     * - 两个框都是 TEXT（TITLE/CAPTION 不合并：标题与图注的层级不能被打乱）；
     * - 横向重叠 ≥ [LayoutOptions.textMergeOverlapRatio]（并排的两栏几乎不重叠，天然排除）；
     * - 垂直间距 ≤ `textMergeGapRatio × 行高代理`。
     *
     * 行高代理为什么不用“行高中位数”：这里还没细化，没有行高。用两个框高度较小者的 1/4
     * 作为代理（一个段落通常 3–5 行），再夹到 `[6, 64]`，既能合并同一段的碎片，
     * 也不会把“两个独立段落”吞成一块。
     */
    private fun mergeTextFragments(
        detections: List<MappedDetection>,
        lineHeightHint: Float,
    ): List<MappedDetection> {
        val text = detections.filter { it.kind == RegionKind.TEXT }
        val others = detections.filter { it.kind != RegionKind.TEXT }
        if (text.size <= 1) return detections

        val sorted = text.sortedBy { it.bounds.top }.toMutableList()
        val merged = ArrayList<MappedDetection>(sorted.size)
        for (d in sorted) {
            val last = merged.lastOrNull()
            if (last != null && canMerge(last, d, lineHeightHint)) {
                val union = last.bounds.union(d.bounds)
                merged[merged.size - 1] = last.copy(
                    bounds = union,
                    score = max(last.score, d.score),
                    kind = RegionKind.TEXT,
                )
            } else {
                merged.add(d)
            }
        }
        return merged + others
    }

    private fun canMerge(a: MappedDetection, b: MappedDetection, hint: Float): Boolean {
        if (a.kind != RegionKind.TEXT || b.kind != RegionKind.TEXT) return false
        // 只看**横向**重叠：上下相接的两个碎片在 2D 上并不相交，用 2D IoU 永远合不上。
        val overlapX = min(a.bounds.right, b.bounds.right) - max(a.bounds.left, b.bounds.left)
        val narrower = min(a.bounds.width, b.bounds.width)
        if (narrower <= 0 || overlapX.toFloat() / narrower < options.textMergeOverlapRatio) return false
        val gap = if (b.bounds.top > a.bounds.bottom) {
            b.bounds.top - a.bounds.bottom
        } else {
            max(0, a.bounds.top - b.bounds.bottom)
        }
        val proxy = if (hint > 0f) {
            hint
        } else {
            min(a.bounds.height, b.bounds.height) / 4f
        }.coerceIn(6f, 64f)
        return gap <= options.textMergeGapRatio * proxy
    }
}
