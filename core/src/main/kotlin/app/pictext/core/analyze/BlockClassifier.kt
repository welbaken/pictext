package app.pictext.core.analyze

import app.pictext.core.model.Block
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import app.pictext.core.util.Stats
import kotlin.math.max
import kotlin.math.min

data class ClassifiedPage(
    val blocks: List<Block>,
    val medianLineHeight: Float,
    val medianGap: Float,
)

/**
 * Step 5 + 6：逐行带判断角色，再合并图块并向外扩张到视觉边界。
 *
 * v1.1 的顺序（对应 docs/06 §2 的插入点）：
 *
 * ```text
 * 行带 → ① 实心/竖直长游程 → PHOTO 图块
 *      → ② 仍然太高 → RowDecomposer 再切行（标题/紧排正文）
 *      → ③ 仍太高且切不出稳定结构 → UI 图块（头像块、按钮条、海报）
 *      → ④ RowClassifier 给出行角色 BODY / HEADING / CAPTION / UI
 * ```
 *
 * 关键点：**先判图、再拆行、最后判角色**。反过来的话，头像/按钮行会先被拆成“文字”，
 * 再被 P0-1 之后的高度护栏“救”成图块，顺序仍然是错的。
 */
class BlockClassifier(private val options: AnalysisOptions = AnalysisOptions()) {

    private data class RawLine(
        var y0: Int,
        var y1: Int,
        var x0: Int,
        var x1: Int,
        val role: LineRole?,
        val kind: FigureKind,
    ) {
        val isFigure: Boolean get() = role == null
    }

    private val decomposer = RowDecomposer(options)
    private val classifier = RowClassifier(options)

    fun classify(
        mask: BooleanArray,
        width: Int,
        height: Int,
        bands: List<RowBand>,
        medianHeight: Float,
        ink: ByteArray,
    ): ClassifiedPage {
        if (bands.isEmpty()) return ClassifiedPage(emptyList(), medianHeight, 6f)

        val vrun = verticalRuns(mask, width, height)
        val raw = ArrayList<RawLine>(bands.size)
        for (band in bands) {
            if (band.height <= 0) continue
            emitBand(mask, width, band, medianHeight, vrun, raw, depth = 0)
        }
        if (raw.isEmpty()) return ClassifiedPage(emptyList(), medianHeight, 6f)

        // 图块之间的行距中位数
        val gaps = ArrayList<Int>()
        for (i in 1 until raw.size) {
            val g = raw[i].y0 - raw[i - 1].y1
            if (g > 0) gaps.add(g)
        }
        val gap = if (gaps.isEmpty()) 6f else Stats.median(gaps, 6)

        // 照片中间的浅色天空会把图块切成两段，先合并相邻图块
        val merged = ArrayList<RawLine>(raw.size)
        for (line in raw) {
            val last = merged.lastOrNull()
            if (last != null && line.isFigure && last.isFigure &&
                line.y0 - last.y1 <= options.figMergeGapRatio * gap
            ) {
                last.y1 = line.y1
                last.x0 = min(last.x0, line.x0)
                last.x1 = max(last.x1, line.x1)
            } else {
                merged.add(line)
            }
        }

        // 低反差墨迹用于把图块扩张到卡片、坐标轴、浅色底
        val lowc = BooleanArray(width * height)
        for (i in ink.indices) {
            val v = ink[i].toInt() and 0xFF
            lowc[i] = v > 4 && v < 45
        }
        for (line in merged) {
            if (!line.isFigure) continue
            expandLowContrast(lowc, width, height, line)
        }

        // 相邻文字行聚成段落；角色变化或段间距变大时切开
        val blocks = ArrayList<Block>(merged.size)
        var para = ArrayList<TextLine>()
        fun flush() {
            if (para.isNotEmpty()) {
                blocks.add(Block.Paragraph(para))
                para = ArrayList()
            }
        }
        for (line in merged) {
            if (line.isFigure) {
                flush()
                blocks.add(Block.Figure(RectI(line.x0, line.y0, line.x1, line.y1), line.kind))
            } else {
                val role = line.role ?: LineRole.BODY
                val tl = TextLine(RectI(line.x0, line.y0, line.x1, line.y1), role = role)
                val prev = para.lastOrNull()
                if (prev != null &&
                    (prev.role != role || prev.band.bottom + options.figMergeGapRatio * gap < line.y0)
                ) {
                    flush()
                }
                para.add(tl)
            }
        }
        flush()

        val textHeights = merged.filter { !it.isFigure }.map { it.y1 - it.y0 }
        val medH = if (textHeights.isEmpty()) {
            medianHeight
        } else {
            Stats.robustMedian(textHeights.toIntArray(), medianHeight.toInt())
        }
        return ClassifiedPage(blocks, medH, gap)
    }

