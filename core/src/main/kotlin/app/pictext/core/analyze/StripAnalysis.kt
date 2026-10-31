package app.pictext.core.analyze

import app.pictext.core.model.Block
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.GrayImage
import app.pictext.core.model.PageLayout
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一条分析条带：在整页分析图里的 y 偏移、高度，以及该条带的版面结果。 */
data class StripPart(val top: Int, val height: Int, val layout: PageLayout)

/**
 * P2：像素超预算时把分析图按**高度**切条带，逐条分析后合并。
 *
 * 为什么按高度切：定标规则是宽度优先（[AnalysisScale]），条带只切高度，
 * 所以每条带的宽度都等于整页分析宽，字缝/行带/中线在条带内仍然是真像素。
 * 条带只压峰值内存（mask/contentMask 只留一条带），并把分析摊到多线程。
 *
 * 合并要做四件事，缺一件就会“悄悄做错”：
 *
 * 1. **行带缝合**：重叠区里同一行会被两条带各检一次，按 y/x 重叠判重，保留没被切边的那份；
 * 2. **段落缝合**：跨边界的段落要并回一个 `Paragraph`，否则规划器会把半段当整段算段间距；
 * 3. **统计量合并**：版心取 min/max、行高/行距/字距按行数加权中位数，不能让某条带说了算；
 * 4. **切分合并**：逐带的 cutX/split 已经在带内算好，这里只合并 splitX/cleanRatio，
 *    并把 `weak` 门统一放到整页判定（带内先不否决，否则只有两行的带会白白丢掉切分）。
 */
