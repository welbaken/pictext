package app.pictext.core.plan

import app.pictext.core.layout.CutDecision
import app.pictext.core.layout.CutPolicy
import app.pictext.core.layout.LayoutSummaryStats
import app.pictext.core.model.CutKind
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LayoutStrategy
import app.pictext.core.model.LayoutSummary
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.model.RegionKind
import app.pictext.core.model.TextLine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2 规划器：**全页一个倍率 `s` + 任意行按需切 N 段**（docs/07 §4.8）。
 *
 * 与 v1 的四个根本差别：
 * 1. 倍率只有一个：TEXT / TITLE / CAPTION 全部乘 `s`，源图的层级（标题大、图注小）原样保留；
 *    不再有 `0.9 × bodyScale` 的图注，也不再有“标题装不下就单独压小”；
 * 2. `s` 只由 **BODY 行高中位数** 算一次，且只放大不缩小；
 * 3. 不再有 v1 的 `byUnsplit`（用最宽的不切行压全页倍率）：切不动就压缩那一行；
 * 4. 切分按 `CutPolicy` 的三级刀口：硬缝 → 软缝 → 强制（每行 ≤1 次，敏感行零强制）。
 *
 * 硬失败（强制切过多、敏感行压缩过多）不在这里改倍率，而是返回
 * `fallback = LAYOUT_FALLBACK`，由 [SmartReflow] 重跑 v1——v2 的倍率模型不能为了个别行妥协。
 */
internal class ReflowPlannerV2(private val cutPolicy: CutPolicy = CutPolicy()) {

