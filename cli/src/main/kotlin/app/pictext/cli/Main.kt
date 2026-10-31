package app.pictext.cli

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.AnalysisScale
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.layout.GrayPageRegionSampler
import app.pictext.core.layout.GrayPageTileSampler
import app.pictext.core.layout.LayoutDetector
import app.pictext.core.layout.LayoutOptions
import app.pictext.core.layout.LayoutOutcome
import app.pictext.core.layout.ModelContract
import app.pictext.core.layout.PageContext
import app.pictext.core.model.Block
import app.pictext.core.model.FigureKind
import app.pictext.core.model.GrayImage
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.plan.PlanAuditReport
import app.pictext.core.plan.PlanAuditor
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import app.pictext.core.plan.SmartReflow
import app.pictext.core.plan.SmartReflowResult
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
 * ./gradlew :cli:run --args="in.png out/ --out-width 1080 --layout --detections boxes.txt --dump-regions"
 * ./gradlew :cli:run --args="in.png out/ --out-width 1080 --compare --detections boxes.txt"
 * ./gradlew :cli:run --args="summary out/summary.png a.png b.png c.png"
 * ```
 *
 * v2（`--layout`）需要一个检测器。在 ORT 适配器接上之前（docs/07 V2-12 的后半段），
 * 用 `--detections <file>` 喂已知框：每行 `label score x1 y1 x2 y2`（S 坐标，`#` 注释）。
 * 这样区域合并、细化、阅读顺序、覆盖审计、规划、审计整条链路都能在没有模型时跑通。
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }
    val memory = PeakMemory.start()
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
    var x4 = false
    var dumpLines = false
    var layoutMode = false
    var dumpRegions = false
    var compare = false
    var detectionsFile: File? = null
    var modelFile: File? = null
    var minCoverage: Float? = null
    var residualFail: Float? = null
    var scoreThreshold: Float? = null
    var auditDownsample: Int? = null
    var figureFullRes = false
    var serialRefine = false
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--out-width" -> outWidth = args.getOrNull(++i)?.toIntOrNull() ?: outWidth
            "--target-em" -> targetEm = args.getOrNull(++i)?.toIntOrNull() ?: targetEm
            "--latin-split" -> latinSplit = true
            "--x4" -> x4 = true
            "--dump-lines" -> dumpLines = true
            "--layout" -> layoutMode = true
            "--dump-regions" -> dumpRegions = true
            "--compare" -> compare = true
            "--detections" -> detectionsFile = args.getOrNull(++i)?.let { File(it) }
            "--model" -> modelFile = args.getOrNull(++i)?.let { File(it) }
            // 调参用：V2-13 要量“模型到底覆盖了多少墨迹”，这两个旋钮能把回退闸门临时放开。
            "--min-coverage" -> minCoverage = args.getOrNull(++i)?.toFloatOrNull()
            "--residual-fail" -> residualFail = args.getOrNull(++i)?.toFloatOrNull()
            "--score" -> scoreThreshold = args.getOrNull(++i)?.toFloatOrNull()
            "--audit-downsample" -> auditDownsample = args.getOrNull(++i)?.toIntOrNull()
            "--figure-full-res" -> figureFullRes = true
            "--serial-refine" -> serialRefine = true
            else -> if (!args[i].startsWith("--")) outDir = File(args[i])
        }
        i++
    }
    if ((layoutMode || compare) && detectionsFile == null && modelFile == null) {
        System.err.println(
            "--layout/--compare 需要检测器：--model models/doclayout/PP-DocLayout-S.onnx，" +
                "或 --detections <file>（无模型时回放已知框）。\n" +
                "detections 格式：每行 `label score x1 y1 x2 y2`，S 坐标，# 开头是注释。",
        )
        return
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

    // 分析图宽度优先（与 Android 侧共用 AnalysisScale）：长图不再按长边压成一条；
    // 像素超预算时由 LayoutAnalyzer 内部切条带。渲染仍用原图。
    val analysisOptions = AnalysisOptions(latinSplitEnabled = latinSplit)
    val analysisSize = AnalysisScale.size(w, h, analysisOptions)
    val aw = analysisSize.width
    val ah = analysisSize.height
    val k = analysisSize.factor
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

    val planOptions = if (x4) {
        ReflowOptions(
            outputWidth = outWidth,
            targetEm = targetEm,
            forceReflow = true,
            scaleMultiplier = 2f,
            maxSegments = 4,
        )
    } else {
        ReflowOptions(outputWidth = outWidth, targetEm = targetEm)
    }

    val t0 = System.nanoTime()
    val v2: SmartReflowResult?
    val layout: PageLayout
    val plan: ReflowPlan
    val audit: PlanAuditReport
    if (layoutMode || compare) {
        val detector: LayoutDetector = if (modelFile != null) {
            DesktopLayoutDetector(modelFile!!).also {
                println("model ${modelFile!!.path} ${it.describe()}")
            }
        } else {
            val (boxes, errors) = parseDetections(detectionsFile!!)
            for (e in errors) System.err.println("detections: $e")
            if (boxes.isEmpty()) System.err.println("detections: no usable boxes in ${detectionsFile!!.path}")
            ScriptedLayoutDetector(ModelContract().modelInput, boxes)
        }
        try {
            v2 = runV2(
                rgb = rgb,
                analysis = gray,
                bgArgb = bgArgb,
                w = w,
                h = h,
                planOptions = planOptions,
                analysisOptions = analysisOptions,
                detector = detector,
                dumpLines = dumpLines,
                layoutOptions = LayoutOptions(
                    minCoverage = minCoverage ?: LayoutOptions().minCoverage,
                    residualFailRatio = residualFail ?: LayoutOptions().residualFailRatio,
                    scoreThreshold = scoreThreshold ?: LayoutOptions().scoreThreshold,
                    auditDownsample = auditDownsample ?: LayoutOptions().auditDownsample,
                    // 调参/基准用：恢复 FIGURE 全分辨率细化，便于量 V2-50 的收益。
                    refineFigureMaxWidth = if (figureFullRes) {
                        LayoutOptions().refineMaxWidth
                    } else {
                        LayoutOptions().refineFigureMaxWidth
                    },
                    refineFigureMaxPixels = if (figureFullRes) {
                        LayoutOptions().refineMaxPixels
                    } else {
                        LayoutOptions().refineFigureMaxPixels
                    },
                    parallelRefine = !serialRefine,
                ),
            )
            layout = v2.layout
            plan = v2.plan
            audit = v2.projectionAudit
        } finally {
            detector.close()
        }
    } else {
        v2 = null
        layout = LayoutAnalyzer(analysisOptions).analyze(gray, w, h)
        plan = ReflowPlanner().plan(layout, planOptions)
        audit = PlanAuditor.audit(layout, plan)
    }
    val t1 = System.nanoTime()
    val peakMb = memory.stopAndPeakMb()

    if (dumpLines) {
        for ((index, line) in layout.textLines().withIndex()) {
            println(
                "line[$index] role=${line.role} band=${line.band} cutX=${line.cutX} " +
                    "split=${line.split} centered=${line.centered} cuts=${line.cuts} " +
                    "sensitive=${line.sensitive}",
            )
        }
    }

    // --compare：同图再跑一次 v1（或 v2），两张 after 并排看差异。
    if (compare) {
        val v1Layout = LayoutAnalyzer(analysisOptions).analyze(gray, w, h)
        val v1Plan = ReflowPlanner().plan(v1Layout, planOptions)
        val v1File = File(outDir, "after-v1.png")
        val v2File = File(outDir, "after-v2.png")
        writeAfter(v1File, rgb, v1Layout, v1Plan)
        writeAfter(v2File, rgb, layout, plan)
        // V2-33：并排对比图（左 v1 / 右 v2），比来回切两张图快得多。
        val compareFile = File(outDir, "compare.png")
        summary(listOf(compareFile.path, v1File.path, v2File.path))
        File(outDir, "stats.json").writeText(
            "{\n" +
                "  \"v1\": {\"mode\":\"${v1Plan.mode}\",\"lines\":${v1Layout.textLines().size}," +
                "\"scale\":${"%.3f".format(v1Plan.scale)},\"height\":${v1Plan.height}},\n" +
                "  \"v2\": {\"mode\":\"${plan.mode}\",\"lines\":${layout.regionLines().size}," +
                "\"scale\":${"%.3f".format(plan.bodyScale)},\"height\":${plan.height}},\n" +
                "  \"fallback\": \"${v2?.fallbackCode ?: "none"}\"\n}\n",
        )
        println("compare v1(mode=${v1Plan.mode} h=${v1Plan.height}) vs v2(mode=${plan.mode} h=${plan.height})")
    }

    if (plan.mode == PlanMode.ORIGINAL) {
        ImageIO.write(rgb, "png", File(outDir, "after.png"))
    } else {
        ImageIO.write(render(rgb, layout, plan), "png", File(outDir, "after.png"))
    }
    val debug = if (v2 != null && v2.layoutAudit != null) {
        v2DebugOverlay(rgb, layout)
    } else {
        debugOverlay(rgb, layout)
    }
    ImageIO.write(debug, "png", File(outDir, "debug.png"))
    if (dumpRegions && v2 != null) {
        File(outDir, "regions.json").writeText(regionsJson(v2, v2.layoutStats?.summary))
    }
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
            planNanos = t1 - t0,
            peakMemMb = peakMb,
            v2 = v2,
        ),
    )

    println(
        "mode=${plan.mode} fallback=${plan.fallback} lines=${layout.textLines().size} " +
            "figures=${layout.blocks.count { it is Block.Figure }} cut=${layout.splitX} " +
            "scale=${"%.3f".format(plan.scale)} lineH=${"%.1f".format(layout.medianLineHeight)} " +
            "effPx=${"%.1f".format(plan.scale * layout.medianLineHeight)} items=${plan.items.size} " +
            "output=${plan.outputWidth}x${plan.height} " +
            "analyze=${(t1 - t0) / 1_000_000}ms peakMem=${peakMb}MB",
    )
    if (v2 != null) {
        println(summaryLine(v2))
        if (v2.fallbackCode != null) println("v2 notes: ${v2.plan.notes.joinToString(" | ")}")
    }
    println("audit ${audit.summary()}")
    v2?.layoutAudit?.let { println("auditV2 ${it.summary()}") }
}

