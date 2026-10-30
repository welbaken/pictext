package app.pictext.core.plan

import app.pictext.core.model.CutKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import kotlin.math.abs

/**
 * v2 计划审计（docs/07 V2-31）：把“版式重排到底做对没有”变成可断言的数字。
 *
 * 与 v1 的 [PlanAuditor] 分开，因为 v2 的硬指标不同：
 *
 * | 检查 | 条件 |
 * |---|---|
 * | 区域覆盖 | 每个有行/有像素的区域 ≥1 个 item |
 * | 顺序 | item 的 `regionIndex` 单调不减 |
 * | 越界 | `src` 在页内、`dest` 在 `[0,outW]` |
 * | 字号 | TEXT/TITLE/CAPTION 相对 `s` ≤2%；UI ≤1.5；CHROME ≤1.0 |
 * | 段数 | ≤ 软上限（硬上限 8）；压缩行 ≤10% |
 * | 强制切 | 每行 ≤1；全页 ≤10%（>15% 失败）；敏感行 =0 |
 * | 敏感压缩 | ≤5% |
 * | 重叠 | `dest` 垂直重叠 ≤2px |
 * | 覆盖率 | ≥99.5% |
 *
 * 任何一条不过 → 整页回退 v1（`layout_audit_failed`）。审计失败不是“调参信号”，是回退信号。
 */