    private fun emitBand(
        mask: BooleanArray,
        width: Int,
        band: RowBand,
        medianHeight: Float,
        vrun: IntArray,
        out: MutableList<RawLine>,
        depth: Int,
    ) {
        if (band.height <= 0 || band.width <= 0) return
        val f = classifier.features(mask, width, band, medianHeight, vrun, width)

        // ① 照片/实心块：墨密度高，或存在超长竖直墨迹。
        if (f.density > options.figDensity ||
            (f.maxRun > options.figRunRatio * medianHeight && f.longFrac > options.figRunFrac)
        ) {
            out.add(RawLine(band.y0, band.y1, band.x0, band.x1, null, FigureKind.PHOTO))
            return
        }

        // ② 仍然太高：先尝试按内部谷值再切行。
        if (depth == 0 && band.height > options.rowDecomposeHeightRatio * medianHeight) {
            val subs = decomposer.decompose(mask, width, band, medianHeight)
            if (subs != null) {
                for (sub in subs) emitBand(mask, width, sub, medianHeight, vrun, out, depth + 1)
                return
            }
        }

        // ③ 切不出稳定行结构：整体当 UI 图块（头像带、按钮条、海报），绝不硬切。
        if (band.height > options.figHeightRatio * medianHeight) {
            out.add(RawLine(band.y0, band.y1, band.x0, band.x1, null, FigureKind.UI))
            return
        }

        // ④ 角色分类。
        val role = classifier.role(f, medianHeight)
        out.add(RawLine(band.y0, band.y1, band.x0, band.x1, role, FigureKind.PHOTO))
    }

    /** 一次自上而下的游程扫描，得到每个像素所在竖直墨迹的长度。 */
    fun verticalRuns(mask: BooleanArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        val run = IntArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                run[x] = if (mask[base + x]) run[x] + 1 else 0
                out[base + x] = run[x]
            }
        }
        return out
    }

    private fun expandLowContrast(lowc: BooleanArray, width: Int, height: Int, line: RawLine) {
        val h0 = line.y1 - line.y0
        val w0 = line.x1 - line.x0
        if (h0 <= 0 || w0 <= 0) return
        val maxPy = max(12, (0.30f * h0).toInt())
        val maxPx = max(12, (0.30f * w0).toInt())

        var step = 0
        while (step < maxPy && line.y0 > 0 && rowCoverage(lowc, width, line.y0 - 1, line.x0, line.x1) >= 0.5f) {
            line.y0--
            step++
        }
        step = 0
        while (step < maxPy && line.y1 < height &&
            rowCoverage(lowc, width, line.y1, line.x0, line.x1) >= 0.5f
        ) {
            line.y1++
            step++
        }
        step = 0
        while (step < maxPx && line.x0 > 0 &&
            colCoverage(lowc, width, line.x0 - 1, line.y0, line.y1) >= 0.5f
        ) {
            line.x0--
            step++
        }
        step = 0
        while (step < maxPx && line.x1 < width &&
            colCoverage(lowc, width, line.x1, line.y0, line.y1) >= 0.5f
        ) {
            line.x1++
            step++
        }
    }

    private fun rowCoverage(lowc: BooleanArray, width: Int, y: Int, x0: Int, x1: Int): Float {
        if (x1 <= x0) return 0f
        val base = y * width
        var c = 0
        for (x in x0 until x1) if (lowc[base + x]) c++
        return c.toFloat() / (x1 - x0)
    }

    private fun colCoverage(lowc: BooleanArray, width: Int, x: Int, y0: Int, y1: Int): Float {
        if (y1 <= y0) return 0f
        var c = 0
        for (y in y0 until y1) if (lowc[y * width + x]) c++
        return c.toFloat() / (y1 - y0)
    }
}