    fun plan(
        layout: PageLayout,
        options: ReflowOptions,
        outW: Int,
        inset: Int,
        contentW: Int,
        notes: MutableList<String>,
        stats: LayoutSummaryStats? = null,
    ): ReflowPlan {
        val regions = layout.regions.sortedBy { it.order }
        val textual = regions.filter { it.isTextual && it.lines.isNotEmpty() }
        val textLines = textual.flatMap { it.lines }
        if (textLines.isEmpty()) {
            return originalPlan(layout, outW, FallbackReason.NO_TEXT, notes)
        }

        // ---- 一个倍率 s（§4.8.1）----
        val medH = max(6f, layout.bodyMedianLineHeight().takeIf { it > 0f } ?: layout.medianLineHeight)
        val emBody = 0.92f * medH
        var s = min(options.targetEm / emBody, options.maxScale)
        if (options.scaleMultiplier > 1.001f) {
            s = min(s * options.scaleMultiplier, options.maxScale * options.scaleMultiplier)
            notes.add("scale_x${format(options.scaleMultiplier)}")
            notes.add("forced_scale")
        }
        s = max(s, 1f)
        if (options.forceReflow && s < 1f) s = 1f

        // 与 v1 同一口径：v2 的 A 坐标就是 S 坐标（analysisScale 恒为 1），
        // 但仍乘上 analysisScale，避免以后 v2 改成分块取样时这里又悄悄虚高。
        val gain = s * layout.analysisScale * layout.srcWidth / outW
        if (!options.forceReflow && options.splitEnabled && gain < options.minGain) {
            notes.add("gain_too_small(${format(gain)})")
            return originalPlan(layout, outW, FallbackReason.GAIN_TOO_SMALL, notes)
        }

        // ---- 间距模型（§4.8.3）----
        val outEm = 0.92f * medH * s
        val sourceLineGap = max(0f, layout.medianPitch - layout.medianLineHeight)
        val lineGapLo = max(1, (0.15f * outEm).roundToInt())
        val lineGapHi = max(lineGapLo + 1, (0.55f * outEm).roundToInt())
        val lineGap = (sourceLineGap * s).roundToInt().coerceIn(lineGapLo, lineGapHi)
        val paraExtra = max((0.8f * lineGap).roundToInt(), (0.25f * outEm).roundToInt())
        val regionGap = max((1.2f * lineGap).roundToInt(), (options.regionGapRatio * outEm).roundToInt())
        val blockGap = max((1.6f * lineGap).roundToInt(), (0.45f * outEm).roundToInt())

        val items = ArrayList<RenderItem>(textLines.size + regions.size)
        var y = inset
        var lineIndex = 0
        var prevText: TextLine? = null
        var emitted = false

        // ---- 统计（审计要靠它们判“切得够不够干净”）----
        var splitLines = 0
        var forcedCutLines = 0
        var compressedLines = 0
        var sensitiveLines = 0
        var sensitiveCompressedLines = 0
        var maxSegments = 1

        fun emitText(
            region: LayoutRegion,
            regionIndex: Int,
            line: TextLine,
            decision: CutDecision,
            gapBefore: Int,
            scale: Float,
        ) {
            if (gapBefore > 0) y += gapBefore
            val lineHeight = max(1, line.band.height)
            for ((segIndex, seg) in decision.segments.withIndex()) {
                val segW = max(1, seg.width)
                val segScale = min(scale, contentW.toFloat() / segW)
                val destW = max(1, (segW * segScale).roundToInt())
                val destH = max(1, (lineHeight * segScale).roundToInt())
                val src = RectI(seg.x0, line.band.top, seg.x1, line.band.bottom)
                    .clampTo(layout.srcWidth, layout.srcHeight)
                if (src.isEmpty) continue
                items.add(
                    RenderItem.TextRow(
                        src = src,
                        dest = RectI(inset, y, inset + destW, y + destH),
                        lineIndex = lineIndex,
                        role = line.role,
                        scale = segScale,
                        regionIndex = regionIndex,
                        segmentIndex = segIndex,
                        segments = decision.count,
                        cut = seg.cut,
                    ),
                )
                y += destH
                emitted = true
            }
            lineIndex++
            prevText = line
        }

        for ((regionIndex, region) in regions.withIndex()) {
            when {
                region.isTextual && region.lines.isNotEmpty() -> {
                    val allowForced = region.kind == RegionKind.TEXT || region.kind == RegionKind.TITLE
                    for (line in region.lines) {
                        // 状态栏/按钮/头像这类 UI 行不参与统一倍率（docs/07 §4.3）：
                        // 它们可能出现在“文字区域”内部（RowClassifier 判出来的），仍要按 UI 封顶，
                        // 否则一行按钮会被放大到 2× 以上（`10-long-feed` 的审计就是这么炸的）。
                        val lineScale = if (line.role == LineRole.UI) minOf(s, options.uiMaxScale) else s
                        val gapBefore = when {
                            !emitted -> 0
                            prevText == null -> regionGap
                            line.band.top - prevText!!.band.bottom >
                                options.paragraphGapRatio * max(1f, layout.medianGap) ->
                                lineGap + paraExtra
                            else -> lineGap
                        }
                        val decision = if (!options.splitEnabled) {
                            CutDecision.single(line)
                        } else {
                            cutPolicy.decide(
                                line = line,
                                contentWidth = contentW,
                                scale = lineScale,
                                allowForced = allowForced && line.role != LineRole.UI,
                            )
                        }
                        if (line.sensitive && decision.compressed) sensitiveCompressedLines++
                        if (line.sensitive) sensitiveLines++
                        if (decision.isSplit) splitLines++
                        if (decision.forcedCuts > 0) forcedCutLines++
                        if (decision.compressed) compressedLines++
                        if (decision.count > maxSegments) maxSegments = decision.count
                        emitText(region, regionIndex, line, decision, gapBefore, lineScale)
                    }
                }

                region.kind == RegionKind.CHROME && region.lines.isNotEmpty() -> {
                    // CHROME 不参与统一倍率：`min(s, 1.0)`，按宽度压缩，绝不切。
                    for (line in region.lines) {
                        val scale = minOf(s, 1f, contentW.toFloat() / max(1, line.band.width))
                        val decision = CutDecision.single(line)
                        val gapBefore = if (!emitted) 0 else lineGap
                        if (gapBefore > 0) y += gapBefore
                        val destW = max(1, (line.band.width * scale).roundToInt())
                        val destH = max(1, (line.band.height * scale).roundToInt())
                        val src = line.band.clampTo(layout.srcWidth, layout.srcHeight)
                        if (!src.isEmpty) {
                            items.add(
                                RenderItem.TextRow(
                                    src = src,
                                    dest = RectI(inset, y, inset + destW, y + destH),
                                    lineIndex = lineIndex,
                                    role = LineRole.UI,
                                    scale = scale,
                                    regionIndex = regionIndex,
                                    segmentIndex = 0,
                                    segments = decision.count,
                                    cut = CutKind.NONE,
                                ),
                            )
                            y += destH
                            emitted = true
                        }
                        lineIndex++
                        prevText = line
                    }
                }

                else -> {
                    // 图 / 表 / 其它 / 无行的 CHROME：整块保留，按宽度适配。
                    val figW = max(1, region.bounds.width)
                    val figH = max(1, region.bounds.height)
                    var sf = when (region.kind) {
                        RegionKind.TABLE -> min(options.figMaxScale, contentW.toFloat() / figW)
                        RegionKind.FIGURE -> (contentW.toFloat() / figW).coerceIn(1f, options.figMaxScale)
                        RegionKind.CHROME -> minOf(1f, contentW.toFloat() / figW)
                        RegionKind.OTHER -> minOf(1f, contentW.toFloat() / figW)
                        else -> minOf(1f, contentW.toFloat() / figW)
                    }
                    if (figW * sf > contentW) sf = contentW.toFloat() / figW
                    val destW = max(1, (figW * sf).roundToInt())
                    val destH = max(1, (figH * sf).roundToInt())
                    val src = region.bounds.clampTo(layout.srcWidth, layout.srcHeight)
                    if (src.isEmpty) continue
                    if (emitted) y += blockGap
                    // 水平位置：**窄图必须保留左右关系**。聊天头像、点赞图标、气泡这些只有 3–40px 宽，
                    // 原来一律居中，等于把“谁说的”“图标属于哪一行”抹掉——v2 在这类页面上看着
                    // 比 v1 更差，根因就在这里（V2-46）。
                    // 宽图（正文插图/照片）仍居中：文档排版里居中才是对的。
                    val narrow = figW < options.narrowFigureRatio * layout.srcWidth
                    val dx = if (narrow) {
                        // 按比例映射后夹到列内：源图比输出更宽时，右边缘元素自然贴右。
                        (inset + src.left * sf).roundToInt()
                            .coerceIn(inset, inset + max(0, contentW - destW))
                    } else {
                        inset + (contentW - destW) / 2
                    }
                    items.add(
                        RenderItem.Figure(
                            src = src,
                            dest = RectI(dx, y, dx + destW, y + destH),
                            blockIndex = regionIndex,
                            kind = when (region.kind) {
                                RegionKind.TABLE, RegionKind.FIGURE -> FigureKind.PHOTO
                                RegionKind.CHROME -> FigureKind.UI
                                else -> FigureKind.DECOR
                            },
                            scale = sf,
                            regionIndex = regionIndex,
                        ),
                    )
                    y += destH
                    emitted = true
                    prevText = null
                }
            }
        }

        if (items.isEmpty()) return originalPlan(layout, outW, FallbackReason.NO_TEXT, notes)

        // ---- 硬门槛：切不干净就回退 v1，而不是拿全页倍率去迁就个别行 ----
        val forcedRatio = if (splitLines <= 0) 0f else forcedCutLines.toFloat() / splitLines
        val sensitiveCompressedRatio =
            if (sensitiveLines <= 0) 0f else sensitiveCompressedLines.toFloat() / sensitiveLines
        if (!options.forceReflow) {
            if (forcedRatio > options.forcedCutFailRatio) {
                notes.add("layout_forced_cut_high(${format(forcedRatio)})")
                return fallbackToV1(layout, outW, s, notes)
            }
            if (sensitiveCompressedRatio > options.sensitiveCompressedRatio) {
                notes.add("layout_sensitive_compressed(${format(sensitiveCompressedRatio)})")
                return fallbackToV1(layout, outW, s, notes)
            }
        }

        val height = y + inset
        if (height > MAX_PLAN_HEIGHT || height <= 0) {
            notes.add("too_tall($height)")
            return originalPlan(layout, outW, FallbackReason.TOO_TALL, notes)
        }
        if (forcedRatio > options.forcedCutPageRatio) {
            notes.add("forced_cut_high(${format(forcedRatio)})")
        }

        val summary = LayoutSummary(
            model = notes.firstOrNull { it.startsWith("layout_model=") }?.substringAfter("=").orEmpty(),
            tiles = stats?.tiles
                ?: notes.firstOrNull { it.startsWith("layout_tiles_") }
                    ?.removePrefix("layout_tiles_")?.toIntOrNull() ?: 0,
            regions = regions.size,
            coverage = stats?.coverage
                ?: notes.firstOrNull { it.startsWith("layout_coverage=") }
                    ?.substringAfter("=")?.toFloatOrNull() ?: 1f,
            residuals = stats?.residuals
                ?: regions.count { it.source == app.pictext.core.model.RegionSource.RESIDUAL },
            orderAmbiguous = stats?.orderAmbiguous ?: ("layout_order_ambiguous" in notes),
            verticalRatio = stats?.verticalRatio ?: 0f,
            forcedCutLines = forcedCutLines,
            splitLines = splitLines,
            compressedLines = compressedLines,
            sensitiveCompressedLines = sensitiveCompressedLines,
            maxSegments = maxSegments,
            regionGap = regionGap,
        )

        return ReflowPlan(
            outputWidth = outW,
            height = height,
            scale = s,
            gain = gain,
            mode = if (splitLines > 0) PlanMode.REFLOW else PlanMode.NO_SPLIT,
            fallback = FallbackReason.NONE,
            items = items,
            backgroundColor = layout.backgroundColor,
            isDark = layout.isDark,
            notes = notes,
            bodyScale = s,
            strategy = LayoutStrategy.DOC_LAYOUT,
            layout = summary,
        )
    }

