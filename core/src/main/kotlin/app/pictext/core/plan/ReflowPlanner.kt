package app.pictext.core.plan

import app.pictext.core.model.Block
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.model.TextLine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Step 9 + 10：把版面分析结果变成“源矩形 → 目标矩形”的渲染计划。
 *
 * 只做裁剪、缩放、平移，不做任何重采样以外的修改。任何收益不足或异常都回退为原图。
 *
 * v1.1 的缩放模型（P0-4）：全页只有一个正文基准 [bodyScale]，其余角色按表换算，
 * 不再让图块走“宽度适配”、正文走“目标字号”这两套互不相干的算法：
 *
 * | 角色 | 缩放 | 切分 |
 * |---|---|---|
 * | BODY | `bodyScale`（装不下时按列宽压缩） | 满宽行 |
 * | HEADING | `min(bodyScale, 列宽/标题宽)`；装不下且 `bodyScale` 明显更大 → 按 BODY 切分 | 条件切分 |
 * | CAPTION | `min(0.9 × bodyScale, 列宽/行宽)` | 不切 |
 * | UI | `min(1.5, 列宽/行宽)` | 不切 |
 * | PHOTO | `clamp(列宽/图宽, 1.0, figMaxScale)` | 不切 |
 * | UI 图块 | `min(1.5, 列宽/图宽)` | 不切 |
 * | DECOR | `min(1.0, 列宽/图宽)` | 不切 |
 */
class ReflowPlanner {

    fun plan(layout: PageLayout, options: ReflowOptions): ReflowPlan {
        val outW = options.outputWidth.coerceAtLeast(1)
        val inset = max(10, (options.insetRatio * outW).roundToInt())
        val contentW = max(1, outW - 2 * inset)

        val notes = layout.diagnostics.notes.toMutableList()
        // 用户明确按了“×2/×4”时只保留“根本没字/没墨”这类硬回退，
        // 长行不足、收益不足、多栏这些前置判断一律不看（他要的是放大，不是建议）。
        val early = earlyFallback(layout, relaxed = options.forceReflow)
        if (early != null) {
            return originalPlan(layout, outW, early, notes)
        }

        val pageWidth = layout.textRight - layout.textLeft
        if (pageWidth <= 0) {
            return originalPlan(layout, outW, FallbackReason.NO_TEXT, notes)
        }

        val weak = "weak_cut" in layout.diagnostics.notes
        val splitLines = if (options.splitEnabled && !weak) {
            layout.textLines().filter { line ->
                if (options.forceTwo) {
                    line.cutX > 0 && !line.centered && line.band.width >= 0.20f * pageWidth
                } else {
                    line.split && line.cutX > 0
                }
            }
        } else {
            emptyList()
        }

        // 逐行切点会浮动几像素，按“最宽的那一半”缩放，避免某一行被二次压缩。
        // 只切“整行放不下”的行：放得下的行再切一次，只会把一句话拆成两行，没有收益。
        //
        // 统一缩放（P0-4）：整页只能有一个正文倍率，它还受**不参与切分的最宽正文行**约束。
        // 否则那一行会被压到列宽里，字号比同段小一截——审计会直接判 body_scale_inconsistent。
        val emScale = emScale(layout, options)
        var active = splitLines
        var bodyScale = 0f
        for (i in 0 until 4) {
            // slot 一律按“左右两半”算：bodyScale 先保证两半放得下，
            // 4 段只是 ×4 下宽行的兜底（见下面的 halfTooWide），不能用它把基准倍率抬高。
            val slot = if (active.isEmpty()) pageWidth else slotFor(active, pageWidth, 2)
            val bySlot = scaleFor(slot, contentW, emScale, options.maxScale)
            val widestUnsplit = layout.textLines()
                .filter { it.role == LineRole.BODY && it !in active }
                .maxOfOrNull { it.band.width } ?: 0
            val byUnsplit = if (widestUnsplit > 0) contentW.toFloat() / widestUnsplit else Float.MAX_VALUE
            bodyScale = min(bySlot, byUnsplit)
            val needed = active.filter { it.band.width * bodyScale > contentW + 0.5f }
            if (needed.size == active.size) break
            active = needed
        }
        val splitLinesUsed = active
        if (bodyScale <= 0f) return originalPlan(layout, outW, FallbackReason.NO_TEXT, notes)
        // ×4：在算法算出的倍率上再乘一倍（上限跟着放宽），这就是“在 ×2 基础上再放大”。
        if (options.scaleMultiplier > 1.001f) {
            bodyScale = min(bodyScale * options.scaleMultiplier, options.maxScale * options.scaleMultiplier)
            notes.add("scale_x${format(options.scaleMultiplier)}")
            notes.add("forced_scale")
        }

        // gain 以“原图按屏宽显示”为基准，而不是源图像素。
        val gain = bodyScale * layout.srcWidth / outW
        val mode = if (splitLinesUsed.isEmpty()) PlanMode.NO_SPLIT else PlanMode.REFLOW

        // P0-5 / D-B：切分失败且没有收益 → 直接看原图，不再输出 1.03× 的伪重排。
        // 用户手动关闭切分时尊重其选择（仍给整页放大视图），只有自动模式才用 gain 门槛回退。
        if (!options.forceReflow && options.splitEnabled && gain < options.minGain) {
            notes.add("gain_too_small(${format(gain)})")
            // 切分没成功（weak_cut）→ 告诉用户“没找到可放大的切分位置”；
            // 其他情况（例如窄图本身字就大）→ “文字已经足够大”。
            val reason = if (weak) FallbackReason.WEAK_CUT else FallbackReason.GAIN_TOO_SMALL
            return originalPlan(layout, outW, reason, notes)
        }

        return buildPlan(
            layout, options, outW, inset, contentW, bodyScale, gain,
            mode, if (weak && mode == PlanMode.NO_SPLIT && !options.forceReflow) FallbackReason.WEAK_CUT else FallbackReason.NONE,
            splitLinesUsed, notes,
        )
    }

