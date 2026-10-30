package app.pictext.core.model

/**
 * 版面区域的语义类别。由模型标签映射而来（见 `core/layout/ModelLabels.kt`），
 * **未知标签一律 [OTHER]**：整块保留、不切分，宁可排版难看，不可丢内容。
 *
 * | 类别 | 缩放 | 切分 |
 * |---|---|---|
 * | [TEXT] | 全页统一倍率 `s` | 按需 N 段（CJK 允许每行一次强制切） |
 * | [TITLE] | 全页统一倍率 `s` | 按需 N 段（CJK 允许每行一次强制切） |
 * | [CAPTION] | 全页统一倍率 `s` | 只走硬/软缝，**不强制** |
 * | [FIGURE] | `clamp(列宽/宽, 1.0, 2.2)` | 不切 |
 * | [TABLE] | `min(2.2, 列宽/宽)`，超宽缩小 | 不切 |
 * | [CHROME] | `min(s, 1.0)` | 不切 |
 * | [OTHER] | `min(1.0, 列宽/宽)` | 不切 |
 */
enum class RegionKind {
    TEXT,
    TITLE,
    FIGURE,
    TABLE,
    CAPTION,
    CHROME,
    OTHER,
}

/** 区域从哪来。`MODEL_FIXED` 是二次判定改过类别的模型区域；`RESIDUAL` 是覆盖审计补出来的。 */
enum class RegionSource {
    MODEL,
    MODEL_FIXED,
    RESIDUAL,
    HEURISTIC,
}

/**
 * 版面分析走的哪条链路。
 *
 * - [PROJECTION]：v1，全页水平投影切行 + 左右对半切；坐标是 A 坐标；
 * - [DOC_LAYOUT]：v2，模型区域 + 阅读顺序 + 按需 N 段；坐标是 **S 坐标**（`analysisScale = 1`）。
 */
enum class LayoutStrategy {
    PROJECTION,
    DOC_LAYOUT,
}

/**
 * 一个版面区域。
 *
 * 坐标系统：v2 下 [bounds] 与 [TextLine.band] 都是 **S 坐标**（源图像素），
 * 与 `PageLayout.analysisScale = 1.0` 一致；模型框、细化行带、渲染矩形三者不必再换算。
 *
 * @param lines 细化后的文字行；非文字区域为空。行序即区域内的阅读顺序。
 * @param order 全局阅读顺序，从 0 开始；`-1` 表示还没排。
 * @param confidence 模型分数；补区/启发式区域为 1。
 * @param label 模型原始标签，仅用于调试/统计，不参与任何判定。
 * @param vertical 该区域是否被判定为竖排文字（竖排占比过高时整页回退原图）。
 */
data class LayoutRegion(
    val id: Int,
    val kind: RegionKind,
    val bounds: RectI,
    val lines: List<TextLine> = emptyList(),
    val order: Int = -1,
    val confidence: Float = 1f,
    val source: RegionSource = RegionSource.MODEL,
    val label: String = "",
    val vertical: Boolean = false,
) {
    /** 参与“文字倍率统一”的区域：TEXT / TITLE / CAPTION。 */
    val isTextual: Boolean get() = kind == RegionKind.TEXT || kind == RegionKind.TITLE ||
        kind == RegionKind.CAPTION

    /** 需要整块保留像素的区域：图/表/其它。 */
    val isBlockLevel: Boolean get() = kind == RegionKind.FIGURE || kind == RegionKind.TABLE ||
        kind == RegionKind.OTHER

    /** 区域内文字行的外接框；无行时退回 [bounds]。 */
    fun inkBounds(): RectI = lines.fold(RectI.EMPTY) { acc, l -> acc.union(l.band) }

    fun withBounds(r: RectI): LayoutRegion = copy(bounds = r)

    fun withLines(ls: List<TextLine>): LayoutRegion = copy(
        lines = ls,
        bounds = if (ls.isEmpty()) bounds else ls.fold(bounds) { acc, l -> acc.union(l.band) },
    )

    fun withOrder(o: Int): LayoutRegion = copy(order = o)

    fun withKind(k: RegionKind, src: RegionSource): LayoutRegion = copy(kind = k, source = src)
}