private fun printUsage() {
    System.err.println(
        "usage: pictext-cli <input-image> [out-dir] [--out-width N] [--target-em N] " +
            "[--latin-split] [--x4] [--dump-lines] [--layout] [--compare] " +
            "[--detections FILE] [--dump-regions]",
    )
    System.err.println("       pictext-cli summary <out.png> <a.png> <b.png> ...")
}

/** 跑 v2 链路：全分辨率灰度只解一次，tile/ROI 都从它采样（桌面内存换简洁）。 */
private fun runV2(
    rgb: BufferedImage,
    analysis: GrayImage,
    bgArgb: Int,
    w: Int,
    h: Int,
    planOptions: ReflowOptions,
    analysisOptions: AnalysisOptions,
    detector: LayoutDetector,
    dumpLines: Boolean,
    layoutOptions: LayoutOptions = LayoutOptions(),
): SmartReflowResult {
    val pageGray = grayFromImage(rgb, bgArgb)
    val page = PageContext(
        srcWidth = w,
        srcHeight = h,
        backgroundColor = bgArgb,
        isDark = luminance(bgArgb) < 110,
    )
    val result = SmartReflow(analysisOptions = analysisOptions, layoutOptions = layoutOptions).run(
        analysis = analysis,
        page = page,
        planOptions = planOptions,
        sampler = GrayPageTileSampler(pageGray),
        regionSampler = GrayPageRegionSampler(pageGray),
        detector = detector,
        onTile = { done, total -> if (dumpLines) println("tile $done/$total") },
    )
    (result.v2Outcome as? LayoutOutcome.Fallback)?.let {
        println(
            "v2 ${it.code} tiles=${it.tiles} dets=${it.detections} regions=${it.regions} " +
                "coverage=${"%.4f".format(it.coverage)}",
        )
        for (tc in it.tilesReport) {
            println(
                "  tile#${tc.tileIndex} ink=${tc.ink} covered=${tc.covered} " +
                    "ratio=${"%.4f".format(tc.ratio)} residual=${tc.residuals.size} " +
                    "boxes=${tc.residuals.take(3).joinToString(" ") { r -> "${r.bounds}:${r.ink}" }}",
            )
        }
    }
    return result
}

