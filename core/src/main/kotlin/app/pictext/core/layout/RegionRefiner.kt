package app.pictext.core.layout

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.BackgroundEstimator
import app.pictext.core.analyze.Binarizer
import app.pictext.core.analyze.BlockClassifier
import app.pictext.core.analyze.ColumnInk
import app.pictext.core.analyze.RowBand
import app.pictext.core.analyze.RowClassifier
import app.pictext.core.analyze.RowSegmenter
import app.pictext.core.model.InkProfile
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LineCutFeatures
import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.TextLine
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 文本区域细化（docs/07 §4.5）：把一个模型区域当作 ROI，在 ROI 内重新切行，并为每行
 * 预计算三级切点特征（硬缝 / 软缝 / 最小墨量列）与敏感行标记。
 *
 * ## 为什么 ROI 从源图取，而不是在“全页分析图”上细化
 *
 * v1 的分析图按长边 ≤1600 定标，1080×20000 的长图被压到 86px 宽，24px 的正文行只剩不到
 * 2px——行投影会糊成一片，字缝（1–2px）直接被采样抹掉。这里沿用 v2 的 tile/ROI 思路：
 * 通过 [RegionSampler] 直接从源图采样这一块，分辨率只受 `refineMaxWidth` / `refineMaxPixels`
 * 约束，内存峰值也只跟一个 ROI 走，而不是跟整页走。
 *
 * ## 为什么模型框只当 ROI
 *
 * 检测框是模型在 480×480 输入上的估计，边缘本来就有几个像素误差；它也可能把相邻行、
 * 引用竖线、页码一起圈进来。细化**不信任框的四条边**：行带全部由 ROI 内的墨迹投影重新
 * 决定，外扩 [LayoutOptions.refinePadPx] 只是补回模型的收缩误差。因此只要文字还落在 ROI
 * 内，框整体平移十几像素不会改变行带（见 `RegionRefinerTest` 的偏移用例）。
 *
 * ## 为什么敏感行只能用几何代理
 *
 * 本版本没有 OCR，拿不到“这一列是数字、字母还是汉字”的语义信息。只有墨簇的**几何形态**
 * 可用：拉丁/代码/URL 的墨簇窄而密，数字串的簇宽与簇间距高度一致。代理必然有漏判
 * （例如全角标点组成的行），但它的错误方向是“多保护、少强切”，符合 docs/07 §4.8.2
 * “宁可压缩一行，不可劈开一个词”的原则。
 *
 * @param options v2 版式参数（ROI 外扩、降采样上限、切点阈值）。
 * @param analysisOptions 复用的 v1 分析参数；细化不另起一套二值化/投影参数。
 */