    private fun emScale(layout: PageLayout, options: ReflowOptions): Float {
        val medH = max(6f, layout.medianLineHeight)
        return options.targetEm / (0.92f * medH)
    }

    /** 所有待切行的最大半行宽；没有待切行时按整版心宽（= 整页不切，只放大）。 */
    private fun slotFor(lines: List<TextLine>, pageWidth: Int, maxSegments: Int): Int {
        var s = 0
        for (line in lines) {
            val xs = if (maxSegments >= 4 && line.cuts.size >= 2) line.cuts else listOf(line.cutX)
            var prev = line.band.left
            for (x in xs) {
                if (x <= prev || x >= line.band.right) continue
                s = max(s, x - prev)
                prev = x
            }
            if (prev <= line.band.left || prev >= line.band.right) continue
            s = max(s, line.band.right - prev)
        }
        return if (s > 0) s else max(1, pageWidth)
    }

    private fun scaleFor(slot: Int, contentW: Int, emScale: Float, maxScale: Float): Float =
        min(contentW.toFloat() / max(1, slot), min(emScale, maxScale))

    private fun buildPlan(
        layout: PageLayout,
        options: ReflowOptions,
        outW: Int,
        inset: Int,
        contentW: Int,
        bodyScale: Float,
        gain: Float,
        mode: PlanMode,
        fallback: FallbackReason,
        splitLines: List<TextLine>,
        notes: MutableList<String>,
    ): ReflowPlan {
        val items = ArrayList<RenderItem>()
        // 列宽固定为整个内容宽：切分半行靠左排，短行不必再被压窄。
        // （旧实现按 slot×s 收窄列宽并居中，导致“放得下的行”也被压缩，同段字号忽大忽小。）
        val colW = contentW
        val left = inset
        val analysisW = max(1, (layout.srcWidth * layout.analysisScale).roundToInt())
        val analysisH = max(1, (layout.srcHeight * layout.analysisScale).roundToInt())

        // P1-3 间距模型：行距来自 pitch - 行高（真实行距，不含段间距），段间距在此基础上叠加。
        val outEm = 0.92f * max(6f, layout.medianLineHeight) * bodyScale
        val sourceLineGap = max(0f, layout.medianPitch - layout.medianLineHeight)
        val lineGapLo = max(1, (0.15f * outEm).roundToInt())
        val lineGapHi = max(lineGapLo + 1, (0.55f * outEm).roundToInt())
        val lineGap = (sourceLineGap * bodyScale).roundToInt().coerceIn(lineGapLo, lineGapHi)
        val paraExtra = max((0.8f * lineGap).roundToInt(), (0.25f * outEm).roundToInt())
        val blockGap = max((1.6f * lineGap).roundToInt(), (0.45f * outEm).roundToInt())

        val bodyRefHeight = max(6f, layout.medianLineHeight) * bodyScale
        var y = inset
        var emitted = false
        var previousText: TextLine? = null
        var lineIndex = 0

        fun addText(
            srcRect: RectI,
            lineWidth: Int,
            lineHeight: Int,
            scale: Float,
            role: LineRole,
            centered: Boolean,
            gapBefore: Int,
        ) {
            if (gapBefore > 0) y += gapBefore
            val destW = max(1, (lineWidth * scale).roundToInt())
            val destH = max(1, (lineHeight * scale).roundToInt())
            val dx = left + if (centered) (colW - destW) / 2 else 0
            val src = layout.toSource(srcRect)
            if (src.isEmpty) return
            items.add(
                RenderItem.TextRow(
                    src = src,
                    dest = RectI(dx, y, dx + destW, y + destH),
                    lineIndex = lineIndex,
                    role = role,
                    scale = scale,
                ),
            )
            y += destH
            emitted = true
        }

        for ((blockIndex, block) in layout.blocks.withIndex()) {
            when (block) {
                is Block.Paragraph -> {
                    for (line in block.lines) {
                        val sy0 = max(0, line.band.top - 1)
                        val sy1 = min(analysisH, line.band.bottom + 1)
                        val lineHeight = max(1, sy1 - sy0)

                        val gapBefore = when {
                            !emitted -> 0
                            previousText == null -> blockGap
                            line.band.top - previousText.band.bottom >
                                options.paragraphGapRatio * max(1f, layout.medianGap) ->
                                lineGap + paraExtra
                            else -> lineGap
                        }

                        val splitAllowed = mode == PlanMode.REFLOW && line in splitLines && line.cutX > 0
                        val role = line.role
                        // 标题只在“不切就会比正文矮”时才切：比较的是输出字高，不是倍数。
                        // 新闻标题第二行虽然整行装不下 2.73×，压到 2.43× 后字高仍大于正文，
                        // 就不该为了倍数一致把它拆成两行（会剩下“広島”孤零零一行）。
                        val fitScale = colW.toFloat() / max(1, line.band.width)
                        val headingNeedsSplit = role != LineRole.HEADING ||
                            line.band.height * fitScale < bodyRefHeight * 0.95f
                        val isSplit = splitAllowed && headingNeedsSplit
                        if (isSplit && role == LineRole.HEADING) notes.add("heading_split")
                        if (isSplit) {
                            val scale = bodyScale
                            // 按行自己的墨迹范围裁剪两半：版心只是统计量，不是裁剪边界。
                            // 用版心裁会把比版心更宽/更靠左的行切掉一截（真实样例踩过）。
                            // [v1.1] ×4 模式用 4 段（cuts 有 3 个切点）；否则就是左右两半。
                            // 半行仍然放不下（宽行 × 大倍率）时，才用 4 段；否则保持左右两半。
                            val halfW = (line.band.width / 2).coerceAtLeast(1)
                            val halfTooWide = halfW * scale > colW * 1.002f
                            val xs = if (options.maxSegments >= 4 && line.cuts.size >= 2 && halfTooWide) {
                                line.cuts
                            } else {
                                listOf(line.cutX)
                            }
                            val bounds = ArrayList<Int>(xs.size + 2)
                            bounds.add(line.band.left)
                            for (x in xs) if (x > bounds.last() && x < line.band.right) bounds.add(x)
                            bounds.add(line.band.right)
                            val halves = Array(bounds.size - 1) { bounds[it] to bounds[it + 1] }
                            var firstHalf = true
                            for ((hx0, hx1) in halves) {
                                if (hx1 <= hx0) continue
                                addText(
                                    RectI(hx0, sy0, hx1, sy1),
                                    hx1 - hx0,
                                    lineHeight,
                                    min(scale, colW.toFloat() / max(1, hx1 - hx0)),
                                    role,
                                    centered = false,
                                    gapBefore = if (firstHalf) gapBefore else lineGap,
                                )
                                firstHalf = false
                            }
                            lineIndex++
                            previousText = line
                            continue
                        }

                        val padX = 2
                        val ax0 = max(0, line.band.left - padX)
                        val ax1 = min(analysisW, line.band.right + padX)
                        val aw = max(1, ax1 - ax0)
                        val targetScale = when (role) {
                            LineRole.BODY -> bodyScale
                            LineRole.HEADING -> min(bodyScale, colW.toFloat() / aw)
                            LineRole.CAPTION -> min(0.9f * bodyScale, colW.toFloat() / aw)
                            LineRole.UI -> min(options.uiMaxScale, colW.toFloat() / aw)
                        }
                        addText(
                            RectI(ax0, sy0, ax1, sy1),
                            aw,
                            lineHeight,
                            min(targetScale, colW.toFloat() / aw),
                            role,
                            centered = line.centered,
                            gapBefore = gapBefore,
                        )
                        lineIndex++
                        previousText = line
                    }
                }

                is Block.Figure -> {
                    val figW = max(1, block.bounds.width)
                    val figH = max(1, block.bounds.height)
                    var sf = when (block.kind) {
                        FigureKind.PHOTO -> (contentW.toFloat() / figW).coerceIn(1f, options.figMaxScale)
                        FigureKind.UI -> min(options.uiMaxScale, contentW.toFloat() / figW)
                        FigureKind.DECOR -> min(1f, contentW.toFloat() / figW)
                    }
                    if (figW > contentW) sf = contentW.toFloat() / figW
                    val destW = max(1, (figW * sf).roundToInt())
                    val destH = max(1, (figH * sf).roundToInt())
                    val src = layout.toSource(block.bounds)
                    if (src.isEmpty) continue
                    if (emitted) y += blockGap
                    val dx = (outW - destW) / 2
                    items.add(
                        RenderItem.Figure(
                            src = src,
                            dest = RectI(dx, y, dx + destW, y + destH),
                            blockIndex = blockIndex,
                            kind = block.kind,
                            scale = sf,
                        ),
                    )
                    y += destH
                    emitted = true
                    previousText = null
                }
            }
        }

        val height = if (items.isEmpty()) 0 else y + inset
        if (height > 300_000 || (items.isNotEmpty() && height <= 0)) {
            notes.add("too_tall($height)")
            return originalPlan(layout, outW, FallbackReason.TOO_TALL, notes)
        }
        if (items.isEmpty()) {
            return originalPlan(layout, outW, FallbackReason.NO_TEXT, notes)
        }
        return ReflowPlan(
            outputWidth = outW,
            height = height,
            scale = bodyScale,
            gain = gain,
            mode = mode,
            fallback = fallback,
            items = items,
            backgroundColor = layout.backgroundColor,
            isDark = layout.isDark,
            notes = notes,
            bodyScale = bodyScale,
        )
    }

    private fun earlyFallback(layout: PageLayout, relaxed: Boolean = false): FallbackReason? {
        val notes = layout.diagnostics.notes
        return when {
            "no_ink" in notes -> FallbackReason.NO_INK
            "no_text" in notes -> FallbackReason.NO_TEXT
            // relaxed = 用户点了 ×2/×4：长行不足和多栏都不再拦。
            !relaxed && "too_few_long_lines" in notes -> FallbackReason.TOO_FEW_LONG_LINES
            !relaxed && "multi_column" in notes -> FallbackReason.MULTI_COLUMN
            else -> null
        }
    }

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
    )

    private fun format(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
}
