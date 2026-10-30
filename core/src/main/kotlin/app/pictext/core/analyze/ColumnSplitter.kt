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
    val weak: Boolean,
)

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
    ): SplitOutcome {
        val pageWidth = metrics.pageWidth
        if (pageWidth <= 0 || blocks.isEmpty()) {
            return SplitOutcome(blocks, metrics.textLeft, 0f, 0, true)
        }
        val roughWidth = max(1, metrics.roughWidth)
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

        val candidates = textLines.filter { it.role == LineRole.BODY || it.role == LineRole.HEADING }
        val longLines = candidates.filter {
            it.band.width >= options.longLineRatio * roughWidth && !latinLike(it)
        }
        if (longLines.size < 2) {
            return SplitOutcome(blocks, (metrics.textLeft + metrics.textRight) / 2, 0f, 0, true)
        }

        val center = (metrics.textLeft + metrics.textRight) / 2
        val win0 = max(8, (options.cutWin0 * width).toInt())
        val first = IntArray(longLines.size) { i ->
            chooseCut(profile(longLines[i]), center, win0, medH, exemptFor(longLines[i]))?.first
                ?: approxCut(profile(longLines[i]), center, win0)
        }
        val cut = Stats.median(first).toInt()

        val win1 = max(5, (options.cutWin1 * width).toInt())
        val inset = options.splitInsetRatio * pageWidth
        var splitCount = 0
        var clean = 0
        val updated = HashMap<TextLine, TextLine>(textLines.size)
        for (line in textLines) {
            val bw = line.band.width
            val centered = abs((line.band.left - metrics.textLeft) - (metrics.textRight - line.band.right)) <
                0.06f * pageWidth && (line.band.left - metrics.textLeft) > 0.04f * pageWidth

            val eligibleRole = line.role == LineRole.BODY || line.role == LineRole.HEADING
            val leftAligned = line.band.left <= metrics.textLeft + inset
            val nearRight = line.band.right >= metrics.textRight - inset
            // 文档规则：左右都贴版心且够宽。
            val fullWidth = leftAligned && nearRight && bw >= options.splitFullWidthRatio * pageWidth
            // 左对齐 + 至少半版心也放行。标题同样适用：标题往往比正文短一截，不放行的话
            // 它只能被压进列宽，字号反而小于正文（audit 会报 heading_smaller_than_body）。
            // 真正决定“要不要切”的是规划器：标题装得下 bodyScale 时不会切。
            val longLeftAligned = leftAligned && bw >= options.splitLeftAlignedRatio * pageWidth
            val eligible = eligibleRole && !centered && (fullWidth || longLeftAligned)

            // 全局切点必须落在这一行里，否则它只是“短于切点的行”，按普通行缩放即可，
            // 不能算进 cleanRatio 的分母（否则每页都会被短行拉低到 weak）。
            val reachable = eligible && cut > line.band.left && cut < line.band.right &&
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
                    val g = chooseCut(profile(line), cut, win1, medH, exempt)
                        ?: chooseCut(profile(line), cut, win1 * 2 + 4, medH, exempt)
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
        if (weak) {
            // 整页退回“只放大不切”：保留 cutX 供调试，但所有 split 置 false。
            for ((k, v) in updated) updated[k] = v.copy(split = false, cuts = emptyList())
        }
        val newBlocks = blocks.map { block ->
            when (block) {
                is Block.Paragraph -> Block.Paragraph(block.lines.map { updated[it] ?: it })
                is Block.Figure -> block
            }
        }
        return SplitOutcome(newBlocks, cut, ratio, splitCount, weak)
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
    ): Pair<Int, Float>? {
        if (p.width <= 0) return null
        if (options.latinSplitEnabled) {
            val cut = WordGapFinder.findCut(p, center, win, options.latinMinGapRatio * medianHeight)
                ?: return null
            return if (p.splitsWord(cut, medianHeight, gapExemptPx)) null else cut to 0f
        }
        return gapNear(p, center, win, medianHeight, gapExemptPx)
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
    ): Pair<Int, Float>? {
        if (p.width <= 0) return null
        val thr = p.lowInkThreshold

        val zero = bestRun(p, center, win, medianHeight, 2, gapExemptPx) { it == 0 }
        if (zero != null) return zero to 0f
        // 中文 JPEG 截图里字缝常常只有 1 列，低墨列组的门槛放到 1 列；
        // 真正保护“不劈字”的是 [ColumnInk.splitsWord]，不是列组宽度。
        val low = bestRun(p, center, win, medianHeight, 1, gapExemptPx) { it <= thr }
        if (low != null) return low to thr
        return null
    }

    /**
     * 找满足 [accept] 的连续列组，按“离中心最近、同距离取更宽”挑，
     * 并跳过会劈开西文单词的组。
     */
    private inline fun bestRun(
        p: ColumnInk,
        center: Int,
        win: Int,
        medianHeight: Float,
        minWidth: Int,
        gapExemptPx: Int,
        accept: (Int) -> Boolean,
    ): Int? {
        val lo = max(p.left, center - win)
        val hi = min(p.left + p.width, center + win)
        if (hi <= lo) return null
        var best = -1
        var bestDist = Int.MAX_VALUE
        var bestWidth = 0
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
            val d = abs(mid - center)
            if (d < bestDist || (d == bestDist && gapWidth > bestWidth)) {
                bestDist = d
                bestWidth = gapWidth
                best = mid
            }
        }
        return if (best >= 0) best else null
    }
}
