package app.pictext.core.layout

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.model.GrayImage
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.PageLayout
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RegionSource
import kotlin.math.max

/** 版式分析的结论：要么得到一页可用版面，要么给出回退码。 */
sealed interface LayoutOutcome {
    data class Success(
        val layout: PageLayout,
        val coverage: CoverageReport,
        val summary: LayoutSummaryStats,
    ) : LayoutOutcome

    /**
     * @param code docs/07 §4.9 的诊断码，例如 `layout_too_many_tiles`。
     * @param useOriginal true 表示“连 v1 也不该重排”，直接看原图（竖排场景）。
     * @param tilesReport 每个 tile 的墨迹/覆盖统计，回退时也能看出“哪一块没被覆盖”。
     */
    data class Fallback(
        val code: String,
        val useOriginal: Boolean = false,
        val tiles: Int = 0,
        val detections: Int = 0,
        val regions: Int = 0,
        val coverage: Float = 0f,
        val tilesReport: List<TileCoverage> = emptyList(),
    ) : LayoutOutcome
}

/** 管线统计，供 CLI 的 `plan.json` 与调试输出用。 */
data class LayoutSummaryStats(
    val tiles: Int,
    val detections: Int,
    val regions: Int,
    val residuals: Int,
    val coverage: Float,
    val orderAmbiguous: Boolean,
    val verticalRatio: Float,
    val maxScore: Float,
    /** 深色页是否把模型输入反相了（V2-45）。 */
    val inverted: Boolean = false,

    /** 文字类区域覆盖的页面面积占比（V2-43 信任度信号）。 */
    val textAreaRatio: Float = 0f,
    /** 墨迹落在文字类区域里的比例（V2-43 信任度信号）。 */
    val inkInTextRatio: Float = 0f,
    /** 非文字类区域里细化出的行 / 全页行（V2-43：文字被框成图片）。 */
    val blockLineRatio: Float = 0f,
    /** 文字类区域里细化不出行的比例（V2-43：框落在空白处）。 */
    val emptyTextRegionRatio: Float = 0f,
    /** 解码 + 预处理（tile 采样、缩放、归一化）。 */
    val decodeMs: Long = 0,
    /** 推理（ORT session.run 累计）。 */
    val detectMs: Long = 0,
    /** 区域合并 + ROI 细化 + 二次判定。 */
    val refineMs: Long = 0,
    /** 覆盖审计（含残差补区的二次审计）。 */
    val coverageMs: Long = 0,
    /** 阅读顺序（XY-cut）。 */
    val orderMs: Long = 0,
) {
    /** 一行看清性能瓶颈在哪。 */
    fun stageSummary(): String =
        "decode=${decodeMs}ms detect=${detectMs}ms refine=${refineMs}ms " +
            "coverage=${coverageMs}ms order=${orderMs}ms"
}

/**
 * v2 版式分析管线（docs/07 §3.1 的那张流程图）。
 *
 * ```text
 * tile 网格 → 采样/预处理 → 检测器 → NMS/坐标映射 → 区域合并 → 细化 → 二次判定
 *          → 覆盖审计 + 残差补区 → 阅读顺序 → 组装 PageLayout
 * ```
 *
 * 三条硬约束：
 * 1. **纯 Kotlin**：检测器是接口，ORT/Android 实现只在 `:app`/`:cli`；
 * 2. **模型框只当 ROI**：渲染位置一律来自细化后的行带，不用模型框裁像素；
 * 3. **漏检补区**：覆盖不足就补 `RESIDUAL` 区域，补不回来就回退（绝不丢内容）。
 *
 * 内存：每 tile 的模型尺寸灰度（480² = 230KB）会被保留到审计阶段，
 * 20 个 tile 约 4.6MB；这是“审计必须看到模型看到的像素”的代价，仍在预算内。
 */
