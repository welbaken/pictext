package app.pictext.cli

import app.pictext.core.layout.Detection
import app.pictext.core.layout.LayoutDetector
import app.pictext.core.layout.LayoutInput
import app.pictext.core.layout.LayoutSummaryStats
import app.pictext.core.layout.ModelLabels
import app.pictext.core.layout.Tile
import app.pictext.core.model.PageLayout
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.plan.SmartReflowResult
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/**
 * 桌面 CLI 的 v2 辅助：脚本检测器、区域 JSON、调试叠加。
 *
 * 还没有接 ORT 之前（M0 契约未验收），CLI 用 **脚本检测器** 喂已知框：
 * 这样区域合并、细化、阅读顺序、覆盖审计、规划、审计这整条链路都能在没有模型的情况下跑通，
 * 也是 docs/07 §4.11.5 里“不加载模型的纯 Kotlin 测试”的桌面版。
 */
class ScriptedLayoutDetector(
    override val modelInputSize: Int,
    private val boxes: List<ScriptedBox>,
) : LayoutDetector {

    override val modelName: String get() = "scripted"

    override fun detect(input: LayoutInput, tile: Tile): List<Detection> {
        val out = ArrayList<Detection>(boxes.size)
        for (b in boxes) {
            // 只让“框中心落在本 tile 内”的那个 tile 输出全框：否则每个相邻 tile 都会吐一份被裁碎的框，
            // 跨 tile 去重会被碎片拖垮。
            val cx = (b.bounds.left + b.bounds.right) / 2
            val cy = (b.bounds.top + b.bounds.bottom) / 2
            if (!tile.src.contains(cx, cy)) continue
            out.add(
                Detection(
                    labelId = b.labelId,
                    score = b.score,
                    left = tile.modelX(b.bounds.left.toFloat()),
                    top = tile.modelY(b.bounds.top.toFloat()),
                    right = tile.modelX(b.bounds.right.toFloat()),
                    bottom = tile.modelY(b.bounds.bottom.toFloat()),
                ),
            )
        }
        return out
    }
}

/** 脚本检测器的一个框（S 坐标）。 */
data class ScriptedBox(val labelId: Int, val score: Float, val bounds: RectI)

/**
 * 解析 `--detections` 文件。每行 `label score x1 y1 x2 y2`，`#` 开头为注释。
 *
 * 用文本而不是 JSON：CLI 没有 JSON 依赖，手改一行比改嵌套结构快，
 * 也和 `regions.json` 的“人类可读”目标一致。
 */
fun parseDetections(file: File): Pair<List<ScriptedBox>, List<String>> {
    val labels = ModelLabels()
    val boxes = ArrayList<ScriptedBox>()
    val errors = ArrayList<String>()
    if (!file.isFile) {
        errors.add("detections file not found: ${file.path}")
        return boxes to errors
    }
    file.readLines().forEachIndexed { index, raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEachIndexed
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 6) {
            errors.add("line ${index + 1}: want `label score x1 y1 x2 y2`, got `$line`")
            return@forEachIndexed
        }
        val labelId = ModelLabels.LABELS.indexOf(parts[0])
        if (labelId < 0 || !labels.isKnown(labelId)) {
            errors.add("line ${index + 1}: unknown label `${parts[0]}`")
            return@forEachIndexed
        }
        val score = parts[1].toFloatOrNull()
        val x1 = parts[2].toIntOrNull()
        val y1 = parts[3].toIntOrNull()
        val x2 = parts[4].toIntOrNull()
        val y2 = parts[5].toIntOrNull()
        if (score == null || x1 == null || y1 == null || x2 == null || y2 == null) {
            errors.add("line ${index + 1}: bad number in `$line`")
            return@forEachIndexed
        }
        if (x2 <= x1 || y2 <= y1) {
            errors.add("line ${index + 1}: empty rect in `$line`")
            return@forEachIndexed
        }
        boxes.add(ScriptedBox(labelId, score, RectI(x1, y1, x2, y2)))
    }
    return boxes to errors
}

