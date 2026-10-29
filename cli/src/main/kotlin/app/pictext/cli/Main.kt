package app.pictext.cli

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.Block
import app.pictext.core.model.FigureKind
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.plan.PlanAuditReport
import app.pictext.core.plan.PlanAuditor
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import app.pictext.core.util.Grayscale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 桌面调参 CLI：把一张图片跑成重排长图、调试图和 plan.json。
 *
 * 用法：
 * ```
 * ./gradlew :cli:run --args="in.png out/ --out-width 1080"
 * ./gradlew :cli:run --args="summary out/summary.png a.png b.png c.png"
 * ```
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println(
            "usage: pictext-cli <input-image> [out-dir] [--out-width N] [--target-em N] [--latin-split]",
        )
        System.err.println("       pictext-cli summary <out.png> <a.png> <b.png> ...")
        return
    }
    if (args[0] == "summary") {
        summary(args.drop(1))
        return
    }
    val input = File(args[0])
    if (!input.isFile) {
        System.err.println("input not found: $input")
        return
    }
    var outDir = File("out")
    var outWidth = 1080
    var targetEm = 44
    var latinSplit = false
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--out-width" -> outWidth = args.getOrNull(++i)?.toIntOrNull() ?: outWidth
            "--target-em" -> targetEm = args.getOrNull(++i)?.toIntOrNull() ?: targetEm
            "--latin-split" -> latinSplit = true
            else -> if (!args[i].startsWith("--")) outDir = File(args[i])
        }
        i++
    }
    outDir.mkdirs()

    val source = ImageIO.read(input)
    if (source == null) {
        System.err.println("cannot decode image: $input")
        return
    }
    val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    val g = rgb.createGraphics()
    g.drawImage(source, 0, 0, null)
    g.dispose()

    val w = rgb.width
    val h = rgb.height
    val fullPixels = IntArray(w * h)
    rgb.getRGB(0, 0, w, h, fullPixels, 0, w)
    val bgArgb = borderColor(fullPixels, w, h)

    // 分析图长边 ≤ 1600，与 Android 侧一致；渲染仍用原图。
    val maxSide = maxOf(w, h)
    val k = if (maxSide > 1600) 1600f / maxSide else 1f
    val aw = maxOf(1, (w * k).roundToInt())
    val ah = maxOf(1, (h * k).roundToInt())
    val analysisPixels = if (k < 1f) {
        val scaled = BufferedImage(aw, ah, BufferedImage.TYPE_INT_RGB)
        val sg = scaled.createGraphics()
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        sg.drawImage(rgb, 0, 0, aw, ah, null)
        sg.dispose()
        IntArray(aw * ah).also { scaled.getRGB(0, 0, aw, ah, it, 0, aw) }
    } else {
        fullPixels
    }
    val gray = Grayscale.fromArgb(aw, ah, analysisPixels, bgArgb)

    val analysisOptions = AnalysisOptions(latinSplitEnabled = latinSplit)
    val t0 = System.nanoTime()
    val layout = LayoutAnalyzer(analysisOptions).analyze(gray, w, h)
    val t1 = System.nanoTime()
    val planOptions = ReflowOptions(outputWidth = outWidth, targetEm = targetEm)
    val plan = ReflowPlanner().plan(layout, planOptions)
    val t2 = System.nanoTime()
    val audit = PlanAuditor.audit(layout, plan)

    if (plan.mode == PlanMode.ORIGINAL) {
        ImageIO.write(rgb, "png", File(outDir, "after.png"))
    } else {
        ImageIO.write(render(rgb, layout, plan), "png", File(outDir, "after.png"))
    }
    ImageIO.write(debugOverlay(rgb, layout), "png", File(outDir, "debug.png"))
    File(outDir, "plan.json").writeText(
        planJson(
            name = input.name,
            sha256 = sha256(input),
            w = w,
            h = h,
            layout = layout,
            plan = plan,
            audit = audit,
            options = planOptions,
            analysisOptions = analysisOptions,
            analyzeNanos = t1 - t0,
            planNanos = t2 - t1,
        ),
    )

    println(
        "mode=${plan.mode} fallback=${plan.fallback} lines=${layout.textLines().size} " +
            "figures=${layout.blocks.count { it is Block.Figure }} cut=${layout.splitX} " +
            "scale=${"%.3f".format(plan.scale)} items=${plan.items.size} " +
            "output=${plan.outputWidth}x${plan.height} " +
            "analyze=${(t1 - t0) / 1_000_000}ms plan=${(t2 - t1) / 1_000_000}ms",
    )
    println("audit ${audit.summary()}")
}

