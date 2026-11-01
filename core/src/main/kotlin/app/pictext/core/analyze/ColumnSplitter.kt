package app.pictext.core.analyze

import app.pictext.core.model.Block
import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import app.pictext.core.util.Stats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SplitOutcome(
    val blocks: List<Block>,
    val splitX: Int,
    val cleanRatio: Float,
    val splitCount: Int,
    val cleanCount: Int,
    val weak: Boolean,
)

/** 一组“同缩进”的行共用的一根切分列（P2 之外的 C2 改进）。 */
private data class GroupColumn(val left: Int, val right: Int, val column: Int)

/**
 * Step 8：逐行找字缝，再取中位数作为全局切点。
 *
 * 不能切几何中点：一行 46 个汉字的中点大概率落在某个字的笔画中间。中文正文按字格
 * 对齐，每一行内部总有字缝；逐行找缝再取中位数，比全局垂直投影稳定得多。
 *
 * v1.1 的三道门：
 *
 * 1. **角色门**（P0-3）：只有 BODY / HEADING 行允许切；UI 行（头像、按钮、时间戳）不切。
 * 2. **版心门**（P0-3）：行必须左对齐到版心，且宽度 ≥ 0.5 版心（标题要 ≥0.72 且顶到右版心）。
 *    引用行、按钮行、居中标题因此不进入切分。
 * 3. **词门**（P1-1）：切点两侧若是“窄墨簇”（拉丁字母），说明切点落在单词内部，拒绝这一刀。
 *    没开拉丁实验时，拉丁/代码行直接拒绝切分；拒绝的行计入 `cleanRatio` 分母，
 *    比例跌破 `cutCleanRatio` 就整页回退——这正是 XERJ 这类英文/代码页该走的路。
 *
 * 拒绝切分不是失败：整页退回“只放大不切”，再由规划器的收益门槛决定是否直接看原图。
 */
class ColumnSplitter(private val options: AnalysisOptions = AnalysisOptions()) {

