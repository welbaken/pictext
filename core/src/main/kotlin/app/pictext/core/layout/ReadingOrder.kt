package app.pictext.core.layout

import app.pictext.core.model.LayoutRegion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 递归 XY-cut 阅读顺序（docs/07 §4.7.1）。
 *
 * 每一步都同时求两个方向的“贯通空白”，**选断口更显著的那个轴**（见 [gapSignificance]）：
 * 1. 沿 y 轴投影得到横带（高度 ≥ `minGapY`），按 top 升序逐带递归；
 * 2. 沿 x 轴投影得到竖列（宽度 ≥ `minGapX`），按 left 升序逐列递归；
 * 3. 两个方向都切不开时退回 `(top, left)` 稳定排序，并置 `ambiguous = true`。
 *
 * > 这里与 docs/07 §4.7.1 的伪代码有一处**刻意的偏差**：伪代码写“先横带、后竖列”，
 * > 实测双栏文档会因此排错（见 §13.2 第 14 条）。断口显著性比较能把“分栏”和“分段”区分开。
 *
 * ## 为什么断口必须“整条贯通”
 * 断口不是“某两个相邻框之间够远”，而是**一条横线（竖线）穿过整页时不碰到任何框**。
 * 若按相邻框两两比较间距，双栏页里左栏某段与右栏某段高度不同，任一列看起来都会出现
 * 可用的“横带断口”，结果会把双栏切成上下两半，阅读顺序变成“左一、右一”的交叉。
 * 把全部框投影到轴上后，左右栏交错的分段会互相填满对方的空隙，只有整页都没有框覆盖的
 * 区间才会成为断口，双栏页因此正确地先切竖列、读完左栏再读右栏。
 *
 * ## 为什么重叠 1px 不算断口
 * [app.pictext.core.model.RectI] 是半开区间 `[top, bottom)`，1px 重叠处仍有像素被框覆盖。
 * 扫描线把同一坐标上的 top/bottom 事件**成组**结算：只有“结算前覆盖数 > 0、结算后 == 0”
 * 才开启一段空白，“结算前 == 0、结算后 > 0”才结束它。于是 1px 重叠（以及边界正好相接）
 * 处覆盖数始终 ≥1，不会产生零宽或负宽的假断口；另外空白宽度还必须 ≥ [LayoutOptions.orderMinGapPx]，
 * 真实但过窄的 1px 缝隙同样不会被误认为断带。
 *
 * 本类只读入参：所有派生列表都是新建的，最终通过 [LayoutRegion.withOrder] 返回副本，
 * 调用方持有的 `regions` 列表及其元素都不会被修改。
 */
class ReadingOrder(private val options: LayoutOptions = LayoutOptions()) {

    /**
     * 计算区域阅读顺序，并把从 0 开始连续递增的序号写回结果中的副本。
     *
     * @param regions 待排序区域（S 坐标），不会被修改。
     * @param medianLineHeight 全页行高中位数（S 像素）；`<= 0`（含 NaN）时内部按
     *   [FALLBACK_LINE_HEIGHT] 兜底，避免“一行都没检出”的页面把断口门槛压成 0。
     * @return 排好序的副本列表与歧义标记；只有“两个方向都切不开且区域数 > 1”或
     *   “递归超过 [LayoutOptions.orderMaxDepth]”时 `ambiguous` 才为 `true`。
     */
    fun order(regions: List<LayoutRegion>, medianLineHeight: Float): OrderResult {
        if (regions.isEmpty()) return OrderResult(regions = emptyList(), ambiguous = false)

        val lineHeight = if (medianLineHeight > 0f) medianLineHeight else FALLBACK_LINE_HEIGHT
        val minGapY = max(options.orderMinGapPx, (options.orderMinGapYRatio * lineHeight).roundToInt())
        val minGapX = max(options.orderMinGapPx, (options.orderMinGapXRatio * lineHeight).roundToInt())

        val raw = cut(regions, depth = 0, minGapX = minGapX, minGapY = minGapY)
        return OrderResult(
            regions = raw.regions.mapIndexed { index, region -> region.withOrder(index) },
            ambiguous = raw.ambiguous,
        )
    }