data class LayoutAuditReport(
    val issues: List<String>,
    val mode: PlanMode,
    val regionCount: Int,
    val regionsWithoutItem: Int,
    val orderViolations: Int,
    val itemsOutOfBounds: Int,
    val maxScaleDeviation: Float,
    val uiMaxScale: Float,
    val chromeMaxScale: Float,
    val maxSegments: Int,
    val forcedCutLines: Int,
    val forcedCutRatio: Float,
    val sensitiveForcedCuts: Int,
    val sensitiveCompressedRatio: Float,
    val compressedRatio: Float,
    val maxVerticalOverlap: Int,
    val coverage: Float,
) {
    val ok: Boolean get() = issues.isEmpty()

    fun summary(): String = buildString {
        append("mode=$mode regions=$regionCount dropped=$regionsWithoutItem ")
        append("orderViolations=$orderViolations outOfBounds=$itemsOutOfBounds ")
        append("scaleDev=${fmt(maxScaleDeviation)} uiMax=${fmt(uiMaxScale)} chromeMax=${fmt(chromeMaxScale)} ")
        append("maxSeg=$maxSegments forced=${fmt(forcedCutRatio)} sensitiveForced=$sensitiveForcedCuts ")
        append("sensComp=${fmt(sensitiveCompressedRatio)} comp=${fmt(compressedRatio)} ")
        append("overlap=${maxVerticalOverlap}px coverage=${fmt(coverage)}")
        if (issues.isNotEmpty()) append(" ISSUES=$issues")
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}

object LayoutAuditor {

    /** TEXT/TITLE/CAPTION 相对 `s` 的允许偏差。 */
    const val SCALE_TOLERANCE = 0.02f

    /** UI 行/图上限（D-V2-9）。 */
    const val UI_CEILING = 1.5f

    /** CHROME 上限（D-V2-9）。 */
    const val CHROME_CEILING = 1.0f

    /** 压缩行占比上限。 */
    const val COMPRESSED_MAX_RATIO = 0.10f

    /**
     * 取整容差：`destW = round(srcW × scale)` 会让实测倍率比目标高/低约 `0.5/srcW`，
     * 一条 22px 高的按钮行在 1.5× 上会量到 1.502。硬门槛加 1% 容差，避免“算得对却判 1.502”的假失败。
     */
    const val ROUNDING_TOLERANCE = 0.01f

    /**
     * 比例门槛的最小样本量。
     *
     * “敏感行压缩 ≤5%” 这类比例门槛在样本很小时会失真：整页只有 1 行敏感行时，
     * 压掉它就是 100%，按字面执行会让整页回退 v1。样本 ≥3 才用比例判，
     * 小样本仍然受“敏感行零强制切”这条绝对门槛约束。
     */
    const val MIN_RATIO_SAMPLE = 3

    /**
     * @param sensitiveLines 敏感行总数（由调用方从 layout 统计：`line.sensitive`）；
     *   传 0 时跳过敏感压缩比检查。
     */
    fun audit(
        layout: PageLayout,
        plan: ReflowPlan,
        options: ReflowOptions,
        sensitiveLines: Int = layout.regionLines().count { it.sensitive },
        totalLines: Int = layout.regions.filter { it.isTextual }.sumOf { it.lines.size },
    ): LayoutAuditReport {
        val issues = ArrayList<String>()
        val regions = layout.regions.sortedBy { it.order }
        val textItems = plan.items.filterIsInstance<RenderItem.TextRow>()
        val figs = plan.items.filterIsInstance<RenderItem.Figure>()

        if (plan.mode == PlanMode.ORIGINAL) {
            return LayoutAuditReport(
                issues, plan.mode, regions.size, 0, 0, 0, 0f, 0f, 0f, 1, 0, 0f, 0, 0f, 0f, 0, 0f,
            )
        }

        // 1) 每个区域至少一个 item。
        val covered = BooleanArray(regions.size)
        for (item in plan.items) {
            val idx = when (item) {
                is RenderItem.TextRow -> item.regionIndex
                is RenderItem.Figure -> item.regionIndex
            }
            if (idx in covered.indices) covered[idx] = true
        }
        var without = 0
        for (i in covered.indices) {
            if (covered[i]) continue
            without++
            issues.add("region_dropped(#$i ${regions[i].kind})")
        }

        // 2) 顺序：regionIndex 单调不减。
        var orderViolations = 0
        var prevRegion = -1
        for (item in plan.items) {
            val idx = when (item) {
                is RenderItem.TextRow -> item.regionIndex
                is RenderItem.Figure -> item.regionIndex
            }
            if (idx < prevRegion) orderViolations++
            prevRegion = maxOf(prevRegion, idx)
        }
        if (orderViolations > 0) issues.add("region_order_violations($orderViolations)")

        // 3) 越界 + 9) 重叠。
        var outOfBounds = 0
        var maxOverlap = 0
        var prevBottom = Int.MIN_VALUE
        for (item in plan.items) {
            val d = item.dest
            if (d.left < 0 || d.top < 0 || d.right > plan.outputWidth || d.bottom > plan.height) {
                outOfBounds++
            }
            val s = item.src
            if (s.left < 0 || s.top < 0 || s.right > layout.srcWidth || s.bottom > layout.srcHeight) {
                outOfBounds++
            }
            if (prevBottom != Int.MIN_VALUE) {
                val overlap = prevBottom - d.top
                if (overlap > maxOverlap) maxOverlap = overlap
            }
            prevBottom = d.bottom
        }
        if (outOfBounds > 0) issues.add("items_out_of_bounds($outOfBounds)")
        if (maxOverlap > 2) issues.add("items_vertical_overlap(${maxOverlap}px)")

        // 4) 字号：TEXT/TITLE/CAPTION 用同一个 s；UI ≤1.5；CHROME ≤1.0。
        //
        // “压缩行”是**有意**的例外：一行怎么切都放不下时，规划器保住全页 `s`、压小这一行，
        // 并计入 `compressedLines`（下面有 10% 的独立门槛）。所以它不参与 2% 偏差检查，
        // 否则“压缩行 ≤10%”这条规则永远和“偏差 ≤2%”互斥。
        val s = plan.bodyScale
        // 只有 TEXT/TITLE/CAPTION 参与统一倍率；CHROME/UI 行按设计就是 `min(s, 1.0)`，
        // 把它们算进“压缩行”会让每页都超 10%（页眉页脚永远是 1.0）。
        val scaledItems = textItems.filter { it.role != LineRole.UI }
        val compressedLineIdx = scaledItems
            .filter { it.segments == 1 && s > 0f && it.src.width > 0 &&
                it.dest.width.toFloat() / it.src.width < s * 0.98f }
            .map { it.lineIndex }
            .toSet()

        var maxDev = 0f
        var uiMax = 0f
        var chromeMax = 0f
        for (item in textItems) {
            if (item.src.width <= 0) continue
            val scale = item.dest.width.toFloat() / item.src.width
            // 取整量子：destW 是整数，`±0.5px` 的量化在窄段上会放大成 1%–4% 的“假偏差”。
            // 容差取“2% 与一个像素的量化误差”的较大者——这是测量精度，不是放宽产品要求。
            val quantum = 0.6f / item.src.width
            when (item.role) {
                LineRole.BODY, LineRole.HEADING, LineRole.CAPTION -> {
                    if (item.lineIndex in compressedLineIdx) continue
                    val dev = if (s > 0f) abs(scale - s) / s else 0f
                    if (dev > maxDev && dev > quantum) maxDev = dev
                }
                LineRole.UI -> if (scale > uiMax && scale > UI_CEILING + quantum) uiMax = scale
            }
        }
        for (f in figs) {
            if (f.kind == app.pictext.core.model.FigureKind.UI && f.scale > chromeMax) chromeMax = f.scale
        }
        // 用户强制 ×2/×4 时个别行会被压回来，这是预期行为（`forced_scale` 已记录）。
        val forced = "forced_scale" in plan.notes
        if (maxDev > SCALE_TOLERANCE && !forced) {
            issues.add("scale_inconsistent(${fmt(maxDev)})")
        }
        if (uiMax > UI_CEILING + ROUNDING_TOLERANCE) issues.add("ui_row_too_large(${fmt(uiMax)})")
        if (chromeMax > CHROME_CEILING) issues.add("chrome_too_large(${fmt(chromeMax)})")

        // 5) 段数。
        val maxSegments = textItems.maxOfOrNull { it.segments } ?: 1
        val soft = textItems.filter { it.segments > options.maxSegmentsPerLine }
            .map { it.lineIndex }
            .distinct()
            .size
        if (maxSegments > options.hardMaxSegments) issues.add("segments_over_hard_max($maxSegments)")
        if (soft > 0) issues.add("segments_over_soft_max($soft)")

        // 6) 强制切。
        val forcedByLine = textItems.filter { it.cut == CutKind.FORCED }.groupBy { it.lineIndex }
        val forcedLines = forcedByLine.size
        val perLineMax = forcedByLine.values.maxOfOrNull { v -> v.count { it.cut == CutKind.FORCED } } ?: 0
        if (perLineMax > options.forcedCutPerLine) issues.add("forced_cut_per_line($perLineMax)")
        val splitLineCount = textItems.filter { it.segments > 1 }.map { it.lineIndex }.distinct().size
        val forcedRatio = if (splitLineCount <= 0) 0f else forcedLines.toFloat() / splitLineCount
        if (forcedRatio > options.forcedCutFailRatio) {
            issues.add("forced_cut_ratio(${fmt(forcedRatio)})")
        }

        // 7) 敏感行零强制 + 敏感压缩比。
        var sensitiveForced = 0
        var sensitiveCompressed = 0
        val sensitiveLineIdx = layout.regionLines().withIndex()
            .filter { it.value.sensitive }
            .map { it.index }
            .toSet()
        for (item in textItems) {
            if (item.lineIndex !in sensitiveLineIdx) continue
            if (item.cut == CutKind.FORCED) sensitiveForced++
        }
        // 压缩行：段数 1 且实际缩放明显小于 s（说明被列宽压过）。与第 4 步共用同一份判定。
        val compressedLines = compressedLineIdx.toList()
        for (idx in compressedLines) if (idx in sensitiveLineIdx) sensitiveCompressed++
        if (sensitiveForced > 0) issues.add("sensitive_forced_cut($sensitiveForced)")
        val sensitiveCompressedRatio = if (sensitiveLines <= 0) 0f else
            sensitiveCompressed.toFloat() / sensitiveLines
        // 小样本保护：整页只有 1–2 行敏感行时，压掉 1 行 = 50%–100%，
        // 按比例判会直接废掉整页。所以比例门槛只在样本 ≥3 行时生效，
        // 1–2 行的情况只看“有没有强制切”（sensitiveForced 已经是硬门槛）。
        if (sensitiveLines >= MIN_RATIO_SAMPLE && sensitiveCompressedRatio > options.sensitiveCompressedRatio) {
            issues.add("sensitive_compressed(${fmt(sensitiveCompressedRatio)})")
        }
        val compressedRatio = if (totalLines <= 0) 0f else compressedLines.size.toFloat() / totalLines
        if (totalLines >= MIN_RATIO_SAMPLE && compressedRatio > COMPRESSED_MAX_RATIO) {
            issues.add("compressed_ratio(${fmt(compressedRatio)})")
        }

        // 8) 覆盖率。
        val coverage = plan.layout?.coverage ?: 1f
        if (coverage < 0.995f) issues.add("coverage_low(${fmt(coverage)})")

        return LayoutAuditReport(
            issues = issues,
            mode = plan.mode,
            regionCount = regions.size,
            regionsWithoutItem = without,
            orderViolations = orderViolations,
            itemsOutOfBounds = outOfBounds,
            maxScaleDeviation = maxDev,
            uiMaxScale = uiMax,
            chromeMaxScale = chromeMax,
            maxSegments = maxSegments,
            forcedCutLines = forcedLines,
            forcedCutRatio = forcedRatio,
            sensitiveForcedCuts = sensitiveForced,
            sensitiveCompressedRatio = sensitiveCompressedRatio,
            compressedRatio = compressedRatio,
            maxVerticalOverlap = maxOf(0, maxOverlap),
            coverage = coverage,
        )
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