    private fun fallbackToV1(
        layout: PageLayout,
        outW: Int,
        s: Float,
        notes: List<String>,
    ): ReflowPlan = ReflowPlan(
        outputWidth = outW,
        height = 0,
        scale = 1f,
        gain = 0f,
        mode = PlanMode.ORIGINAL,
        fallback = FallbackReason.LAYOUT_FALLBACK,
        items = emptyList(),
        backgroundColor = layout.backgroundColor,
        isDark = layout.isDark,
        notes = notes + "fallback_layout_fallback",
        bodyScale = 1f,
        strategy = LayoutStrategy.DOC_LAYOUT,
    )

    private fun originalPlan(
        layout: PageLayout,
        outW: Int,
        reason: FallbackReason,
        notes: List<String>,
    ): ReflowPlan = ReflowPlan(
        outputWidth = outW,
        height = 0,
        scale = 1f,
        gain = 0f,
        mode = PlanMode.ORIGINAL,
        fallback = reason,
        items = emptyList(),
        backgroundColor = layout.backgroundColor,
        isDark = layout.isDark,
        notes = notes + "fallback_${reason.name.lowercase()}",
        bodyScale = 1f,
        strategy = LayoutStrategy.DOC_LAYOUT,
    )

    private fun format(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    companion object {
        /** 计划高度硬上限，和 v1 一致（长图也够用）。 */
        const val MAX_PLAN_HEIGHT = 300_000
    }
}

/**
 * v2（模型版面）的规划入口，**只给 `SmartReflow` / CLI 用**。
 *
 * v1 的 [ReflowPlanner] 不引用本函数，也不引用 [ReflowPlannerV2]；App 侧
 * （release：R8 + 无 keep 规则）因此会把这一整条 v2 链路剥掉。
 * 门禁：`scripts/verify-v1-only.ps1` 扫描 `classes.dex` 里的 v2 专有字符串。
 */
internal fun planDocLayout(
    layout: PageLayout,
    options: ReflowOptions,
    stats: LayoutSummaryStats? = null,
): ReflowPlan {
    val geometry = PlanGeometry.of(options)
    return ReflowPlannerV2().plan(
        layout = layout,
        options = options,
        outW = geometry.outW,
        inset = geometry.inset,
        contentW = geometry.contentW,
        notes = layout.diagnostics.notes.toMutableList(),
        stats = stats,
    )
}


