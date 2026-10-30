package app.pictext.core.model

/** 计划的整体模式。 */
enum class PlanMode {
    /** 文字行按左右两半重排。 */
    REFLOW,

    /** 整页只放大、不切分（例如切点不干净或用户关闭切分）。 */
    NO_SPLIT,

    /** 放弃重排，展示原图。 */
    ORIGINAL,
}

/** 回退原因；`NONE` 表示没有回退。 */
enum class FallbackReason {
    NONE,
    NO_INK,
    NO_TEXT,
    TOO_FEW_LONG_LINES,
    WEAK_CUT,
    GAIN_TOO_SMALL,
    TOO_TALL,

    /** 检测到多栏版面（中线附近有贯通全页的低墨竖带），重排会错位，直接看原图。 */
    MULTI_COLUMN,

    /** v2 版式分析不可用（模型缺失/加载失败/无检出/覆盖不足等），已降级到 v1。 */
    LAYOUT_FALLBACK,
}

/**
 * 一行被切成 [CutKind] 记录的那一刀属于哪一级（docs/07 §4.8.2）。
 *
 * 顺序即优先级：硬缝 > 软缝 > 强制。审计按级别统计 `forcedCutLines`；
 * 敏感行（拉丁/URL/代码/价格）**不允许出现 [FORCED]**。
 */
enum class CutKind {
    /** 没有切（整行一段）。 */
    NONE,

    /** 硬缝：整行为零墨列，宽度 ≥ `max(2, 0.10×行高)`。 */
    HARD,

    /** 软缝：1–2px 低墨列组。 */
    SOFT,

    /** 强制：目标附近墨量最小的列，可能劈开一个字；每行最多一次。 */
    FORCED,
}

/**
 * v2 版面分析摘要，只用于 CLI/调试/审计，不参与渲染。
 *
 * @param coverage 整页墨迹覆盖率 `ΣcoveredInk / Σink`，硬门槛 99.5%。
 * @param residuals 残差补区个数。
 * @param orderAmbiguous XY-cut 两个方向都切不开，退回稳定排序。
 * @param verticalRatio 竖排文字区域占总文字区域面积的比例。
 */
data class LayoutSummary(
    val model: String = "",
    val tiles: Int = 0,
    val regions: Int = 0,
    val coverage: Float = 1f,
    val residuals: Int = 0,
    val orderAmbiguous: Boolean = false,
    val verticalRatio: Float = 0f,
    val forcedCutLines: Int = 0,
    val splitLines: Int = 0,
    val compressedLines: Int = 0,
    val sensitiveCompressedLines: Int = 0,
    val maxSegments: Int = 1,
    val regionGap: Int = 0,
) {
    /** 强制切行占比（相对切分过的行数）。 */
    val forcedCutRatio: Float
        get() = if (splitLines <= 0) 0f else forcedCutLines.toFloat() / splitLines
}

/**
 * 一个渲染项：从源图的 [src] 采样，画到输出图的 [dest]。
 * `src` 是 S 坐标，`dest` 是 O 坐标；`dest` 只决定位置，不改变顺序。
 */
sealed interface RenderItem {
    val src: RectI
    val dest: RectI

    data class TextRow(
        override val src: RectI,
        override val dest: RectI,
        val lineIndex: Int,
        val role: LineRole = LineRole.BODY,
        /** 该行实际使用的缩放倍数，`dest.width / src.width`（切分后仍有 ±1px 误差）。 */
        val scale: Float = 1f,
        /** [v2] 所属区域序号；`-1` 表示 v1 投影链路。仅用于审计/调试。 */
        val regionIndex: Int = -1,
        /** [v2] 该行第几段（从 0 开始）。 */
        val segmentIndex: Int = 0,
        /** [v2] 该行一共切成几段。 */
        val segments: Int = 1,
        /** [v2] 这一段的左边界是哪种刀口切出来的。 */
        val cut: CutKind = CutKind.NONE,
    ) : RenderItem

    data class Figure(
        override val src: RectI,
        override val dest: RectI,
        val blockIndex: Int,
        val kind: FigureKind = FigureKind.PHOTO,
        val scale: Float = 1f,
        /** [v2] 所属区域序号；`-1` 表示 v1 投影链路。仅用于审计/调试。 */
        val regionIndex: Int = -1,
    ) : RenderItem
}

/**
 * 重排计划：只保存渲染项，不保存像素。阅读页按 [tileHeightPx] 高的瓦片按需渲染。
 *
 * @param bodyScale 全页统一倍率 `s`。v2 下 TEXT/TITLE/CAPTION 的实际缩放都必须与它相差 ≤2%。
 * @param strategy 走的是 v1 投影链路还是 v2 版式链路。
 * @param layout v2 的版面摘要；v1 为 `null`。
 */
data class ReflowPlan(
    val outputWidth: Int,
    val height: Int,
    val scale: Float,
    val gain: Float,
    val mode: PlanMode,
    val fallback: FallbackReason,
    val items: List<RenderItem>,
    val tileHeightPx: Int = 1024,
    val notes: List<String> = emptyList(),
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val isDark: Boolean = false,
    /** 本页统一的正文缩放倍数；BODY 行的实际缩放必须与之相差 ≤5%。 */
    val bodyScale: Float = scale,
    val strategy: LayoutStrategy = LayoutStrategy.PROJECTION,
    val layout: LayoutSummary? = null,
) {
    val tileCount: Int get() = if (height <= 0) 0 else (height + tileHeightPx - 1) / tileHeightPx
    fun tileTop(i: Int): Int = i * tileHeightPx
    fun tileHeight(i: Int): Int = minOf(tileHeightPx, height - tileTop(i)).coerceAtLeast(0)

    /** 与 [tileRect]（O 坐标）相交的渲染项，按 `dest.top` 二分定位。 */
    fun itemsIntersecting(tileRect: RectI): List<RenderItem> {
        if (items.isEmpty() || tileRect.isEmpty) return emptyList()
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].dest.bottom <= tileRect.top) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<RenderItem>()
        var i = lo
        while (i < items.size && items[i].dest.top < tileRect.bottom) {
            if (items[i].dest.intersect(tileRect).isEmpty.not()) out.add(items[i])
            i++
        }
        return out
    }

    /** 点在 O 坐标中命中的图块，用于点按全屏。 */
    fun figureAt(x: Int, y: Int): RenderItem.Figure? =
        items.firstOrNull { it is RenderItem.Figure && it.dest.contains(x, y) } as? RenderItem.Figure

    /** v2：被强制切过的行数（按 lineIndex 去重，取每个 lineIndex 的第一个 item）。 */
    fun forcedCutLines(): Int = items
        .filterIsInstance<RenderItem.TextRow>()
        .filter { it.cut == CutKind.FORCED }
        .map { it.lineIndex }
        .distinct()
        .size

    /** v2：被切成 ≥2 段的行数。 */
    fun splitLines(): Int = items
        .filterIsInstance<RenderItem.TextRow>()
        .filter { it.segments > 1 }
        .map { it.lineIndex }
        .distinct()
        .size

    /** v2：单行最大段数。 */
    fun maxSegments(): Int = items.filterIsInstance<RenderItem.TextRow>().maxOfOrNull { it.segments } ?: 1
}