/** 区域框/序号/标签叠加，用于 `debug.png`（v2）。 */
fun v2DebugOverlay(source: BufferedImage, layout: PageLayout): BufferedImage {
    val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.drawImage(source, 0, 0, null)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    for (r in layout.regions.sortedBy { it.order }) {
        val c = kindColor(r.kind)
        g.color = Color(c.red, c.green, c.blue, 40)
        g.fillRect(r.bounds.left, r.bounds.top, r.bounds.width, r.bounds.height)
        g.color = c
        g.stroke = BasicStroke(2f)
        g.drawRect(r.bounds.left, r.bounds.top, r.bounds.width, r.bounds.height)

        // 行带：细框，用来检查“模型框只是 ROI、几何来自投影”。
        g.stroke = BasicStroke(1f)
        for (line in r.lines) {
            g.drawRect(line.band.left, line.band.top, line.band.width, line.band.height)
        }

        val label = "#${r.order} ${r.kind} ${r.label} ${"%.2f".format(r.confidence)}"
        g.font = Font("SansSerif", Font.BOLD, 16)
        val tw = g.fontMetrics.stringWidth(label) + 8
        val th = g.fontMetrics.height + 4
        var tx = r.bounds.left
        var ty = r.bounds.top - th
        if (ty < 0) ty = r.bounds.top
        if (tx + tw > source.width) tx = (source.width - tw).coerceAtLeast(0)
        g.color = Color(0, 0, 0, 170)
        g.fillRect(tx, ty, tw, th)
        g.color = Color.WHITE
        g.drawString(label, tx + 4, ty + th - 5)
    }

    // 切点：绿色=硬缝起点，橙色=最小墨量列（强制切的候选）。
    for (line in layout.regions.flatMap { it.lines }) {
        val f = line.cutFeatures ?: continue
        g.stroke = BasicStroke(1f)
        g.color = Color(0, 170, 90, 160)
        for (gap in f.hardGaps) {
            g.drawLine(gap.first, line.band.top, gap.first, line.band.bottom)
        }
        if (f.minInkColumn >= 0) {
            g.color = Color(230, 120, 0, 160)
            g.drawLine(f.minInkColumn, line.band.top, f.minInkColumn, line.band.bottom)
        }
    }
    g.dispose()
    return out
}

private fun kindColor(kind: RegionKind): Color = when (kind) {
    RegionKind.TEXT -> Color(220, 40, 40)
    RegionKind.TITLE -> Color(200, 0, 160)
    RegionKind.CAPTION -> Color(30, 90, 220)
    RegionKind.FIGURE -> Color(240, 120, 0)
    RegionKind.TABLE -> Color(150, 60, 200)
    RegionKind.CHROME -> Color(90, 90, 90)
    RegionKind.OTHER -> Color(0, 150, 150)
}

/** `regions.json`：模型框 + 合并后区域 + 管线统计。 */
fun regionsJson(result: SmartReflowResult, stats: LayoutSummaryStats?): String {
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"strategy\": \"${result.layout.strategy}\",\n")
    sb.append("  \"fallbackCode\": ${jsonString(result.fallbackCode)},\n")
    if (stats != null) {
        sb.append("  \"tiles\": ${stats.tiles},\n")
        sb.append("  \"detections\": ${stats.detections},\n")
        sb.append("  \"coverage\": ${"%.4f".format(stats.coverage)},\n")
        sb.append("  \"residuals\": ${stats.residuals},\n")
        sb.append("  \"orderAmbiguous\": ${stats.orderAmbiguous},\n")
        sb.append("  \"verticalRatio\": ${"%.3f".format(stats.verticalRatio)},\n")
        sb.append("  \"maxScore\": ${"%.3f".format(stats.maxScore)},\n")
    }
    sb.append("  \"regions\": [\n")
    val regions = result.layout.regions.sortedBy { it.order }
    for ((i, r) in regions.withIndex()) {
        sb.append("    {\"id\":${r.id},\"order\":${r.order},\"kind\":\"${r.kind}\",")
        sb.append("\"source\":\"${r.source}\",\"label\":${jsonString(r.label)},")
        sb.append("\"score\":${"%.3f".format(r.confidence)},")
        sb.append("\"bounds\":[${r.bounds.left},${r.bounds.top},${r.bounds.right},${r.bounds.bottom}],")
        sb.append("\"vertical\":${r.vertical},")
        sb.append("\"lines\":[")
        sb.append(r.lines.joinToString(",") { l -> lineJson(l) })
        sb.append("]}")
        if (i != regions.lastIndex) sb.append(",")
        sb.append("\n")
    }
    sb.append("  ]\n}\n")
    return sb.toString()
}

