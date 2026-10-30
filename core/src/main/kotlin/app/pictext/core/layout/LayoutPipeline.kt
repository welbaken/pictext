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
    private val auditor = CoverageAuditor(options)
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

        for (tile in grid.tiles) {
            val t0 = System.nanoTime()
            val argb = sampler.sample(tile.src, tile.innerWidth, tile.innerHeight, page.backgroundColor)
            preprocessor.process(argb, tile.innerWidth, tile.innerHeight, tile, input, gray)
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
        var regions = built.map { fixer.fix(it, refiner.refine(regionSampler, page, it)) }
        regions = regions.map { r -> r.copy(lines = fixer.normalizeRoles(r)) }
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
        val first = coverageOf(tileGrays, regions)
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

        val all = regions + residuals
        val coverage = coverageOf(tileGrays, all)
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
        val notes = ArrayList<String>(5)
        notes.add("layout_model=${contract.name}")
        notes.add("layout_tiles_${grid.count}")
        notes.add("layout_coverage=${format(coverage.coverage)}")
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
                decodeMs = decodeNanos / 1_000_000,
                detectMs = detectNanos / 1_000_000,
                refineMs = refineMs,
                coverageMs = coverageMs,
                orderMs = orderMs,
            ),
        )
    }

    private fun coverageOf(
        tileGrays: List<Pair<Tile, GrayImage>>,
        regions: List<LayoutRegion>,
    ): CoverageReport = auditor.report(tileGrays.map { (tile, gray) -> auditor.auditTile(gray, tile, regions) })

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

    companion object {
        /** 低于这个分数说明模型基本没认出这一页（docs/07 §4.9）。 */
        const val MIN_USABLE_SCORE = 0.30f

        /** 残差区域的 id 从 1000 开始，避免和模型区域撞号。 */
        const val RESIDUAL_ID_BASE = 1000
    }
}
