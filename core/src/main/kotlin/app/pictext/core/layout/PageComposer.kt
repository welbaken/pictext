package app.pictext.core.layout

import app.pictext.core.model.Block
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LayoutStrategy
import app.pictext.core.model.PageLayout
import app.pictext.core.model.RegionKind
import app.pictext.core.model.TextLine
import kotlin.math.max

/**
 * 把“有序区域 + 细化行”组装成 [PageLayout]（docs/07 V2-24）。
 *
 * v2 的 `PageLayout` 用 **S 坐标 + `analysisScale = 1.0`**：模型框、细化行带、渲染矩形
 * 三者不必换算，少一层转换就少一类 bug。`splitX = -1`、`cleanRatio = 1.0`（v2 不用这两个字段）。
 *
 * 区域 → 块的映射：
 * - TEXT / TITLE / CAPTION → [Block.Paragraph]（行角色由细化期决定）；
 * - CHROME 有行 → 也是 [Block.Paragraph]，但行角色是 UI（≤1.0 倍，不切）；
 * - 其余（FIGURE / TABLE / OTHER / 无行的 CHROME）→ [Block.Figure] 整块保留。
 */
class PageComposer {

    fun compose(
        page: PageContext,
        regions: List<LayoutRegion>,
        coverage: CoverageReport?,
        notes: List<String> = emptyList(),
    ): PageLayout {
        val ordered = regions.sortedBy { it.order }
        val blocks = ArrayList<Block>(ordered.size)
        val allLines = ArrayList<TextLine>()

        for (r in ordered) {
            when (r.kind) {
                RegionKind.TEXT, RegionKind.TITLE, RegionKind.CAPTION -> {
                    if (r.lines.isNotEmpty()) {
                        blocks.add(Block.Paragraph(r.lines))
                        allLines.addAll(r.lines)
                    } else {
                        // 没有行的“文字区”按图块保留像素：宁可整块，不可丢内容。
                        blocks.add(Block.Figure(r.bounds, FigureKind.PHOTO))
                    }
                }

                RegionKind.CHROME -> {
                    if (r.lines.isNotEmpty()) {
                        val ui = r.lines.map { it.copy(role = LineRole.UI) }
                        blocks.add(Block.Paragraph(ui))
                        allLines.addAll(ui)
                    } else {
                        blocks.add(Block.Figure(r.bounds, FigureKind.UI))
                    }
                }

                RegionKind.TABLE -> blocks.add(Block.Figure(r.bounds, FigureKind.PHOTO))
                RegionKind.OTHER -> blocks.add(Block.Figure(r.bounds, FigureKind.DECOR))
                RegionKind.FIGURE -> blocks.add(Block.Figure(r.bounds, FigureKind.PHOTO))
            }
        }

        val textLeft = allLines.minOfOrNull { it.band.left } ?: 0
        val textRight = allLines.maxOfOrNull { it.band.right } ?: 0
        val bodyH = bodyMedianHeight(allLines)
        val gap = medianGap(ordered)
        val pitch = medianPitch(ordered, bodyH, gap)
        val figureCount = blocks.count { it is Block.Figure }

        return PageLayout(
            srcWidth = page.srcWidth,
            srcHeight = page.srcHeight,
            analysisScale = 1f,
            blocks = blocks,
            textLeft = textLeft,
            textRight = textRight,
            splitX = -1,
            cleanRatio = 1f,
            medianLineHeight = bodyH,
            medianGap = gap,
            medianPitch = pitch,
            isDark = page.isDark,
            backgroundColor = page.backgroundColor,
            diagnostics = Diagnostics(
                inkPixels = coverage?.ink ?: 0,
                inkThreshold = 0,
                rowCount = allLines.size,
                figureCount = figureCount,
                cleanRatio = 1f,
                notes = notes + "layout_strategy=DOC_LAYOUT",
            ),
            regions = ordered,
            strategy = LayoutStrategy.DOC_LAYOUT,
        )
    }

    /** BODY 行高优先；页面没有 BODY 时退回全部文字行（标题页、纯图注页也能规划）。 */
    private fun bodyMedianHeight(lines: List<TextLine>): Float {
        val body = lines.filter { it.role == LineRole.BODY }
        val src = body.ifEmpty { lines }
        if (src.isEmpty()) return 0f
        val h = IntArray(src.size) { src[it].band.height }
        h.sort()
        return h[h.size / 2].toFloat()
    }

    private fun medianGap(regions: List<LayoutRegion>): Float {
        val gaps = ArrayList<Int>()
        for (r in regions) {
            for (i in 1 until r.lines.size) {
                val g = r.lines[i].band.top - r.lines[i - 1].band.bottom
                if (g >= 0) gaps.add(g)
            }
        }
        if (gaps.isEmpty()) return 6f
        gaps.sort()
        return max(1, gaps[gaps.size / 2]).toFloat()
    }

    private fun medianPitch(regions: List<LayoutRegion>, lineHeight: Float, gap: Float): Float {
        val pitches = ArrayList<Int>()
        for (r in regions) {
            for (i in 1 until r.lines.size) {
                val p = r.lines[i].band.top - r.lines[i - 1].band.top
                if (p > 0) pitches.add(p)
            }
        }
        if (pitches.isEmpty()) return lineHeight + gap
        pitches.sort()
        return max(1, pitches[pitches.size / 2]).toFloat()
    }
}