/** 逐行转换，避免为 1080×20000 的长图再分配一份 86MB 的 IntArray。 */
private fun grayFromImage(img: BufferedImage, backgroundColor: Int): GrayImage {
    val w = img.width
    val h = img.height
    val data = ByteArray(w * h)
    val row = IntArray(w)
    for (y in 0 until h) {
        img.getRGB(0, y, w, 1, row, 0, w)
        val base = y * w
        for (x in 0 until w) {
            val c = row[x]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            data[base + x] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
        }
    }
    return GrayImage(w, h, data, backgroundColor)
}

private fun luminance(argb: Int): Int {
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return (77 * r + 150 * g + 29 * b) shr 8
}

private fun writeAfter(file: File, source: BufferedImage, layout: PageLayout, plan: ReflowPlan) {
    if (plan.mode == PlanMode.ORIGINAL) {
        ImageIO.write(source, "png", file)
    } else {
        ImageIO.write(render(source, layout, plan), "png", file)
    }
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
    peakMemMb: Long,
    v2: SmartReflowResult?,
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
    line(
        "\"options\": {\"outputWidth\": ${options.outputWidth}, \"targetEm\": ${options.targetEm}, " +
            "\"minGain\": ${options.minGain}, \"maxScale\": ${options.maxScale}, " +
            "\"splitEnabled\": ${options.splitEnabled}, \"uiMaxScale\": ${options.uiMaxScale}, " +
            "\"latinSplit\": ${analysisOptions.latinSplitEnabled}}",
    )
    line("\"blockCounts\": ${jsonMap(layout.blocks.groupingBy { blockKind(it) }.eachCount())}")
    line("\"figureReasons\": ${jsonMap(layout.figureKindCounts().mapKeys { it.key.name })}")
    line("\"roles\": ${jsonMap(layout.roleCounts().mapKeys { it.key.name })}")
    line(
        "\"audit\": {\"ok\": ${audit.ok}, \"bodyScaleDeviation\": ${"%.3f".format(audit.bodyScaleDeviation)}, " +
            "\"uiMaxScale\": ${"%.2f".format(audit.uiMaxScale)}, " +
            "\"headingHeightRatio\": ${"%.2f".format(audit.headingHeightRatio)}, " +
            "\"itemsOutOfBounds\": ${audit.itemsOutOfBounds}, \"nonMonotonic\": ${audit.nonMonotonic}, " +
            "\"issues\": [${audit.issues.joinToString(", ") { "\"" + it.replace("\"", "\\\"") + "\"" }}]}",
    )
    if (v2 != null) {
        sb.append("  ").append(layoutSummaryJson(v2).trimEnd('\n').replace("\n", "\n  ")).append('\n')
    }
    line("\"analyzeMs\": ${"%.1f".format(analyzeNanos / 1_000_000.0)}")
    line("\"planMs\": ${"%.1f".format(planNanos / 1_000_000.0)}")
    line("\"peakMemMb\": $peakMemMb")
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
        if (item is RenderItem.TextRow) {
            // 引号包住 "0/1"：不带引号的 0/1 不是合法 JSON（ConvertFrom-Json 会整份拒绝）。
            sb.append("\"region\":${item.regionIndex},\"seg\":\"${item.segmentIndex}/")
            sb.append("${item.segments}\",\"cut\":\"${item.cut}\",")
        }
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

/**
 * 峰值内存采样（V2-40 验收用）。
 *
 * `Runtime.totalMemory() - freeMemory()` 只是 JVM 堆的“当前已用”，GC 之后会掉下来；
 * 单次读一次会严重低估峰值，所以用一个 20ms 的守护线程持续采样取最大值。
 * 不包含 native（ORT）内存，CLI 侧用于对比“改动前后/不同页面尺寸”，
 * Android 侧要用 `Debug.getMemoryInfo` 另行实测。
 */
private class PeakMemory private constructor() {
    @Volatile private var peak = 0L
    @Volatile private var running = true
    private val thread = Thread {
        val rt = Runtime.getRuntime()
        while (running) {
            val used = rt.totalMemory() - rt.freeMemory()
            if (used > peak) peak = used
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return@Thread
            }
        }
    }.also {
        it.isDaemon = true
        it.name = "pictext-peak-memory"
        it.start()
    }

    fun stopAndPeakMb(): Long {
        running = false
        thread.interrupt()
        thread.join(200)
        return peak / (1024 * 1024)
    }

    companion object {
        fun start(): PeakMemory = PeakMemory()
    }
}