    /**
     * 递归主体：返回的区域仍是入参对象的引用，序号统一由 [order] 在最后写回，
     * 这样中间层不需要反复 copy，也不会碰到调用方的对象。
     */
    private fun cut(
        regions: List<LayoutRegion>,
        depth: Int,
        minGapX: Int,
        minGapY: Int,
    ): OrderResult {
        if (regions.size <= 1) return OrderResult(regions, ambiguous = false)

        // 防御异常输入：超过深度上限不再尝试切分，稳定排序后标歧义，绝不抛异常。
        if (depth >= options.orderMaxDepth) {
            return OrderResult(topLeftSorted(regions), ambiguous = true)
        }

        val bands = splitByFullWidthGaps(regions, minGapY)
        val columns = splitByFullHeightGaps(regions, minGapX)
        val canBands = bands.size > 1
        val canCols = columns.size > 1

        // 两个方向都能切时，**选断口更显著的那个轴**，而不是无条件先横带。
        //
        // 为什么：双栏文档的左右栏各自有自己的段间距，很容易在某个 y 上“两栏同时留白”，
        // 于是横带断口成立，页面被切成上下两半，顺序变成“左一段、右一段”交叉
        // （`11-doc-two-column` 的 GT 就是这么被排坏的）。栏间空白通常比段间距宽得多，
        // 用“断口宽度 / 该轴跨度”比较两个方向，就能把“分栏”和“分节”区分开。
        if (canBands && canCols) {
            val verticalSignificance = gapSignificance(regions, byY = false)
            val horizontalSignificance = gapSignificance(regions, byY = true)
            return if (verticalSignificance > horizontalSignificance) {
                concatInOrder(columns, depth, minGapX, minGapY)
            } else {
                concatInOrder(bands, depth, minGapX, minGapY)
            }
        }
        if (canBands) return concatInOrder(bands, depth, minGapX, minGapY)
        if (canCols) return concatInOrder(columns, depth, minGapX, minGapY)

        return OrderResult(topLeftSorted(regions), ambiguous = true)
    }

    /**
     * 该轴上最大断口相对跨度的比例。
     *
     * 例如双栏页：栏间空白 40px / 页宽 1080 = 0.037；两段文字之间的段间距 20px / 页高 1200 = 0.017，
     * 于是“分栏”优先于“分节”。单栏页只有横带断口，走不到这里。
     */
    private fun gapSignificance(regions: List<LayoutRegion>, byY: Boolean): Float {
        val spans = emptySpans(regions, byY)
        if (spans.isEmpty()) return 0f
        var minStart = Int.MAX_VALUE
        var maxEnd = Int.MIN_VALUE
        for (r in regions) {
            val start = if (byY) r.bounds.top else r.bounds.left
            val end = if (byY) r.bounds.bottom else r.bounds.right
            if (start < minStart) minStart = start
            if (end > maxEnd) maxEnd = end
        }
        val extent = (maxEnd - minStart).toFloat()
        if (extent <= 0f) return 0f
        val largest = spans.maxOf { it.end - it.start }.toFloat()
        return largest / extent
    }

    /** 依次递归每个分组并拼接；任一子结果有歧义，父结果就带歧义。 */
    private fun concatInOrder(
        groups: List<List<LayoutRegion>>,
        depth: Int,
        minGapX: Int,
        minGapY: Int,
    ): OrderResult {
        val ordered = ArrayList<LayoutRegion>()
        var ambiguous = false
        for (group in groups) {
            val part = cut(group, depth + 1, minGapX, minGapY)
            ordered.addAll(part.regions)
            ambiguous = ambiguous || part.ambiguous
        }
        return OrderResult(ordered, ambiguous)
    }

    /** 横带：空白必须在 x 方向贯穿整页（所有框都不覆盖这段 y）。 */
    private fun splitByFullWidthGaps(
        regions: List<LayoutRegion>,
        minGapY: Int,
    ): List<List<LayoutRegion>> = splitByProjection(regions, byY = true, minGap = minGapY)

    /** 竖列：空白必须在 y 方向贯穿整页（所有框都不覆盖这段 x）。 */
    private fun splitByFullHeightGaps(
        regions: List<LayoutRegion>,
        minGapX: Int,
    ): List<List<LayoutRegion>> = splitByProjection(regions, byY = false, minGap = minGapX)

    /**
     * 沿一个轴做投影扫描线，在**挑出来的那一刀**处一分为二。
     *
     * 为什么只切一刀、而不是把所有空白都切成多组：一次切光会把“双栏正文”按每一段的段间距
     * 切成十几条横带，顺序直接变成“左一段、右一段”（`11-doc-two-column` 实测就是这么坏的）。
     * 教科书版 XY-cut 每次只切最显著的那一刀，递归下去自然得到“先分节、再分栏、再分段”。
     *
     * 返回按轴升序的两组；找不到合格空白时返回只含原列表的单元素列表。
     */
    private fun splitByProjection(
        regions: List<LayoutRegion>,
        byY: Boolean,
        minGap: Int,
    ): List<List<LayoutRegion>> {
        val gap = chooseGap(regions, byY, minGap) ?: return listOf(regions)

        val ordered = regions.sortedWith(
            compareBy<LayoutRegion>(
                { if (byY) it.bounds.top else it.bounds.left },
                { if (byY) it.bounds.bottom else it.bounds.right },
            ),
        )
        val before = ArrayList<LayoutRegion>(ordered.size)
        val after = ArrayList<LayoutRegion>(ordered.size)
        for (region in ordered) {
            val start = if (byY) region.bounds.top else region.bounds.left
            if (start < gap.end) before.add(region) else after.add(region)
        }
        return when {
            before.isEmpty() || after.isEmpty() -> listOf(regions)
            else -> listOf(before, after)
        }
    }