class LayoutPipeline(
    private val options: LayoutOptions = LayoutOptions(),
    private val analysisOptions: AnalysisOptions = AnalysisOptions(),
    private val contract: ModelContract = ModelContract(),
) {
    private val tiler = LayoutTiler(options)
    private val labels = ModelLabels(contract.labels)
    private val preprocessor = LayoutPreprocessor(contract)
    private val postprocessor = DetectionPostprocessor(options, labels)
    private val builder = RegionBuilder(options, labels)
    // 细化用一份“v2 专用”的 v1 参数副本：v1 默认值一个字都不动，只放宽装饰列宽度上限
    // （v1 是按页宽算的 0.5%，在 ROI 上会漏掉引用块的 6px 左边线，把 3 行文字粘成一条）。
    private val refiner = RegionRefiner(
        options,
        analysisOptions.copy(
            decorMaxWidthRatio = maxOf(
                analysisOptions.decorMaxWidthRatio,
                options.refineDecorMaxWidthRatio,
            ),
        ),
    )
    private val fixer = RegionKindFixer(options)
    private val deduper = RegionDeduper()
    private val auditor = CoverageAuditor(options, options.auditDownsample)
    private val orderer = ReadingOrder(options)
    private val composer = PageComposer()

    /**
     * @param onTile 每个 tile 完成后回调 `(已完成, 总数)`，App 用它显示“正在识别版式 3/12”。
     */
    fun analyze(
        sampler: TileSampler,
        detector: LayoutDetector,
        regionSampler: RegionSampler,
        page: PageContext,
        onTile: ((Int, Int) -> Unit)? = null,
    ): LayoutOutcome {
        require(detector.modelInputSize == contract.modelInput) {
            "detector input ${detector.modelInputSize} != contract ${contract.modelInput}"
        }
        val grid = tiler.grid(page.srcWidth, page.srcHeight)
        if (grid.count > options.maxTiles) {
            return LayoutOutcome.Fallback("layout_too_many_tiles", tiles = grid.count)
        }

        val model = contract.modelInput
        val input = LayoutInput(model, model, 3)
        val gray = ByteArray(model * model)
        val mapped = ArrayList<MappedDetection>(64)
        val tileGrays = ArrayList<Pair<Tile, GrayImage>>(grid.count)
        var maxScore = 0f
        var decodeNanos = 0L
        var detectNanos = 0L

        // 深色页（V2-45）：模型在浅底文档上训练，深底浅字属于分布外。反相只作用在模型输入上，
        // 覆盖审计/细化仍然吃原图像素（`grayOut` 不受影响），所以坐标与墨迹语义都不变。
        val invert = page.isDark || luminanceOf(page.backgroundColor) < DARK_LUMA

        for (tile in grid.tiles) {
            val t0 = System.nanoTime()
            val argb = sampler.sample(tile.src, tile.innerWidth, tile.innerHeight, page.backgroundColor)
            preprocessor.process(argb, tile.innerWidth, tile.innerHeight, tile, input, gray, invert)
            val t1 = System.nanoTime()
            val detections = detector.detect(input, tile)
            val t2 = System.nanoTime()
            decodeNanos += t1 - t0
            detectNanos += t2 - t1
            for (d in detections) if (d.score > maxScore) maxScore = d.score
            mapped.addAll(postprocessor.postprocess(tile, detections, page.size))
            tileGrays.add(tile to GrayImage(model, model, gray.copyOf(model * model), page.backgroundColor))
            onTile?.invoke(tile.index + 1, grid.count)
        }

        // 一个框都没有：模型可能彻底没适配这一页，交给 v1。
        if (mapped.isEmpty() || maxScore < MIN_USABLE_SCORE) {
            return LayoutOutcome.Fallback(
                "layout_no_detection",
                tiles = grid.count,
                detections = mapped.size,
            )
        }

        // 区域 + 细化 + 二次判定。
        val tRefine = System.nanoTime()
        val built = builder.build(mapped, page.size)
        // V2-50：区域之间互相独立，细化是纯 CPU；按区域并行能把 refine 段（1080×2400
        // 模拟器 ~1s）压到核数量级。`parallelStream().collect` 保持 encounter order，
        // 输出的区域/行顺序与串行完全一致，不确定性的来源仍是投影本身。
        val fixed = if (options.parallelRefine && built.size >= PARALLEL_REFINE_MIN_REGIONS) {
            built.parallelStream()
                .map { r -> fixer.fix(r, refiner.refine(regionSampler, page, r)) }
                .collect(java.util.stream.Collectors.toList())
        } else {
            built.map { r -> fixer.fix(r, refiner.refine(regionSampler, page, r)) }
        }
        var regions = fixed.map { r -> r.copy(lines = fixer.normalizeRoles(r)) }
        val refineMs = (System.nanoTime() - tRefine) / 1_000_000

        val textual = regions.count { it.isTextual && it.lines.isNotEmpty() }
        if (regions.size < 2 || textual == 0) {
            return LayoutOutcome.Fallback(
                "layout_too_few_regions",
                tiles = grid.count,
                detections = mapped.size,
                regions = regions.size,
            )
        }

        // 第一遍覆盖审计。
        //
        // **顺序上的一个刻意改动**（docs/07 §13.2）：原文是“残差 >5% 就直接回退 v1”，
        // 但现实中一个被漏掉的大图块（合成页的占位图、纯色卡片）就足以把单页残差顶到 20%；
        // 而残差补区本来就是为这种情况设计的、且**绝不丢内容**（补不到行就整块保留像素）。
        // 所以这里改成：先补区，补完再看覆盖率；补区后仍不达标（或残差块过多）才回退。
        val tCoverage = System.nanoTime()
        // 墨迹掩膜只算一次：补区前后各审计一遍，但背景估计 + Otsu 与区域无关（V2-40）。
        val prepared = prepareAudit(tileGrays)
        val first = auditor.report(prepared.map { it.audit(regions) })
        if (first.residuals.size > options.maxResiduals) {
            return LayoutOutcome.Fallback(
                "layout_residual_high",
                tiles = grid.count,
                detections = mapped.size,
                regions = regions.size,
                coverage = first.coverage,
                tilesReport = first.tiles,
            )
        }

        // 残差补区：漏检的文字/图块必须被找回来，绝不丢弃。
        val residuals: List<LayoutRegion> = first.residuals.mapIndexed { i, res ->
            val seed = LayoutRegion(
                id = RESIDUAL_ID_BASE + i,
                kind = RegionKind.OTHER,
                bounds = res.bounds,
                source = RegionSource.RESIDUAL,
            )
            val rr = refiner.refine(regionSampler, page, seed)
            if (rr.lines.size >= options.residualMinLines) {
                seed.copy(kind = RegionKind.TEXT, lines = rr.lines)
            } else {
                seed.copy(kind = RegionKind.FIGURE)
            }
        }

        // 残差 ROI 会把“已被模型区域覆盖的行”重新切出来（外接框 + 外扩），两个重叠 tile
        // 也可能各补一块同一墨迹。做一次行带级/块级去重，否则 plan 里同一 src 会渲染多次（V2-48）。
        val all = deduper.dedupe(regions, residuals)
        val coverage = auditor.report(prepared.map { it.audit(all) })
        val coverageMs = (System.nanoTime() - tCoverage) / 1_000_000
        if (coverage.coverage < options.minCoverage) {
            val code = if (coverage.residualRatio > options.residualFailRatio) {
                "layout_residual_high"
            } else {
                "layout_low_coverage"
            }
            return LayoutOutcome.Fallback(
                code,
                tiles = grid.count,
                detections = mapped.size,
                regions = all.size,
                coverage = coverage.coverage,
                tilesReport = coverage.tiles,
            )
        }
        if (all.size > regions.size) {
            // 补过区：把“补了几块”写进诊断，CLI/plan.json 能看到而不是靠猜。
        }

        // 单一大文字块（整页段落）：模型没切出结构，v1 反而更稳。
        val pageArea = page.srcWidth.toLong() * page.srcHeight
        val wall = all.firstOrNull { it.kind == RegionKind.TEXT && it.isLargeWall(pageArea) }
        if (wall != null && all.none { it.kind == RegionKind.FIGURE }) {
            return LayoutOutcome.Fallback(
                "layout_single_wall",
                tiles = grid.count,
                detections = mapped.size,
                regions = all.size,
                coverage = coverage.coverage,
            )
        }

        val verticalRatio = fixer.verticalRatio(all)
        if (verticalRatio > options.verticalMaxRatio) {
            return LayoutOutcome.Fallback(
                "layout_vertical",
                useOriginal = true,
                tiles = grid.count,
                detections = mapped.size,
                regions = all.size,
            )
        }

        val tOrder = System.nanoTime()
        val ordered = orderer.order(all, medianLineHeight(all))
        val orderMs = (System.nanoTime() - tOrder) / 1_000_000
        val trust = trustSignal(page, prepared, all, coverage.ink)
        val notes = ArrayList<String>(7)
        notes.add("layout_model=${contract.name}")
        notes.add("layout_tiles_${grid.count}")
        if (invert) notes.add("layout_invert=1")
        notes.add("layout_coverage=${format(coverage.coverage)}")
        notes.add("layout_text_area=${format(trust.textAreaRatio)}")
        notes.add("layout_ink_in_text=${format(trust.inkInTextRatio)}")
        notes.add("layout_block_lines=${format(trust.blockLineRatio)}")
        notes.add("layout_empty_text_regions=${format(trust.emptyTextRegionRatio)}")
        if (residuals.isNotEmpty()) notes.add("layout_residuals=${residuals.size}")
        if (ordered.ambiguous) notes.add("layout_order_ambiguous")

        val layout = composer.compose(page, ordered.regions, coverage, notes)
        return LayoutOutcome.Success(
            layout = layout,
            coverage = coverage,
            summary = LayoutSummaryStats(
                tiles = grid.count,
                detections = mapped.size,
                regions = ordered.regions.size,
                residuals = residuals.size,
                coverage = coverage.coverage,
                orderAmbiguous = ordered.ambiguous,
                verticalRatio = verticalRatio,
                maxScore = maxScore,
                textAreaRatio = trust.textAreaRatio,
                inkInTextRatio = trust.inkInTextRatio,
                blockLineRatio = trust.blockLineRatio,
                emptyTextRegionRatio = trust.emptyTextRegionRatio,
                inverted = invert,
                decodeMs = decodeNanos / 1_000_000,
                detectMs = detectNanos / 1_000_000,
                refineMs = refineMs,
                coverageMs = coverageMs,
                orderMs = orderMs,
            ),
        )
    }

    /** 页面检测结果的可信度信号（V2-43）。 */
    private data class TrustSignal(
        val textAreaRatio: Float,
        val inkInTextRatio: Float,
        /** 细化在**非文字类**区域里找到的行数占全部行的比例：字被框成图的主要特征。 */
        val blockLineRatio: Float,
        /** 文字类区域里“一行都没细化出来”的比例：框落在空白处的主要特征。 */
        val emptyTextRegionRatio: Float,
    )

    /**
     * 算可信度信号（V2-43）。四个信号都不需要真值、不依赖 OCR：
     *
     * - `textAreaRatio`：文字类区域覆盖的页面面积；
     * - `inkInTextRatio`：墨迹落在文字类区域里的比例；
     * - `blockLineRatio`：**细化在 FIGURE/TABLE 里找到的行 / 全页行**——
     *   文字被框成图片时（聊天气泡、海报大字）这个值会明显抬起来；
     * - `emptyTextRegionRatio`：**文字类区域里细化不出行的比例**——框落在空白/装饰上时抬高。
     *
     * 前两个是“面积视角”，后两个是“细化结果视角”。实测前者分不开好坏页
     * （深色文章的 `inkInTextRatio` 高达 0.79 却仍漏检），判定只用后两个。
     */
    private fun trustSignal(
        page: PageContext,
        prepared: List<CoverageAuditor.PreparedTile>,
        regions: List<LayoutRegion>,
        totalInk: Int,
    ): TrustSignal {
        // 面积：32px 粗网格，避免为“统计”再光栅化一整页。
        val cell = 32
        val cols = (page.srcWidth + cell - 1) / cell
        val rows = (page.srcHeight + cell - 1) / cell
        val grid = BooleanArray(cols * rows)
        for (r in regions) {
            if (!r.isTextual) continue
            val x0 = (r.bounds.left / cell).coerceIn(0, cols - 1)
            val x1 = ((r.bounds.right - 1) / cell).coerceIn(0, cols - 1)
            val y0 = (r.bounds.top / cell).coerceIn(0, rows - 1)
            val y1 = ((r.bounds.bottom - 1) / cell).coerceIn(0, rows - 1)
            for (y in y0..y1) {
                val base = y * cols
                for (x in x0..x1) grid[base + x] = true
            }
        }
        val marked = grid.count { it }
        val textAreaRatio = if (grid.isEmpty()) 0f else marked.toFloat() / grid.size

        var inkInText = 0
        for (t in prepared) inkInText += t.inkIn(regions) { it.isTextual }
        val inkInTextRatio = if (totalInk <= 0) 0f else inkInText.toFloat() / totalInk

        var allLines = 0
        var blockLines = 0
        var textRegions = 0
        var emptyTextRegions = 0
        for (r in regions) {
            val n = r.lines.size
            allLines += n
            if (r.isTextual) {
                textRegions++
                if (n == 0) emptyTextRegions++
            } else {
                blockLines += n
            }
        }
        val blockLineRatio = if (allLines <= 0) 0f else blockLines.toFloat() / allLines
        val emptyTextRegionRatio = if (textRegions <= 0) 0f else emptyTextRegions.toFloat() / textRegions
        return TrustSignal(textAreaRatio, inkInTextRatio, blockLineRatio, emptyTextRegionRatio)
    }

    /**
     * 预计算每个 tile 的墨迹掩膜（V2-40）：管线里要审计两次（补区前 / 补区后），
     * 背景估计 + Otsu 只做一次。
     */
    private fun prepareAudit(tileGrays: List<Pair<Tile, GrayImage>>): List<CoverageAuditor.PreparedTile> =
        tileGrays.map { (tile, gray) -> auditor.prepare(gray, tile) }

    /** XY-cut 的断口阈值要一个“行高”，这里取所有文字行（BODY 优先）的中位数。 */
    private fun medianLineHeight(regions: List<LayoutRegion>): Float {
        val all = regions.flatMap { it.lines }
        if (all.isEmpty()) return 16f
        val body = all.filter { it.role == app.pictext.core.model.LineRole.BODY }
        val src = body.ifEmpty { all }
        val h = IntArray(src.size) { src[it].band.height }
        h.sort()
        return max(1, h[h.size / 2]).toFloat()
    }

    private fun LayoutRegion.isLargeWall(pageArea: Long): Boolean {
        val area = bounds.width.toLong() * bounds.height
        return pageArea > 0 && area > 0.85 * pageArea
    }

    private fun format(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    /** 与 App/CLI 判定 `isDark` 同一套系数（77/150/29），避免两边口径不一致。 */
    private fun luminanceOf(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8
    }

    companion object {
        /** 低于这个分数说明模型基本没认出这一页（docs/07 §4.9）。 */
        const val MIN_USABLE_SCORE = 0.30f

        /** 残差区域的 id 从 1000 开始，避免和模型区域撞号。 */
        const val RESIDUAL_ID_BASE = 1000

        /** 页面底色亮度低于它就算深色页，模型输入反相（V2-45）。 */
        const val DARK_LUMA = 110

        /** 少于这么多区域时并行调度本身不值钱（小图/单卡片页）。 */
        const val PARALLEL_REFINE_MIN_REGIONS = 6
    }
}