class StripLayoutAnalyzer(
    private val options: AnalysisOptions = AnalysisOptions(),
    /** @param stripSrcHeight 该条带换算回源图后的高度（`analysisHeight / analysisScale`）。 */
    private val analyzeStrip: (GrayImage, Int) -> PageLayout,
) {

    fun analyze(image: GrayImage, srcWidth: Int, srcHeight: Int): PageLayout {
        val scale = image.width.toFloat() / max(1, srcWidth)
        val slices = slices(image.height)
        if (slices.size <= 1) return analyzeStrip(image, srcHeight)

        val strips = slices.map { (top, height) ->
            GrayImage(
                width = image.width,
                height = height,
                data = image.data.copyOfRange(top * image.width, (top + height) * image.width),
                backgroundColor = image.backgroundColor,
            )
        }
        val stripSrcHeights = slices.map { (_, height) -> max(1, (height / scale).roundToInt()) }
        val layouts = runSlices(strips, stripSrcHeights)
        val parts = slices.mapIndexed { i, (top, height) -> StripPart(top, height, layouts[i]) }
        return StripMerger.merge(parts, image, srcWidth, srcHeight, scale, options)
    }

    /** 条带切片：`(top, height)`，相邻条带重叠 [AnalysisOptions.stripOverlap] 像素。 */
    fun slices(totalHeight: Int): List<Pair<Int, Int>> {
        val stripH = max(1, options.stripHeight)
        if (totalHeight <= stripH) return listOf(0 to totalHeight)
        val step = max(1, stripH - max(0, options.stripOverlap))
        val out = ArrayList<Pair<Int, Int>>(totalHeight / step + 1)
        var top = 0
        while (top < totalHeight) {
            val height = min(stripH, totalHeight - top)
            out.add(top to height)
            if (top + height >= totalHeight) break
            top += step
        }
        return out
    }

    private fun runSlices(strips: List<GrayImage>, heights: List<Int>): List<PageLayout> {
        val n = strips.size
        val parallelism = (if (options.stripParallelism > 0) {
            options.stripParallelism
        } else {
            min(4, Runtime.getRuntime().availableProcessors())
        }).coerceAtLeast(1)
        if (n <= 1 || parallelism <= 1) {
            return strips.mapIndexed { i, strip -> analyzeStrip(strip, heights[i]) }
        }
        val pool = Executors.newFixedThreadPool(min(n, parallelism)) { r ->
            Thread(r, "pictext-strip").apply { isDaemon = true }
        }
        return try {
            val futures = strips.mapIndexed { i, strip ->
                pool.submit(Callable { analyzeStrip(strip, heights[i]) })
            }
            futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
}

/** 条带结果的合并（纯函数，便于单测）。 */
object StripMerger {

    fun merge(
        parts: List<StripPart>,
        image: GrayImage,
        srcWidth: Int,
        srcHeight: Int,
        analysisScale: Float,
        options: AnalysisOptions = AnalysisOptions(),
    ): PageLayout {
        if (parts.isEmpty()) {
            return PageLayout(
                srcWidth = srcWidth, srcHeight = srcHeight, analysisScale = analysisScale,
                blocks = emptyList(), textLeft = 0, textRight = 0, splitX = -1, cleanRatio = 0f,
                medianLineHeight = 0f, medianGap = 0f, medianPitch = 0f,
                isDark = false, backgroundColor = image.backgroundColor,
                diagnostics = Diagnostics(notes = listOf("no_ink")),
            )
        }

        val blocks = stitchBlocks(parts)
        val textParts = parts.filter { it.layout.textLines().isNotEmpty() }

        // ---- 版心：x 是同一套坐标，取 min/max；不能把“没有文字的带”的 0 算进去 ----
        val textLeft = textParts.minOfOrNull { it.layout.textLeft } ?: 0
        val textRight = textParts.maxOfOrNull { it.layout.textRight } ?: 0
        val roughLeft = textParts.minOfOrNull { p ->
            p.layout.blocks.minOfOrNull { it.bounds.left } ?: p.layout.textLeft
        } ?: textLeft
        val roughRight = textParts.maxOfOrNull { p ->
            p.layout.blocks.maxOfOrNull { it.bounds.right } ?: p.layout.textRight
        } ?: textRight

        // ---- 行高/行距/字距：按行数加权中位数，避免只有两行的带把整页统计带偏 ----
        fun weightedMedian(select: (PageLayout) -> Float): Float {
            val pairs = textParts.map { it.layout to it.layout.textLines().size }
                .filter { it.second > 0 && select(it.first) > 0f }
                .sortedBy { select(it.first) }
            if (pairs.isEmpty()) return 0f
            val total = pairs.sumOf { it.second }
            var acc = 0
            for ((layout, weight) in pairs) {
                acc += weight
                if (acc * 2 >= total) return select(layout)
            }
            return select(pairs.last().first)
        }

        val medH = weightedMedian { it.medianLineHeight }
        val medGap = weightedMedian { it.medianGap }
        val medPitch = weightedMedian { it.medianPitch }

        // ---- 切分：splitX 加权中位数，cleanRatio 用总计数 ----
        val splitParts = parts.filter { it.layout.splitX > 0 }
        val splitX = if (splitParts.isEmpty()) {
            (textLeft + textRight) / 2
        } else {
            weightedMedianOf(
                splitParts.map { it.layout.splitX.toFloat() to max(1, it.layout.diagnostics.splitCount) },
            )
        }
        val splitCount = parts.sumOf { it.layout.diagnostics.splitCount }
        val splitClean = parts.sumOf { it.layout.diagnostics.splitClean }
        val cleanRatio = if (splitCount == 0) 0f else splitClean.toFloat() / splitCount
        val weak = splitCount == 0 || cleanRatio < options.cutCleanRatio

        val finalBlocks = if (weak) clearSplits(blocks) else blocks

        // ---- 背景/深色：按行数投票 ----
        val dominant = textParts.maxByOrNull { it.layout.textLines().size } ?: parts.first()
        val darkVotes = textParts.count { it.layout.isDark }
        val isDark = if (textParts.isEmpty()) dominant.layout.isDark else darkVotes * 2 > textParts.size

        // ---- 诊断与 notes：先合并，再按整页口径重算否决项 ----
        val notes = LinkedHashSet<String>()
        parts.forEach { notes.addAll(it.layout.diagnostics.notes) }
        notes.remove("no_ink")
        notes.remove("no_text")
        notes.remove("too_few_long_lines")
        notes.remove("multi_column")
        notes.remove("weak_cut")
        val longLineCount = parts.sumOf { it.layout.diagnostics.longLineCount }
        if (textParts.isEmpty()) {
            notes.add("no_text")
        } else {
            if (longLineCount < 2) notes.add("too_few_long_lines")
            val multi = parts.count { "multi_column" in it.layout.diagnostics.notes }
            if (multi * 2 >= textParts.size) notes.add("multi_column")
            if (weak) notes.add("weak_cut")
        }
        notes.add("strip=${parts.size}")

        return PageLayout(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            analysisScale = analysisScale,
            blocks = finalBlocks,
            textLeft = textLeft,
            textRight = textRight,
            splitX = splitX,
            cleanRatio = cleanRatio,
            medianLineHeight = medH,
            medianGap = medGap,
            medianPitch = if (medPitch > 0f) medPitch else medH + medGap,
            isDark = isDark,
            backgroundColor = dominant.layout.backgroundColor,
            diagnostics = Diagnostics(
                inkPixels = parts.sumOf { it.layout.diagnostics.inkPixels },
                inkThreshold = dominant.layout.diagnostics.inkThreshold,
                rowCount = parts.sumOf { it.layout.diagnostics.rowCount },
                figureCount = parts.sumOf { it.layout.diagnostics.figureCount },
                cleanRatio = cleanRatio,
                notes = notes.toList(),
                splitCount = splitCount,
                splitClean = splitClean,
                longLineCount = longLineCount,
            ),
            strategy = dominant.layout.strategy,
        )
    }

    private fun weightedMedianOf(values: List<Pair<Float, Int>>): Int {
        val sorted = values.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        var acc = 0
        for ((v, w) in sorted) {
            acc += w
            if (acc * 2 >= total) return v.roundToInt()
        }
        return sorted.last().first.roundToInt()
    }

    /**
     * 把各条带的块平移到位，并在**行级**做跨条带判重。
     *
     * 为什么是行级而不是段落级：重叠区 48px 比一行还高，同一行会被两条带各检一次，
     * 而且两条带的段落切分不一定一致（一条带的末行被切边后常常自成一个“细条段”）。
     * 按段落比对会漏掉这种错位。行级判重的规则很简单：两行 y/x 重叠比例都够高就是同一行，
     * 保留更高（没被切边）的那份；被替换掉的若把段落掏空，整段丢弃。
     */
    private fun stitchBlocks(parts: List<StripPart>): List<Block> {
        val figures = HashMap<Int, Block>()
        val paragraphs = HashMap<Int, MutableList<TextLine>>()
        val order = ArrayList<Int>()
        var index = 0
        for (part in parts) {
            for (block in part.layout.blocks) {
                order.add(index)
                when (block) {
                    is Block.Figure -> figures[index] = shift(block, part.top)
                    is Block.Paragraph -> paragraphs[index] = block.lines
                        .map { it.copy(band = it.band.offsetY(part.top)) }
                        .toMutableList()
                }
                index++
            }
        }

        val dropped = HashSet<Pair<Int, Int>>()
        val all = paragraphs.keys.sorted().flatMap { key ->
            paragraphs.getValue(key).mapIndexed { i, line -> Triple(key, i, line) }
        }.sortedWith(compareBy({ it.third.band.top }, { it.third.band.height }))
        var previous: Triple<Int, Int, TextLine>? = null
        for (current in all) {
            val prev = previous
            if (prev != null && sameLine(prev.third.band, current.third.band)) {
                if (current.third.band.height > prev.third.band.height) {
                    dropped.add(prev.first to prev.second)
                    previous = current
                } else {
                    dropped.add(current.first to current.second)
                }
            } else {
                previous = current
            }
        }

        val out = ArrayList<Block>(order.size)
        for (key in order) {
            val figure = figures[key]
            if (figure != null) {
                out.add(figure)
                continue
            }
            val lines = paragraphs[key] ?: continue
            val kept = lines.filterIndexed { i, _ -> (key to i) !in dropped }
            if (kept.isNotEmpty()) out.add(Block.Paragraph(kept))
        }
        return out
    }

    private fun shift(block: Block, dy: Int): Block = when (block) {
        is Block.Paragraph -> Block.Paragraph(block.lines.map { it.copy(band = it.band.offsetY(dy)) })
        is Block.Figure -> Block.Figure(block.bounds.offsetY(dy), block.kind)
    }

    private fun RectI.offsetY(dy: Int): RectI = RectI(left, top + dy, right, bottom + dy)

    private fun sameLine(a: RectI, b: RectI): Boolean {
        val oy = min(a.bottom, b.bottom) - max(a.top, b.top)
        val ox = min(a.right, b.right) - max(a.left, b.left)
        if (oy <= 0 || ox <= 0) return false
        val minH = min(a.height, b.height).coerceAtLeast(1)
        val minW = min(a.width, b.width).coerceAtLeast(1)
        return oy.toFloat() / minH > 0.6f && ox.toFloat() / minW > 0.5f
    }

    private fun clearSplits(blocks: List<Block>): List<Block> = blocks.map { block ->
        when (block) {
            is Block.Paragraph -> Block.Paragraph(
                block.lines.map { it.copy(split = false, cuts = emptyList()) },
            )
            is Block.Figure -> block
        }
    }
}