/** 把若干张 after.png 横向拼成一张复核图（脚本用）。 */
private fun summary(args: List<String>) {
    if (args.size < 2) {
        System.err.println("usage: summary <out.png> <in1.png> <in2.png> ...")
        return
    }
    val out = File(args[0])
    val images = args.drop(1).mapNotNull { path ->
        val f = File(path)
        if (!f.isFile) {
            System.err.println("skip missing: $path")
            null
        } else {
            ImageIO.read(f)
        }
    }
    if (images.isEmpty()) {
        System.err.println("no input images")
        return
    }
    val tileW = 540
    val gap = 16
    val heights = images.map { (it.height.toFloat() * tileW / it.width).roundToInt() }
    val canvasH = heights.max().coerceAtLeast(1)
    val canvasW = images.size * tileW + (images.size + 1) * gap
    val canvas = BufferedImage(canvasW, canvasH + 2 * gap, BufferedImage.TYPE_INT_RGB)
    val g = canvas.createGraphics()
    g.color = Color(0xF2, 0xF2, 0xF2)
    g.fillRect(0, 0, canvas.width, canvas.height)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    var x = gap
    for ((index, img) in images.withIndex()) {
        g.drawImage(img, x, gap, tileW, heights[index], null)
        x += tileW + gap
    }
    g.dispose()
    out.parentFile?.mkdirs()
    ImageIO.write(canvas, "png", out)
    println("summary ${out.path} ${canvas.width}x${canvas.height}")
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun render(source: BufferedImage, layout: PageLayout, plan: ReflowPlan): BufferedImage {
    val out = BufferedImage(plan.outputWidth, maxOf(1, plan.height), BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.color = Color(layout.backgroundColor, true)
    g.fillRect(0, 0, out.width, out.height)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    for (item in plan.items) {
        val src = item.src
        if (src.isEmpty || item.dest.isEmpty) continue
        g.drawImage(
            source,
            item.dest.left,
            item.dest.top,
            item.dest.right,
            item.dest.bottom,
            src.left,
            src.top,
            src.right,
            src.bottom,
            null,
        )
    }
    g.dispose()
    return out
}

/** 红=BODY，洋红=HEADING，蓝=CAPTION，灰=UI；橙=图块，绿色短线=实际切点。 */
private fun debugOverlay(source: BufferedImage, layout: PageLayout): BufferedImage {
    val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.drawImage(source, 0, 0, null)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    for (line in layout.textLines()) {
        val r = layout.toSource(line.band)
        val c = when (line.role) {
            LineRole.BODY -> Color(220, 40, 40, 45)
            LineRole.HEADING -> Color(200, 0, 160, 55)
            LineRole.CAPTION -> Color(30, 90, 220, 45)
            LineRole.UI -> Color(90, 90, 90, 55)
        }
        g.color = c
        g.fillRect(r.left, r.top, r.width, r.height)
    }
    for (block in layout.blocks) {
        if (block !is Block.Figure) continue
        val r = layout.toSource(block.bounds)
        g.color = if (block.kind == FigureKind.PHOTO) {
            Color(240, 120, 0, 60)
        } else {
            Color(150, 60, 200, 60)
        }
        g.fillRect(r.left, r.top, r.width, r.height)
        g.color = if (block.kind == FigureKind.PHOTO) Color(240, 120, 0) else Color(150, 60, 200)
        g.stroke = BasicStroke(2f)
        g.drawRect(r.left, r.top, r.width, r.height)
    }
    if (layout.splitX > 0) {
        val sx = (layout.splitX / layout.analysisScale).roundToInt()
        g.color = Color(0, 160, 220)
        g.stroke = BasicStroke(3f)
        g.drawLine(sx, 0, sx, source.height)
        g.color = Color(0, 170, 90)
        g.stroke = BasicStroke(2f)
        for (line in layout.textLines()) {
            if (!line.split) continue
            val x = (line.cutX / layout.analysisScale).roundToInt()
            val r = layout.toSource(line.band)
            g.drawLine(x, r.top, x, r.bottom)
        }
    }
    g.dispose()
    return out
}

private fun borderColor(pixels: IntArray, w: Int, h: Int): Int {
    val rs = ArrayList<Int>(2 * (w + h))
    val gs = ArrayList<Int>(2 * (w + h))
    val bs = ArrayList<Int>(2 * (w + h))
    fun add(c: Int) {
        rs.add((c ushr 16) and 0xFF)
        gs.add((c ushr 8) and 0xFF)
        bs.add(c and 0xFF)
    }
    for (x in 0 until w) {
        add(pixels[x])
        add(pixels[(h - 1) * w + x])
    }
    for (y in 0 until h) {
        add(pixels[y * w])
        add(pixels[y * w + w - 1])
    }
    if (rs.isEmpty()) return -1
    rs.sort()
    gs.sort()
    bs.sort()
    val m = rs.size / 2
    return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
}

private fun planJson(
    name: String,
    sha256: String,
    w: Int,
    h: Int,
    layout: PageLayout,
    plan: ReflowPlan,
    audit: PlanAuditReport,
    options: ReflowOptions,
    analysisOptions: AnalysisOptions,
    analyzeNanos: Long,
    planNanos: Long,
): String {
    val sb = StringBuilder()
    fun line(body: String, last: Boolean = false) {
        sb.append("  ").append(body)
        if (!last) sb.append(',')
        sb.append('\n')
    }
    sb.append("{\n")
    line("\"file\": \"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    line("\"sourceSha256\": \"$sha256\"")
    line("\"src\": [$w, $h]")
    line("\"analysis\": [${(w * layout.analysisScale).roundToInt()}, ${(h * layout.analysisScale).roundToInt()}]")
    line("\"lines\": ${layout.textLines().size}")
    line("\"figures\": ${layout.blocks.count { it is Block.Figure }}")
    line("\"splitX\": ${layout.splitX}")
    line("\"cleanRatio\": ${"%.3f".format(layout.cleanRatio)}")
    line("\"scale\": ${"%.3f".format(plan.scale)}")
    line("\"bodyScale\": ${"%.3f".format(plan.bodyScale)}")
    line("\"gain\": ${"%.3f".format(plan.gain)}")
    line("\"mode\": \"${plan.mode.name}\"")
    line("\"fallback\": \"${plan.fallback.name}\"")
    line("\"items\": ${plan.items.size}")
    line("\"itemsDropped\": ${audit.blocksWithoutItem}")
    line("\"height\": ${plan.height}")
    line("\"outputWidth\": ${plan.outputWidth}")
    line("\"textEm\": ${options.targetEm}")
    line("\"options\": {\"outputWidth\": ${options.outputWidth}, \"targetEm\": ${options.targetEm}, " +
        "\"minGain\": ${options.minGain}, \"maxScale\": ${options.maxScale}, " +
        "\"splitEnabled\": ${options.splitEnabled}, \"uiMaxScale\": ${options.uiMaxScale}, " +
        "\"latinSplit\": ${analysisOptions.latinSplitEnabled}}")
    line("\"blockCounts\": ${jsonMap(layout.blocks.groupingBy { blockKind(it) }.eachCount())}")
    line("\"figureReasons\": ${jsonMap(layout.figureKindCounts().mapKeys { it.key.name })}")
    line("\"roles\": ${jsonMap(layout.roleCounts().mapKeys { it.key.name })}")
    line("\"audit\": {\"ok\": ${audit.ok}, \"bodyScaleDeviation\": ${"%.3f".format(audit.bodyScaleDeviation)}, " +
        "\"uiMaxScale\": ${"%.2f".format(audit.uiMaxScale)}, " +
        "\"headingHeightRatio\": ${"%.2f".format(audit.headingHeightRatio)}, " +
        "\"itemsOutOfBounds\": ${audit.itemsOutOfBounds}, \"nonMonotonic\": ${audit.nonMonotonic}, " +
        "\"issues\": [${audit.issues.joinToString(", ") { "\"" + it.replace("\"", "\\\"") + "\"" }}]}")
    line("\"analyzeMs\": ${"%.1f".format(analyzeNanos / 1_000_000.0)}")
    line("\"planMs\": ${"%.1f".format(planNanos / 1_000_000.0)}")
    sb.append("  \"itemsDetail\": [\n")
    for ((i, item) in plan.items.withIndex()) {
        val kind = when (item) {
            is RenderItem.TextRow -> "text"
            is RenderItem.Figure -> "figure"
        }
        val role = when (item) {
            is RenderItem.TextRow -> item.role.name
            is RenderItem.Figure -> item.kind.name
        }
        val scale = if (item.src.width > 0) item.dest.width.toFloat() / item.src.width else 1f
        sb.append("    {\"kind\":\"$kind\",\"role\":\"$role\",\"scale\":${"%.3f".format(scale)},")
        sb.append("\"src\":[${item.src.left},${item.src.top},${item.src.right},${item.src.bottom}],")
        sb.append("\"dest\":[${item.dest.left},${item.dest.top},${item.dest.right},${item.dest.bottom}]}")
        if (i != plan.items.lastIndex) sb.append(",")
        sb.append("\n")
    }
    sb.append("  ],\n")
    sb.append("  \"notes\": [")
    sb.append(plan.notes.joinToString(", ") { "\"${it.replace("\"", "\\\"")}\"" })
    sb.append("]\n}\n")
    return sb.toString()
}

private fun blockKind(block: Block): String = when (block) {
    is Block.Paragraph -> "paragraph"
    is Block.Figure -> "figure_${block.kind.name.lowercase()}"
}

private fun jsonMap(map: Map<*, Int>): String =
    map.entries.joinToString(", ", "{", "}") { "\"${it.key}\": ${it.value}" }
