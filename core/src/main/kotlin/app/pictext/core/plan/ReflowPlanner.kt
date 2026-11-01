package app.pictext.core.plan

import app.pictext.core.model.Block
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LayoutStrategy
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.model.TextLine
import kotlin.math.abs
import kotlin.math.ceil
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

    /**
     * v1（全页投影链路）的规划入口。
     *
     * **这里刻意不引用 v2**：v1/v2 的分流放在 `SmartReflow`（只有 CLI 用）。否则 App 的
     * release 包里会一直拖着 `ReflowPlannerV2` + `CutPolicy`——它们正是 R8 唯一剥不掉的
     * v2 逻辑。现在 release 里只剩 v2 的**数据类型**（v1 模型字段引用），没有任何 v2 代码路径，
     * 由 `scripts/verify-v1-only.ps1` 对 `classes.dex` 做门禁。
     */
    fun plan(layout: PageLayout, options: ReflowOptions): ReflowPlan {
        val geometry = PlanGeometry.of(options)
        val outW = geometry.outW
        val inset = geometry.inset
        val contentW = geometry.contentW

        val notes = layout.diagnostics.notes.toMutableList()
        // 防御：v1 规划器不认识 v2 版面（区域/阅读顺序在 `regions` 里，`blocks` 可能是空的）。
        // 正常调用不会走到这里（分流在 SmartReflow），真走到了也按“看原图”降级，不崩。
        if (layout.strategy != LayoutStrategy.PROJECTION) {
            return originalPlan(layout, outW, FallbackReason.LAYOUT_FALLBACK, notes)
        }
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
        //
        // v1.5：图注也切，但它**不参与定倍率**——图注通栏、半宽又常常比正文半宽更宽，
        // 让它进 `active` 会把整页 bodyScale 压低，正文替图注买单。
        val captionSplits = splitLines.filter { it.role == LineRole.CAPTION }
        val emScale = emScale(layout, options)
        var active = splitLines.filter { it.role != LineRole.CAPTION }
        var bodyScale = 0f
        for (i in 0 until 4) {
            // 基准倍率一律按“左右两半”算：×4 的 2/4 段是二次重排（见 secondPassBounds），
            // 不能用它把 ×2 的基准抬高。
            val slot = if (active.isEmpty()) pageWidth else slotFor(active, pageWidth)
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
        val splitLinesUsed = active + captionSplits
        if (bodyScale <= 0f) return originalPlan(layout, outW, FallbackReason.NO_TEXT, notes)
        // ×4：在算法算出的倍率上再乘一倍（上限跟着放宽），这就是“在 ×2 基础上再放大”。
        if (options.scaleMultiplier > 1.001f) {
            bodyScale = min(bodyScale * options.scaleMultiplier, options.maxScale * options.scaleMultiplier)
            notes.add("scale_x${format(options.scaleMultiplier)}")
            notes.add("forced_scale")
        }
        // 强制档位只放大、不缩小：海报/大字截图这类页面算出的倍率可能 < 1，
        // 那等于把页面缩小，而用户点 ×2/×4 想要的是更大。
        if (options.forceReflow && bodyScale < 1f) {
            bodyScale = 1f
            notes.add("forced_min_1x")
        }

        // gain 以“原图按屏宽显示”为基准，而不是源图像素。
        //
        // [P0] bodyScale 是**分析图像素**上的倍率，必须先乘 analysisScale 换回源图像素。
        // 旧写法 `bodyScale * srcWidth / outW` 少乘了这一项：分析图被按宽度定标后
        // （长图尤其明显，690×10305 曾只剩 107px 宽），gain 会虚高 1/analysisScale 倍，
        // 本该回退原图的页面被当成“有收益”，最后渲染成左边一条窄列。
        val gain = bodyScale * layout.analysisScale * layout.srcWidth / outW
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
    private fun slotFor(lines: List<TextLine>, pageWidth: Int): Int {
        var s = 0
        for (line in lines) {
            val cut = line.cutX
            if (cut <= line.band.left || cut >= line.band.right) continue
            s = max(s, cut - line.band.left)
            s = max(s, line.band.right - cut)
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
        // 图注的倍率天花板：有正文时压在 0.9×bodyScale 以内（图注必须比正文小一档）；
        // 整页没有正文时不压——那条规则的前提不存在，压了只会让纯图注页更小。
        val captionCeiling = if (layout.textLines().any { it.role == LineRole.BODY }) {
            0.9f * bodyScale
        } else {
            bodyScale
        }
        // [v1.2] ×4 = 把 ×2 的输出当一张新图重新排一次：每条 BODY/HEADING 行按新倍率
        // 决定切 2 段还是 4 段，段与段之间用分析期预存的候选字缝。
        // 用户关掉切分（splitEnabled=false）时尊重设置：只放大，不再切行。
        val secondPass = options.scaleMultiplier > 1.001f && options.splitEnabled
        var secondPassSplit = false
        if (secondPass) notes.add("second_pass")
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
                        // [v1.2] ×4 二次重排：按目标倍率切 2/4 段，段宽不足时不再把整行压小。
                        if (secondPass && (role == LineRole.BODY || role == LineRole.HEADING)) {
                            val pieces = secondPassBounds(line, bodyScale, colW, options.maxSegments)
                            if (pieces.size >= 2) {
                                secondPassSplit = true
                                var firstPiece = true
                                for ((px0, px1) in pieces) {
                                    val pieceW = max(1, px1 - px0)
                                    addText(
                                        RectI(px0, sy0, px1, sy1),
                                        pieceW,
                                        lineHeight,
                                        min(bodyScale, colW.toFloat() / pieceW),
                                        role,
                                        centered = line.centered,
                                        gapBefore = if (firstPiece) gapBefore else lineGap,
                                    )
                                    firstPiece = false
                                }
                                lineIndex++
                                previousText = line
                                continue
                            }
                        }
                        // 标题只在“不切就会比正文矮”时才切：比较的是输出字高，不是倍数。
                        // 新闻标题第二行虽然整行装不下 2.73×，压到 2.43× 后字高仍大于正文，
                        // 就不该为了倍数一致把它拆成两行（会剩下“広島”孤零零一行）。
                        val fitScale = colW.toFloat() / max(1, line.band.width)
                        val headingNeedsSplit = role != LineRole.HEADING ||
                            line.band.height * fitScale < bodyRefHeight * 0.95f
                        val isSplit = splitAllowed && headingNeedsSplit
                        if (isSplit && role == LineRole.HEADING) notes.add("heading_split")
                        if (isSplit) {
                            // 切开的行按 bodyScale 放大；图注切开后仍然只给 `captionCeiling`，
                            // 否则“图注比正文小一档”会因为“它被切了”而失效。
                            val scale = if (role == LineRole.CAPTION) captionCeiling else bodyScale
                            // 按行自己的墨迹范围裁剪两半：版心只是统计量，不是裁剪边界。
                            // 用版心裁会把比版心更宽/更靠左的行切掉一截（真实样例踩过）。
                            // ×4 的 2/4 段由上面的二次重排处理，这里只做 ×2 的左右两半。
                            val xs = listOf(line.cutX)
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
                            LineRole.CAPTION -> min(captionCeiling, colW.toFloat() / aw)
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
        // 二次重排真的切了段，模式就是 REFLOW：×2 里“整页不切”的行在 ×4 可能又被切开了。
        val finalMode = if (secondPassSplit) PlanMode.REFLOW else mode
        return ReflowPlan(
            outputWidth = outW,
            height = height,
            scale = bodyScale,
            gain = gain,
            mode = finalMode,
            fallback = fallback,
            items = items,
            backgroundColor = layout.backgroundColor,
            isDark = layout.isDark,
            notes = notes,
            bodyScale = bodyScale,
        )
    }

    /**
     * ×4 二次重排：这一行在 [scale] 下要切几段、每段在 A 坐标里的 x 范围。
     *
     * ×2 的一行在 ×4 里字号翻倍，所以每段宽度必须减半：先算需要的段数，再从
     * [TextLine.cuts]（这一行所有安全字缝）里为每个理想分界点挑最近的一刀。
     * 固定刀位会切出 `197/18/56/158` 这种碎片；挑最近的缝才能得到接近等分的段。
     *
     * @return 空列表表示不切（放得下，或者没有安全切点——此时只能整行放大并被压缩）。
     */
    private fun secondPassBounds(
        line: TextLine,
        scale: Float,
        contentW: Int,
        maxSegments: Int,
    ): List<Pair<Int, Int>> {
        val l = line.band.left
        val r = line.band.right
        val w = r - l
        if (w <= 0 || scale <= 0f) return emptyList()
        val needed = ceil(w.toFloat() * scale / contentW).toInt()
        if (needed <= 1) return emptyList()
        val candidates = line.cuts
        if (candidates.isEmpty()) return emptyList()
        var pieces = 2
        while (pieces < needed && pieces * 2 <= maxSegments) pieces *= 2
        while (pieces >= 2) {
            boundsFor(l, r, pieces, candidates)?.let { return it }
            pieces /= 2
        }
        return emptyList()
    }

    /** 在 [candidates] 里为 [pieces] 段挑分界点；挑不齐返回 null。 */
    private fun boundsFor(
        l: Int,
        r: Int,
        pieces: Int,
        candidates: List<Int>,
    ): List<Pair<Int, Int>>? {
        val w = r - l
        val xs = ArrayList<Int>(pieces - 1)
        for (i in 1 until pieces) {
            val ideal = l + (w.toLong() * i / pieces).toInt()
            var best = -1
            var bestDist = Int.MAX_VALUE
            for (c in candidates) {
                if (c <= l || c >= r) continue
                if (xs.isNotEmpty() && c <= xs.last()) continue
                val d = abs(c - ideal)
                if (d < bestDist) {
                    bestDist = d
                    best = c
                }
            }
            if (best <= 0) return null
            xs.add(best)
        }
        val bounds = ArrayList<Pair<Int, Int>>(pieces)
        var prev = l
        for (x in xs + r) {
            if (x <= prev) return null
            bounds.add(prev to x)
            prev = x
        }
        return bounds
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