    fun split(
        mask: BooleanArray,
        width: Int,
        height: Int,
        blocks: List<Block>,
        metrics: PageMetrics,
        deferWeak: Boolean = false,
    ): SplitOutcome {
        val pageWidth = metrics.pageWidth
        if (pageWidth <= 0 || blocks.isEmpty()) {
            return SplitOutcome(blocks, metrics.textLeft, 0f, 0, 0, true)
        }
        val longLineMinWidth = metrics.longLineThreshold
        val medH = max(4f, metrics.medianLineHeight)
        val textLines = blocks.flatMap { b ->
            when (b) {
                is Block.Paragraph -> b.lines
                is Block.Figure -> emptyList()
            }
        }.sortedBy { it.band.top }

        // 每行只扫一遍像素，找缝/判词/宽度统计共用同一份列墨剖面。
        val profiles = HashMap<TextLine, ColumnInk>(textLines.size)
        fun profile(line: TextLine): ColumnInk =
            profiles.getOrPut(line) { ColumnInk.of(mask, width, line.band) }

        fun latinLike(line: TextLine): Boolean {
            val p = profile(line)
            return p.medianClusterWidth() < options.latinLikeClusterRatio * medH &&
                p.clustersPerEm(medH) > options.latinLikeClusterDensity
        }

        /** 宽空白豁免门槛：拉丁行可豁免词间空格；中文行返回 0，不豁免（否则会劈开汉字偏旁）。 */
        fun exemptFor(line: TextLine): Int {
            if (!latinLike(line)) return 0
            return max(3, (1.5f * profile(line).blankRunMedian).toInt())
        }

        val candidates = textLines.filter { it.role != LineRole.UI }
        val longLines = candidates.filter {
            it.band.width >= longLineMinWidth && !latinLike(it)
        }
        if (longLines.size < 2) {
            return SplitOutcome(blocks, (metrics.textLeft + metrics.textRight) / 2, 0f, 0, 0, true)
        }

        val win0 = max(8, (options.cutWin0 * width).toInt())

        // [C2] 按段落左边距分组：同缩进的段落共用一根列，组间各自一根。
        // 缩进引用/列表项因此也能切，而不是被压进列宽（audit 的 body_scale_inconsistent）。
        // 只有 1 行的“孤立缩进行”拿不到自己的列，仍旧走页面列——避免把聊天气泡、
        // 右移的单行标签这类东西单独劈开。
        val tolerance = max(3, (options.splitGroupTolerance * pageWidth).toInt())
        val groups = clusterByLeft(candidates, tolerance)
        val groupOf = HashMap<TextLine, GroupColumn>(candidates.size)
        for (cluster in groups) {
            val members = cluster.filter {
                !latinLike(it) && it.band.width >= options.splitLeftAlignedRatio * pageWidth
            }
            if (members.size < 2) continue
            val groupLeft = Stats.median(members.map { it.band.left }).toInt()
            val groupRight = members.maxOf { it.band.right }
            val groupCenter = (groupLeft + groupRight) / 2
            val preferred = IntArray(members.size) { i ->
                chooseCut(profile(members[i]), groupCenter, win0, medH, exemptFor(members[i]))?.first
                    ?: approxCut(profile(members[i]), groupCenter, win0)
            }
            val gc = GroupColumn(groupLeft, groupRight, Stats.median(preferred).toInt())
            for (line in cluster) groupOf[line] = gc
        }

        // 页面列 = 覆盖行数最多的那一组的列；没有任何分组时退回几何中心。
        val pageColumn = groupOf.values.groupingBy { it.column }.eachCount()
            .maxByOrNull { it.value }?.key
            ?: (metrics.textLeft + metrics.textRight) / 2
        val pageGroup = GroupColumn(metrics.textLeft, metrics.textRight, pageColumn)

        val win1 = max(5, (options.cutWin1 * width).toInt())
        val inset = options.splitInsetRatio * pageWidth
        var splitCount = 0
        var clean = 0
        val updated = HashMap<TextLine, TextLine>(textLines.size)
        for (line in textLines) {
            val bw = line.band.width
            val centered = abs((line.band.left - metrics.textLeft) - (metrics.textRight - line.band.right)) <
                0.06f * pageWidth && (line.band.left - metrics.textLeft) > 0.04f * pageWidth

            // 角色门：只有 UI 行不许切（头像、按钮、图标行）。BODY / HEADING / CAPTION 都可以切，
            // 图注（v1.5）也切：通栏图注不切就只能被列宽压着放大（≈原图尺寸），切了才有字号收益；
            // 它放大后仍由规划器压在 `0.9 × bodyScale` 以内，不会跑到正文之上。
            val eligibleRole = line.role != LineRole.UI
            // 这一行所属分组：有分组用分组自己的版心与列，孤立行用页面版心与页面列。
            val group = groupOf[line] ?: pageGroup
            val leftAligned = line.band.left <= group.left + inset
            val nearRight = line.band.right >= group.right - inset
            // 文档规则：左右都贴版心且够宽。
            val fullWidth = leftAligned && nearRight && bw >= options.splitFullWidthRatio * pageWidth
            // 左对齐 + 至少半版心也放行。标题同样适用：标题往往比正文短一截，不放行的话
            // 它只能被压进列宽，字号反而小于正文（audit 会报 heading_smaller_than_body）。
            // 真正决定“要不要切”的是规划器：标题装得下 bodyScale 时不会切。
            val longLeftAligned = leftAligned && bw >= options.splitLeftAlignedRatio * pageWidth
            val eligible = eligibleRole && !centered && (fullWidth || longLeftAligned)

            // 切点必须落在这一行里，否则它只是“短于切点的行”，按普通行缩放即可，
            // 不能算进 cleanRatio 的分母（否则每页都会被短行拉低到 weak）。
            val reachable = eligible && group.column > line.band.left && group.column < line.band.right &&
                bw >= 0.25f * pageWidth
            var cutX = -1
            var inkAt = Float.MAX_VALUE
            if (reachable) {
                // 没开拉丁实验时，拉丁/代码行一律不切：找不到“整行零墨且够宽”的缝，
                // 硬切就会劈开单词（XERJ 的 El|asticsearch）。
                if (options.latinSplitEnabled || !latinLike(line)) {
                    // 先在中位切点附近找；找不到就把窗口放大一倍再找一次。
                    // 放宽窗口是安全的：真正保护“不劈字/不劈词”的是 ColumnInk.splitsWord。
                    val exempt = exemptFor(line)
                    val g = chooseCut(profile(line), group.column, win1, medH, exempt, balanceFirst = true)
                        ?: chooseCut(profile(line), group.column, win1 * 2 + 4, medH, exempt, balanceFirst = true)
                    if (g != null) {
                        cutX = g.first
                        inkAt = g.second
                    }
                }
            }
            val isSplit = reachable && cutX > 0
            if (reachable) {
                splitCount++
                if (isSplit && inkAt <= 1.5f) clean++
            }
            // [v1.2] 二次重排的候选切点：这一行所有安全的字缝，供 ×4 按需要的段数挑选。
            // 它与 ×2 的切分门无关（引用行、缩进行在 ×4 里同样需要能继续对半），
            // 但角色和拉丁保护照旧：不切 UI/图注，不切可能劈词的拉丁行。
            val cuts = if (eligibleRole && (options.latinSplitEnabled || !latinLike(line))) {
                safeGaps(profile(line), medH, exemptFor(line))
            } else {
                emptyList()
            }
            updated[line] = line.copy(cutX = cutX, split = isSplit, centered = centered, cuts = cuts)
        }

        val ratio = if (splitCount == 0) 0f else clean.toFloat() / splitCount
        val weak = splitCount == 0 || ratio < options.cutCleanRatio
        // 条带分析时带内先不否决：一条只有两行的带判 weak 会白白丢掉切分，
        // 否决权要留给合并后的整页（见 StripMerger）。
        if (weak && !deferWeak) {
            // 整页退回“只放大不切”：保留 cutX 供调试，但所有 split 置 false。
            for ((k, v) in updated) updated[k] = v.copy(split = false, cuts = emptyList())
        }
        val newBlocks = blocks.map { block ->
            when (block) {
                is Block.Paragraph -> Block.Paragraph(block.lines.map { updated[it] ?: it })
                is Block.Figure -> block
            }
        }
        return SplitOutcome(newBlocks, pageColumn, ratio, splitCount, clean, weak)
    }