private fun lineJson(l: app.pictext.core.model.TextLine): String {
    val f = l.cutFeatures
    return "{\"band\":[${l.band.left},${l.band.top},${l.band.right},${l.band.bottom}]," +
        "\"role\":\"${l.role}\"," +
        "\"hardGaps\":${f?.hardGaps?.size ?: 0}," +
        "\"softGaps\":${f?.softGaps?.size ?: 0}," +
        "\"minInkColumn\":${f?.minInkColumn ?: -1}," +
        "\"sensitive\":${l.sensitive}}"
}

/** `plan.json` 里的 v2 段（`strategy` / `layout` / `auditV2`）。 */
fun layoutSummaryJson(result: SmartReflowResult): String {
    val audit = result.layoutAudit
    val summary = result.plan.layout
    val sb = StringBuilder()
    sb.append("\"strategy\": \"${result.plan.strategy}\",\n")
    sb.append("  \"fallbackCode\": ${jsonString(result.fallbackCode)},\n")
    if (summary != null) {
        sb.append("  \"layout\": {\"model\":${jsonString(summary.model)},\"tiles\":${summary.tiles},")
        sb.append("\"regions\":${summary.regions},\"coverage\":${"%.4f".format(summary.coverage)},")
        sb.append("\"residuals\":${summary.residuals},\"ambiguous\":${summary.orderAmbiguous},")
        sb.append("\"forcedCutLines\":${summary.forcedCutLines},\"splitLines\":${summary.splitLines},")
        sb.append("\"compressedLines\":${summary.compressedLines},")
        sb.append("\"sensitiveCompressedLines\":${summary.sensitiveCompressedLines},")
        sb.append("\"maxSegments\":${summary.maxSegments}},\n")
    }
    if (audit != null) {
        sb.append("  \"auditV2\": {\"ok\":${audit.ok},")
        sb.append("\"scaleDeviation\":${"%.4f".format(audit.maxScaleDeviation)},")
        sb.append("\"uiMaxScale\":${"%.2f".format(audit.uiMaxScale)},")
        sb.append("\"chromeMaxScale\":${"%.2f".format(audit.chromeMaxScale)},")
        sb.append("\"forcedCutRatio\":${"%.3f".format(audit.forcedCutRatio)},")
        sb.append("\"sensitiveForcedCuts\":${audit.sensitiveForcedCuts},")
        sb.append("\"compressedRatio\":${"%.3f".format(audit.compressedRatio)},")
        sb.append("\"maxSegments\":${audit.maxSegments},")
        sb.append("\"regionsWithoutItem\":${audit.regionsWithoutItem},")
        sb.append("\"issues\":[${audit.issues.joinToString(",") { jsonString(it) }}]},\n")
    }
    return sb.toString()
}

private fun jsonString(v: String?): String =
    if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** CLI 打印行：一行看清“用了哪条链路、几个 tile、覆盖多少、审计过没过”。 */
fun summaryLine(result: SmartReflowResult): String {
    val s = result.layoutStats?.summary
    val audit = result.layoutAudit
    val base = "strategy=${result.layout.strategy} v2fallback=${result.fallbackCode ?: "none"} " +
        "regions=${result.layout.regions.size} lines=${result.layout.regionLines().size} " +
        "scale=${"%.2f".format(result.plan.bodyScale)} mode=${result.plan.mode} " +
        "lineH=${"%.1f".format(result.layout.medianLineHeight)} " +
        "effPx=${"%.1f".format(result.plan.bodyScale * result.layout.medianLineHeight)}"
    val extra = if (s != null) {
        " tiles=${s.tiles} dets=${s.detections} residuals=${s.residuals} " +
            "coverage=${"%.4f".format(s.coverage)} ambiguous=${s.orderAmbiguous} " +
            (if (s.inverted) "invert=1 " else "") +
            "textArea=${"%.3f".format(s.textAreaRatio)} inkInText=${"%.3f".format(s.inkInTextRatio)} " +
            "blockLines=${"%.3f".format(s.blockLineRatio)} emptyText=${"%.3f".format(s.emptyTextRegionRatio)} " +
            s.stageSummary()
    } else {
        ""
    }
    val ok = audit?.let { " auditV2=${if (it.ok) "ok" else "FAIL(${it.issues})"}" } ?: ""
    return base + extra + ok
}