class RegionRefiner(
    private val options: LayoutOptions = LayoutOptions(),
    private val analysisOptions: AnalysisOptions = AnalysisOptions(),
) {

    /**
     * 细化 [region]。
     *
     * 返回的 [RefineResult.region] 已经把行写回（`withLines`），坐标全部是 **S 坐标**；
     * 行序保持 [RowSegmenter] 的 y 升序，不再重排。失败时 `failed = true`、`lines` 为空，
     * `inkPixels` / `inkRatio` 仍尽量填上，供 `RegionKindFixer` 判断“这是不是图”。
     */
    fun refine(sampler: RegionSampler, page: PageContext, region: LayoutRegion): RefineResult {
        // 1. 模型框只是 ROI：外扩容差后裁到页面内；完全在页外就没什么可细化的。
        val pad = options.refinePadPx.coerceAtLeast(0)
        val crop = RectI(
            region.bounds.left - pad,
            region.bounds.top - pad,
            region.bounds.right + pad,
            region.bounds.bottom + pad,
        ).clampTo(page.srcWidth, page.srcHeight)
        if (crop.isEmpty) return failed(region)

        val first = refineWithin(sampler, page, region, crop)
        if (first.failed) return first

        // 1.5 行带贴住 ROI 左右边界时，向外再吃一截（V2-49）。
        //
        // 模型框在标题上常比实际文本窄几个像素，外扩 [LayoutOptions.refinePadPx] 也不一定够：
        // 690px 宽截图上「Pro 20x 200美元套餐」的模型框右边界是 527，文本墨迹一直到 537，
        // 外扩 6px 后 ROI 仍在 533 把字形切断。行带只要贴住 ROI 边界，就说明“这里不是墨迹的
        // 真实终点”，把 ROI 按行高的一半（最多 [EDGE_GROW_MAX_PX]）再扩一次重切。
        // 不扩上下边：上下相邻的是别的段落，横向相邻的通常只是同一个字的残余笔画。
        val touchLeft = first.lines.any { it.band.left <= crop.left }
        val touchRight = first.lines.any { it.band.right >= crop.right }
        if (touchLeft || touchRight) {
            val grow = maxOf(
                (0.5f * first.medianLineHeight).roundToInt(),
                options.refinePadPx.coerceAtLeast(1),
            ).coerceAtMost(EDGE_GROW_MAX_PX)
            val expanded = RectI(
                if (touchLeft) crop.left - grow else crop.left,
                crop.top,
                if (touchRight) crop.right + grow else crop.right,
                crop.bottom,
            ).clampTo(page.srcWidth, page.srcHeight)
            if (expanded != crop) {
                val second = refineWithin(sampler, page, region, expanded)
                if (!second.failed) return second
            }
        }
        return first
    }

    /** 在一个已经确定好的 ROI 内细化；[refine] 负责 ROI 的外扩重试。 */
    private fun refineWithin(
        sampler: RegionSampler,
        page: PageContext,
        region: LayoutRegion,
        crop: RectI,
    ): RefineResult {
        // 2. 直接从源图采样；scale 是“采样列 / 源列”，允许 < 1。
        val img = sampler.gray(crop, options.refineMaxWidth, options.refineMaxPixels)
        val w = img.gray.width
        val h = img.gray.height
        val scale = img.scale
        if (w <= 0 || h <= 0 || scale <= 0f || !scale.isFinite()) return failed(region)

        // 3. 背景估计 + 二值化，全部复用 v1，保证与全页分析同一套墨迹定义。
        val bg = BackgroundEstimator(analysisOptions).estimate(img.gray)
        val bin = Binarizer(analysisOptions).binarize(bg.ink, w, h)
        val inkPixels = bin.inkPixels
        val inkRatio = inkPixels.toFloat() / (w.toLong() * h).toFloat()
        if (inkRatio < options.refineMinInkRatio) {
            return failed(region, inkPixels, inkRatio)
        }

        // 4. 行投影。contentMask 为空表示没检测到装饰列，继续用原始 mask。
        val rs = RowSegmenter(analysisOptions).segment(bin.mask, w, h)
        if (rs.bands.isEmpty()) return failed(region, inkPixels, inkRatio)
        val content = if (rs.contentMask.isEmpty()) bin.mask else rs.contentMask
        val medianH = if (rs.medianHeight > 0f) rs.medianHeight else 8f

        // 5. 竖直游程只算一次，行角色与 maxRun 都用它。
        val vrun = BlockClassifier(analysisOptions).verticalRuns(content, w, h)
        val vertical = isVertical(rs.bands)

        // 4.5 的验收要求“纯照片区域返回 0 行”。随机纹理/照片在投影里通常就是**一个铺满
        // ROI 的带**：它不是文字行，但也躲得过“太窄”过滤。这里把它整体判失败，交给
        // 区域二次判定降级为 FIGURE；正常文字行的 ROI 有 6px 外扩，不会四边贴满。
        if (isFullBleedBlock(rs.bands, w, h)) {
            return failed(region, inkPixels, inkRatio, vertical)
        }

        val classifier = RowClassifier(analysisOptions)
        val lines = ArrayList<TextLine>(rs.bands.size)
        for (band in rs.bands) {
            if (band.height <= 0 || band.width <= 0) continue

            // 6. 行太细 → 噪声/边框。统一换成 S 宽度再和区域宽度比（降采样下也成立）。
            val widthS = band.width / scale
            if (widthS < options.refineMinLineWidthRatio * region.bounds.width) continue

            val features = classifier.features(content, w, band, medianH, vrun, w)
            val role = roleFor(region.kind, classifier.role(features, medianH))

            // 7. 坐标换回 S。四边都用 round，和 `RectI.scale` 的口径一致。
            val x0 = (crop.left + band.x0 / scale).roundToInt().coerceIn(crop.left, crop.right)
            val y0 = (crop.top + band.y0 / scale).roundToInt().coerceIn(crop.top, crop.bottom)
            val x1 = (crop.left + band.x1 / scale).roundToInt().coerceIn(crop.left, crop.right)
            val y1 = (crop.top + band.y1 / scale).roundToInt().coerceIn(crop.top, crop.bottom)
            if (x1 <= x0 || y1 <= y0) continue

            // 8. 列剖面：先按采样分辨率算一次 ColumnInk，再按“最近采样列”重采样到 S。
            val column = ColumnInk.of(content, w, RectI(band.x0, band.y0, band.x1, band.y1))
            val profile = toSourceProfile(column, crop.left, scale, x0, x1)
            val lineDensity = if (band.width * band.height == 0) {
                0f
            } else {
                // 两轴同一 scale，所以像素占比与 S 空间完全相等。
                band.inkCount.toFloat() / (band.width.toFloat() * band.height)
            }
            val maxRun = (features.maxRun / scale).roundToInt()
            val cutFeatures = buildCutFeatures(profile, y1 - y0, lineDensity, maxRun)

            lines.add(
                TextLine(
                    band = RectI(x0, y0, x1, y1),
                    role = role,
                    cutFeatures = cutFeatures,
                ),
            )
        }

        if (lines.isEmpty()) return failed(region, inkPixels, inkRatio, vertical)

        // 9~11. 结果级统计全部用 S 坐标；行序保持投影顺序。
        val heights = IntArray(lines.size) { lines[it].band.height }.sortedArray()
        val medianLineHeight = heights[heights.size / 2].toFloat()
        val maxRun = lines.maxOf { it.cutFeatures?.maxRun ?: 0 }
        val refined = region.withLines(lines)
        return RefineResult(
            region = refined,
            medianLineHeight = medianLineHeight,
            inkPixels = inkPixels,
            inkRatio = inkRatio,
            // 10. ROI 墨点 / ROI 面积；与 inkRatio 同口径。
            density = inkRatio,
            maxRun = maxRun,
            vertical = vertical,
            failed = false,
            lines = lines,
        )
    }

    /** 按区域类别覆盖行角色；其余类别保留 [RowClassifier] 的结论。 */
    private fun roleFor(kind: RegionKind, classifierRole: LineRole): LineRole = when (kind) {
        RegionKind.TITLE -> LineRole.HEADING
        RegionKind.CAPTION -> LineRole.CAPTION
        RegionKind.CHROME -> LineRole.UI
        else -> classifierRole
    }

    /** `vertical`：只有一两个带，且至少一个“又高又窄”，即竖排的一列。 */
    private fun isVertical(bands: List<RowBand>): Boolean =
        bands.size <= 2 && bands.any { it.height > 2.2f * it.width }

    /**
     * 一个带铺满 ROI（≥98% 宽高）时，它不可能是文字行，而是整块照片/纹理。
     *
     * 两种正常情况都不会命中：单个文字行的带高只有 ROI 高的一部分（外扩 6px 保证
     * 上下有留白）；多行文字会被投影切成多个带。
     */
    private fun isFullBleedBlock(bands: List<RowBand>, width: Int, height: Int): Boolean {
        if (bands.size != 1) return false
        val b = bands[0]
        return b.width >= 0.98f * width && b.height >= 0.98f * height
    }

    /**
     * 把采样分辨率的 [ColumnInk] 按“最近采样列”映射成 S 空间的 [InkProfile]。
     *
     * 对每个 S 列 `x` 取采样列 `round((x - cropLeft) * scale)`。降采样时多个 S 列会
     * 落到同一采样列（形成平台），**窄于 1/scale 的缝会合并**——这是可接受的已知限制：
     * 规划期还能退到软缝或压缩，但不会因为“看不见的 1px 缝”去劈开一个词。
     */
    private fun toSourceProfile(
        column: ColumnInk,
        cropLeft: Int,
        scale: Float,
        sLeft: Int,
        sRight: Int,
    ): InkProfile {
        val counts = IntArray(max(0, sRight - sLeft))
        if (counts.isEmpty() || column.width <= 0) return InkProfile.of(sLeft, counts)
        for (x in sLeft until sRight) {
            val sampleX = ((x - cropLeft) * scale).roundToInt()
            val idx = (sampleX - column.left).coerceIn(0, column.width - 1)
            counts[x - sLeft] = column.counts[idx]
        }
        return InkProfile.of(sLeft, counts)
    }

    /**
     * 逐行算三级切点特征（docs/07 §4.8.2、V2-21）。
     *
     * 所有几何量都在 S 空间：`profile` 的列是源图绝对列，行高/行宽也是 S 像素。
     * 敏感行没有 OCR，只能用几何代理，具体阈值见各分支注释。
     */
    private fun buildCutFeatures(
        profile: InkProfile,
        lineHeight: Int,
        density: Float,
        maxRun: Int,
    ): LineCutFeatures {
        val width = profile.width
        if (width <= 0 || lineHeight <= 0) {
            return LineCutFeatures(density = density, maxRun = maxRun, profile = profile)
        }
        fun ink(i: Int): Int = profile.inkAt(profile.left + i)

        // --- 硬缝：连续零墨列，宽度 ≥ max(2, round(0.10 × 行高)) ---
        val hardMin = max(2, (options.hardGapMinRatio * lineHeight).roundToInt())
        val hardMask = BooleanArray(width)
        val hardGaps = ArrayList<IntRange>(4)
        var i = 0
        while (i < width) {
            if (ink(i) != 0) {
                i++
                continue
            }
            var j = i
            while (j + 1 < width && ink(j + 1) == 0) j++
            if (j - i + 1 >= hardMin) {
                hardGaps.add((profile.left + i)..(profile.left + j))
                for (k in i..j) hardMask[k] = true
            }
            i = j + 1
        }

        // --- 软缝：墨量 ≤ softGapMaxInk 的 1–2px 列组，排除已经是硬缝的列 ---
        // 零墨的 1–2px 缝（不够硬缝宽度）也算软缝，这正是“紧排中文”可用的刀口。
        val softGaps = ArrayList<IntRange>(4)
        i = 0
        while (i < width) {
            if (ink(i).toFloat() > options.softGapMaxInk) {
                i++
                continue
            }
            var j = i
            while (j + 1 < width && ink(j + 1).toFloat() <= options.softGapMaxInk) j++
            val len = j - i + 1
            var overlapsHard = false
            for (k in i..j) if (hardMask[k]) overlapsHard = true
            if (len in 1..2 && !overlapsHard) {
                softGaps.add((profile.left + i)..(profile.left + j))
            }
            i = j + 1
        }

        // --- minInkColumn：左右各留 8% 边距，墨量最小且是局部最小（≤ 两侧），并列取最左 ---
        val margin = (width * 0.08f).roundToInt()
        val from = max(profile.left + 1, profile.left + margin)
        val to = minOf(profile.right - 2, profile.right - 1 - margin)
        var minInkColumn = -1
        var minInk = Int.MAX_VALUE
        for (x in from..to) {
            val v = profile.inkAt(x)
            if (v <= profile.inkAt(x - 1) && v <= profile.inkAt(x + 1) && v < minInk) {
                minInk = v
                minInkColumn = x
            }
        }

        // --- 墨簇与簇间空白（S 空间，列坐标绝对） ---
        val clusters = ArrayList<IntRange>(width / 4 + 1)
        i = 0
        while (i < width) {
            if (ink(i) <= 0) {
                i++
                continue
            }
            var j = i
            while (j + 1 < width && ink(j + 1) > 0) j++
            clusters.add((profile.left + i)..(profile.left + j))
            i = j + 1
        }
        val widths = IntArray(clusters.size) { clusters[it].last - clusters[it].first + 1 }
        val gaps = IntArray(max(0, clusters.size - 1)) {
            clusters[it + 1].first - clusters[it].last - 1
        }

        val lineH = lineHeight.toFloat()

        // --- cjkDominant：宽墨簇（≥0.6×行高）里的墨点 ≥60% 全行墨点 ---
        val totalInk = profile.totalInk
        var wideInk = 0
        for (c in clusters) {
            if (c.last - c.first + 1 >= options.cjkClusterRatio * lineH) {
                wideInk += profile.inkIn(c.first, c.last + 1)
            }
        }
        val cjkDominant = totalInk > 0 &&
            wideInk.toFloat() >= options.cjkDominantRatio * totalInk

        // --- latinLike：簇宽中位数 <0.62×行高，且每“字宽”的簇数 >1.8 ---
        val medianClusterWidth = median(widths)
        val ems = width / lineH
        val clustersPerEm = if (ems > 0f) clusters.size / ems else 0f
        val latinLike = clusters.isNotEmpty() &&
            medianClusterWidth < options.latinClusterRatio * lineH &&
            clustersPerEm > options.latinClusterDensity

        // --- digitHeavy：没有 OCR，用“等宽等距”的几何代理 ---
        // 数字串/价格的墨簇宽度通常落在 0.2–0.85 行高，且宽度、相邻簇间距都高度一致。
        // 固定阈值来自合成样本的形态：簇宽 CV <0.25、间距 CV <0.35；至少要 2 个簇
        // （单簇没有“间距”可言，不参与判定）。`digitHeavyRatio` 是给未来 OCR 版本
        // 预留的“数字墨点占比”，本版本没有语义信息，故不使用。
        val digitHeavy = clusters.size >= 2 &&
            medianClusterWidth >= 0.2f * lineH &&
            medianClusterWidth <= 0.85f * lineH &&
            cv(widths) < 0.25f &&
            cv(gaps) < 0.35f

        // --- hasUrlOrCode：latinLike 且（最大内部空白 ≥1.5×行高，或出现 ≥3 个等宽等距簇）---
        val maxGap = if (gaps.isEmpty()) 0 else gaps.max()
        val hasUrlOrCode = latinLike &&
            (maxGap >= 1.5f * lineH || hasEqualPitchRun(widths, gaps))

        val sensitive = latinLike || digitHeavy || hasUrlOrCode

        return LineCutFeatures(
            hardGaps = hardGaps,
            softGaps = softGaps,
            minInkColumn = minInkColumn,
            cjkDominant = cjkDominant,
            latinLike = latinLike,
            hasUrlOrCode = hasUrlOrCode,
            digitHeavy = digitHeavy,
            sensitive = sensitive,
            density = density,
            maxRun = maxRun,
            profile = profile,
        )
    }

    /** 是否存在连续的 3 个“等宽等距”墨簇（URL/代码/表格状数字的几何代理）。 */
    private fun hasEqualPitchRun(widths: IntArray, gaps: IntArray): Boolean {
        if (widths.size < 3 || gaps.size < 2) return false
        for (i in 0..widths.size - 3) {
            if (gaps[i] <= 0 || gaps[i + 1] <= 0) continue
            val w = intArrayOf(widths[i], widths[i + 1], widths[i + 2])
            val g = intArrayOf(gaps[i], gaps[i + 1])
            if (cv(w) < 0.25f && cv(g) < 0.25f) return true
        }
        return false
    }

    /** 变异系数 `σ / μ`；样本不足或均值为 0 时返回 0（视为完全一致）。 */
    private fun cv(values: IntArray): Float {
        if (values.size < 2) return 0f
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        if (mean <= 0.0) return 0f
        var sq = 0.0
        for (v in values) {
            val d = v - mean
            sq += d * d
        }
        return (sqrt(sq / values.size) / mean).toFloat()
    }

    /** 中位数（偶数取偏右那个，与 [app.pictext.core.util.Stats] 的稳健中位数口径一致）。 */
    private fun median(values: IntArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2].toFloat()
    }

    private fun failed(
        region: LayoutRegion,
        inkPixels: Int = 0,
        inkRatio: Float = 0f,
        vertical: Boolean = false,
    ): RefineResult = RefineResult(
        region = region.withLines(emptyList()),
        medianLineHeight = 0f,
        inkPixels = inkPixels,
        inkRatio = inkRatio,
        density = inkRatio,
        maxRun = 0,
        vertical = vertical,
        failed = true,
        lines = emptyList(),
    )

    private companion object {
        /** 行带贴边时 ROI 最多再向外扩多少像素（防止跨过栏间距吃进相邻列）。 */
        const val EDGE_GROW_MAX_PX = 32
    }
}