    /**
     * 挑一刀：**优先切在“通栏块”的边界上**，其次取最宽的空白。
     *
     * 为什么不是简单地取最宽：`03-news-columns` 的正文栏里有 22px 的段间距，而正文栏与右栏
     * 之间只有 9px 的“图与正文”边界——如果取最宽，就会先从正文栏中间劈开，把右栏内容
     * 插到正文中间。通栏块（图片、表格、页脚）的上/下边界才是真正的“分节线”：
     * 切掉它之后，剩下的区域才会露出贯穿的栏间空白，下一轮就能正确分栏。
     */
    private fun chooseGap(regions: List<LayoutRegion>, byY: Boolean, minGap: Int): Span? {
        val gaps = emptySpans(regions, byY).filter { it.end - it.start >= minGap }
        if (gaps.isEmpty()) return null
        // 通栏判定：沿另一个轴的跨度是否覆盖本节点的 80% 以上。
        val crossMin = regions.minOf { if (byY) it.bounds.left else it.bounds.top }
        val crossMax = regions.maxOf { if (byY) it.bounds.right else it.bounds.bottom }
        val crossExtent = (crossMax - crossMin).toFloat().coerceAtLeast(1f)
        val fullThreshold = 0.8f * crossExtent

        fun isFullBlock(r: LayoutRegion): Boolean {
            val a = if (byY) r.bounds.left else r.bounds.top
            val b = if (byY) r.bounds.right else r.bounds.bottom
            return (b - a) >= fullThreshold
        }

        var best: Span? = null
        var bestScore = -1
        var bestWidth = -1
        for (g in gaps) {
            val touchesFullBlock = regions.any { r ->
                val start = if (byY) r.bounds.top else r.bounds.left
                val end = if (byY) r.bounds.bottom else r.bounds.right
                isFullBlock(r) && (end == g.start || start == g.end)
            }
            val score = if (touchesFullBlock) 1 else 0
            val width = g.end - g.start
            if (score > bestScore || (score == bestScore && width > bestWidth)) {
                best = g
                bestScore = score
                bestWidth = width
            }
        }
        return best
    }

    /**
     * 用扫描线求轴向上的所有极大空白区间（只含页面内部、两侧都有框的空白）。
     *
     * 事件按坐标成组结算：同一坐标上既有 top 结束又有 bottom 开始时，先整体加减再判断，
     * 因此“边界相接”（`bottom == next.top`）或“重叠 1px”都不会被当成空白。
     */
    private fun emptySpans(regions: List<LayoutRegion>, byY: Boolean): List<Span> {
        val edges = ArrayList<Edge>(regions.size * 2)
        for (region in regions) {
            val bounds = region.bounds
            val start = if (byY) bounds.top else bounds.left
            val end = if (byY) bounds.bottom else bounds.right
            if (end <= start) continue // 空框没有投影宽度，不参与断口判定。
            edges.add(Edge(start, +1))
            edges.add(Edge(end, -1))
        }
        if (edges.isEmpty()) return emptyList()
        edges.sortBy { it.at }

        val spans = ArrayList<Span>()
        var coverage = 0
        var gapStart = 0
        var gapOpen = false
        var i = 0
        while (i < edges.size) {
            val at = edges[i].at
            val before = coverage
            while (i < edges.size && edges[i].at == at) {
                coverage += edges[i].delta
                i++
            }
            if (before > 0 && coverage == 0) {
                gapStart = at
                gapOpen = true
            } else if (before == 0 && coverage > 0 && gapOpen) {
                spans.add(Span(gapStart, at))
                gapOpen = false
            }
        }
        return spans
    }

    /** 稳定排序：先 top 后 left；同坐标保持入参相对顺序。 */
    private fun topLeftSorted(regions: List<LayoutRegion>): List<LayoutRegion> =
        regions.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

    /** 轴向上的一段 `[start, end)`。 */
    private data class Span(val start: Int, val end: Int)

    /** 扫描线事件：`at` 处覆盖数变化 `delta`。 */
    private data class Edge(val at: Int, val delta: Int)

    private companion object {
        /** 没检出文字行时的行高兜底（S 像素），避免断口门槛退化为 0。 */
        const val FALLBACK_LINE_HEIGHT = 16f
    }
}
