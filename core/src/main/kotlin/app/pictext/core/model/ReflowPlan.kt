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
    ) : RenderItem

    data class Figure(
        override val src: RectI,
        override val dest: RectI,
        val blockIndex: Int,
        val kind: FigureKind = FigureKind.PHOTO,
        val scale: Float = 1f,
    ) : RenderItem
}

/**
 * 重排计划：只保存渲染项，不保存像素。阅读页按 [tileHeightPx] 高的瓦片按需渲染。
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
}