    /**
     * 按左边距把候选行聚成“同缩进”组：以组内第一行的 left 为锚，超过容差就另起一组。
     *
     * 不用四舍五入分桶：缩进量常常刚好落在桶边界上，会被劈成两组。
     */
    private fun clusterByLeft(lines: List<TextLine>, tolerance: Int): List<List<TextLine>> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.band.left }
        val out = ArrayList<MutableList<TextLine>>()
        for (line in sorted) {
            val last = out.lastOrNull()
            if (last != null && abs(line.band.left - last.first().band.left) <= tolerance) {
                last.add(line)
            } else {
                out.add(mutableListOf(line))
            }
        }
        return out
    }

    /**
     * 这一行所有能安全下刀的字缝（升序，列坐标）。
     *
     * 二次重排（×4）不是“按固定 3 刀切 4 段”，而是**按需要的段数挑最近的缝**：
     * 每条行的字缝疏密不同，固定刀位会切出 197/18/56/158 这种碎片。
     *
     * 与 [ColumnInk.splitsWord] 同一套保护：可能劈开西文单词的缝不进入候选。
     */
    private fun safeGaps(p: ColumnInk, medianHeight: Float, gapExemptPx: Int): List<Int> {
        if (p.width <= 0) return emptyList()
        val thr = p.lowInkThreshold
        val right = p.left + p.width
        val out = ArrayList<Int>(8)
        var x = 0
        while (x < p.width) {
            if (p.counts[x] > thr) {
                x++
                continue
            }
            val start = x
            var zeroOnly = true
            while (x < p.width && p.counts[x] <= thr) {
                if (p.counts[x] > 0) zeroOnly = false
                x++
            }
            // 单个零墨列多半是抗锯齿噪点；低墨列组（JPEG 截图里的字缝）1 列也认。
            if (zeroOnly && x - start < 2) continue
            val mid = p.left + (start + x - 1) / 2
            if (mid - p.left < 2 || right - mid < 2) continue
            if (p.splitsWord(mid, medianHeight, gapExemptPx)) continue
            out.add(mid)
        }
        return out
    }

    /**
     * 选一刀：[latinSplitEnabled] 时要求“整行零墨且足够宽的空白”，
     * 否则走中文字缝，但同样拒绝劈开西文单词。
     * @return 切点与该列墨点数；`null` 表示这一行没有安全缝，不切。
     */
    private fun chooseCut(
        p: ColumnInk,
        center: Int,
        win: Int,
        medianHeight: Float,
        gapExemptPx: Int,
        balanceFirst: Boolean = false,
    ): Pair<Int, Float>? {
        if (p.width <= 0) return null
        if (options.latinSplitEnabled) {
            val cut = WordGapFinder.findCut(p, center, win, options.latinMinGapRatio * medianHeight)
                ?: return null
            return if (p.splitsWord(cut, medianHeight, gapExemptPx)) null else cut to 0f
        }
        return gapNear(p, center, win, medianHeight, gapExemptPx, balanceFirst)
    }

    /** 没有安全缝时的近似切点，只用于估计全局切分列，不用于实际切分。 */
    private fun approxCut(p: ColumnInk, center: Int, win: Int): Int {
        val lo = max(0, center - win)
        val hi = min(p.left + p.width, center + win)
        var minIdx = -1
        var minVal = Int.MAX_VALUE
        for (x in lo until hi) {
            val v = p.counts[x - p.left]
            if (v < minVal) {
                minVal = v
                minIdx = x
            }
        }
        return if (minIdx >= 0) minIdx else center
    }

    /**
     * 在 `center ± win` 内找字缝：优先零墨列组，其次低墨列组（≥2 列），都拒绝“劈开单词”。
     *
     * @return 切点 x 与该列的墨点数；`null` 表示窗口内没有可接受的字缝。
     */
    fun gapNear(
        p: ColumnInk,
        center: Int,
        win: Int,
        medianHeight: Float,
        gapExemptPx: Int = 0,
        balanceFirst: Boolean = false,
    ): Pair<Int, Float>? {
        if (p.width <= 0) return null
        val thr = p.lowInkThreshold

        val zero = bestRun(p, center, win, medianHeight, 2, gapExemptPx, balanceFirst) { it == 0 }
        if (zero != null) return zero to 0f
        // 中文 JPEG 截图里字缝常常只有 1 列，低墨列组的门槛放到 1 列；
        // 真正保护“不劈字”的是 [ColumnInk.splitsWord]，不是列组宽度。
        val low = bestRun(p, center, win, medianHeight, 1, gapExemptPx, balanceFirst) { it <= thr }
        if (low != null) return low to thr
        return null
    }

    /**
     * 找满足 [accept] 的连续列组，按**加权评分**取最优的一刀，并跳过会劈开西文单词的组。
     *
     * 评分（v1.4，权重见 [AnalysisOptions.cutWeightCenter] 等）：
     * ```
     * center  = 1 - |x - center| / win                 离目标列越近越好
     * gap     = min(1, 缝宽 / max(2, 0.35 × 行高))      缝越宽越像真边界（字距只有一半分）
     * balance = 2 - 2 × max(左半, 右半) / 行宽          两半越均衡越好
     * ink     = 1 - 缝内最干净列的墨量 / 低墨阈值        零墨满分
     * ```
     * [balanceFirst] = true 时 center 与 balance 的权重互换：估算全局列要稳（靠 center），
     * 逐行下刀要均衡（靠 balance，等于白捡 `slotFor` 的倍率）。
     *
     * 旧的字典序（离中心最近 → 缝更宽）会让 1px 的噪点缝压过 12px 的真字缝；
     * 加权后宽缝能补回一点距离差，但 center 仍占主导，列不会随参差的右边缘漂移。
     */
    private inline fun bestRun(
        p: ColumnInk,
        center: Int,
        win: Int,
        medianHeight: Float,
        minWidth: Int,
        gapExemptPx: Int,
        balanceFirst: Boolean,
        accept: (Int) -> Boolean,
    ): Int? {
        val lo = max(p.left, center - win)
        val hi = min(p.left + p.width, center + win)
        if (hi <= lo) return null
        val lineRight = p.left + p.width
        val lineWidth = max(1, p.width)
        val wCenter = if (balanceFirst) options.cutWeightBalance else options.cutWeightCenter
        val wBalance = if (balanceFirst) options.cutWeightCenter else options.cutWeightBalance
        val fullGap = max(2f, options.cutGapFullRatio * medianHeight)
        var best = -1
        var bestScore = -1f
        var i = lo - p.left
        val end = hi - p.left
        while (i < end) {
            if (!accept(p.counts[i])) {
                i++
                continue
            }
            val start = i
            while (i < end && accept(p.counts[i])) i++
            val gapWidth = i - start
            if (gapWidth < minWidth) continue
            val mid = p.left + (start + i - 1) / 2
            if (p.splitsWord(mid, medianHeight, gapExemptPx)) continue
            val score = scoreCandidate(p, mid, gapWidth, center, win, lineWidth, lineRight, wCenter, wBalance, fullGap)
            if (score > bestScore) {
                bestScore = score
                best = mid
            }
        }
        return if (best >= 0) best else null
    }

    /** 单个候选切点的加权得分；各项都归一化到 `0..1`。 */
    private fun scoreCandidate(
        p: ColumnInk,
        x: Int,
        gapWidth: Int,
        center: Int,
        win: Int,
        lineWidth: Int,
        lineRight: Int,
        wCenter: Float,
        wBalance: Float,
        fullGap: Float,
    ): Float {
        val d = abs(x - center)
        val centerScore = 1f - min(1f, d.toFloat() / max(1, win))
        val gapScore = min(1f, gapWidth / fullGap)
        val half = max(x - p.left, lineRight - x)
        val balanceScore = (2f - 2f * half / lineWidth).coerceIn(0f, 1f)
        val idx = (x - p.left).coerceIn(0, p.counts.size - 1)
        val thr = max(1f, p.lowInkThreshold)
        val inkScore = 1f - min(1f, p.counts[idx] / thr)
        return wCenter * centerScore +
            options.cutWeightGap * gapScore +
            wBalance * balanceScore +
            options.cutWeightInk * inkScore
    }
}
