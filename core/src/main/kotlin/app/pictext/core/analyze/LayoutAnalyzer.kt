package app.pictext.core.analyze

import app.pictext.core.model.Block
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.GrayImage
import app.pictext.core.model.PageLayout

/**
 * 版面分析入口：按 docs/02-algorithm.md 的 Step 1..8 编排。
 *
 * 纯函数、线程安全；不在内部持有可变状态，但会为每张图分配分析期缓冲区
 * （分析图长边 ≤ 1600，内存可控）。
 */
class LayoutAnalyzer(private val options: AnalysisOptions = AnalysisOptions()) {

    /**
     * @param image 降采样后的灰度分析图。
     * @param srcWidth 源图像素宽，用于把 A 坐标换算回 S。
     * @param srcHeight 源图像素高。
     */
    fun analyze(image: GrayImage, srcWidth: Int = image.width, srcHeight: Int = image.height): PageLayout {
        val w = image.width
        val h = image.height
        val safeSrcW = srcWidth.coerceAtLeast(1)
        val safeSrcH = srcHeight.coerceAtLeast(1)
        val analysisScale = if (safeSrcW > 0) w.toFloat() / safeSrcW else 1f

        if (w <= 0 || h <= 0) {
            return emptyLayout(image, safeSrcW, safeSrcH, analysisScale, "no_ink")
        }

        val background = BackgroundEstimator(options).estimate(image)
        val bin = Binarizer(options).binarize(background.ink, w, h)
        val area = w.toLong() * h
        if (bin.inkPixels == 0 || bin.inkPixels < 0.0005 * area) {
            return emptyLayout(
                image, safeSrcW, safeSrcH, analysisScale, "no_ink",
                inkPixels = bin.inkPixels, threshold = bin.threshold, dark = background.isDark,
            )
        }

        val rowResult = RowSegmenter(options).segment(bin.mask, w, h)
        if (rowResult.bands.isEmpty()) {
            return emptyLayout(
                image, safeSrcW, safeSrcH, analysisScale, "no_ink",
                inkPixels = bin.inkPixels, threshold = bin.threshold, dark = background.isDark,
            )
        }
        // 细竖直装饰列（引用竖线）已从 contentMask 清除；后续所有几何都用它。
        val content = if (rowResult.contentMask.isEmpty()) bin.mask else rowResult.contentMask

        val classified = BlockClassifier(options).classify(
            content, w, h, rowResult.bands, rowResult.medianHeight, background.ink,
        )
        val textCount = classified.blocks.sumOf { if (it is Block.Paragraph) it.lines.size else 0 }
        val figureCount = classified.blocks.count { it is Block.Figure }

        if (textCount == 0) {
            return PageLayout(
                srcWidth = safeSrcW,
                srcHeight = safeSrcH,
                analysisScale = analysisScale,
                blocks = classified.blocks,
                textLeft = 0,
                textRight = 0,
                splitX = -1,
                cleanRatio = 0f,
                medianLineHeight = classified.medianLineHeight,
                medianGap = classified.medianGap,
                medianPitch = classified.medianLineHeight + classified.medianGap,
                isDark = background.isDark,
                backgroundColor = image.backgroundColor,
                diagnostics = Diagnostics(
                    inkPixels = bin.inkPixels,
                    inkThreshold = bin.threshold,
                    rowCount = rowResult.bands.size,
                    figureCount = figureCount,
                    notes = listOf("no_text"),
                ),
            )
        }

        val metrics = PageMetricsCalculator.compute(classified.blocks, options)
        if (metrics.longLineCount < 2) {
            return PageLayout(
                srcWidth = safeSrcW,
                srcHeight = safeSrcH,
                analysisScale = analysisScale,
                blocks = classified.blocks,
                textLeft = metrics.textLeft,
                textRight = metrics.textRight,
                splitX = -1,
                cleanRatio = 0f,
                medianLineHeight = metrics.medianLineHeight,
                medianGap = metrics.medianGap,
                medianPitch = metrics.medianPitch,
                isDark = background.isDark,
                backgroundColor = image.backgroundColor,
                diagnostics = Diagnostics(
                    inkPixels = bin.inkPixels,
                    inkThreshold = bin.threshold,
                    rowCount = rowResult.bands.size,
                    figureCount = figureCount,
                    notes = listOf("too_few_long_lines"),
                ),
            )
        }

        // P1-4：双栏报刊是唯一“会安静地做错”的场景，检测到就直接回退原图。
        if (MultiColumnDetector(options).detect(content, w, h, classified.blocks, metrics)) {
            return layoutWith(
                classified, metrics, splitBlocks = classified.blocks, splitX = -1, cleanRatio = 0f,
                dark = background.isDark, image = image, srcW = safeSrcW, srcH = safeSrcH,
                analysisScale = analysisScale, inkPixels = bin.inkPixels, threshold = bin.threshold,
                rowCount = rowResult.bands.size, figureCount = figureCount,
                notes = listOf("multi_column"),
            )
        }

        val split = ColumnSplitter(options).split(content, w, h, classified.blocks, metrics)
        val notes = if (split.weak) listOf("weak_cut") else emptyList()
        return layoutWith(
            classified, metrics, splitBlocks = split.blocks, splitX = split.splitX,
            cleanRatio = split.cleanRatio,
            dark = background.isDark, image = image, srcW = safeSrcW, srcH = safeSrcH,
            analysisScale = analysisScale, inkPixels = bin.inkPixels, threshold = bin.threshold,
            rowCount = rowResult.bands.size, figureCount = figureCount, notes = notes,
        )
    }

    private fun layoutWith(
        classified: ClassifiedPage,
        metrics: PageMetrics,
        splitBlocks: List<Block>,
        splitX: Int,
        cleanRatio: Float,
        dark: Boolean,
        image: GrayImage,
        srcW: Int,
        srcH: Int,
        analysisScale: Float,
        inkPixels: Int,
        threshold: Int,
        rowCount: Int,
        figureCount: Int,
        notes: List<String>,
    ): PageLayout = PageLayout(
        srcWidth = srcW,
        srcHeight = srcH,
        analysisScale = analysisScale,
        blocks = splitBlocks,
        textLeft = metrics.textLeft,
        textRight = metrics.textRight,
        splitX = splitX,
        cleanRatio = cleanRatio,
        medianLineHeight = metrics.medianLineHeight,
        medianGap = metrics.medianGap,
        medianPitch = metrics.medianPitch,
        isDark = dark,
        backgroundColor = image.backgroundColor,
        diagnostics = Diagnostics(
            inkPixels = inkPixels,
            inkThreshold = threshold,
            rowCount = rowCount,
            figureCount = figureCount,
            cleanRatio = cleanRatio,
            notes = notes,
        ),
    )

    private fun emptyLayout(
        image: GrayImage,
        srcW: Int,
        srcH: Int,
        analysisScale: Float,
        note: String,
        inkPixels: Int = 0,
        threshold: Int = 0,
        dark: Boolean = false,
    ): PageLayout = PageLayout(
        srcWidth = srcW,
        srcHeight = srcH,
        analysisScale = analysisScale,
        blocks = emptyList(),
        textLeft = 0,
        textRight = 0,
        splitX = -1,
        cleanRatio = 0f,
        medianLineHeight = 0f,
        medianGap = 0f,
        medianPitch = 0f,
        isDark = dark,
        backgroundColor = image.backgroundColor,
        diagnostics = Diagnostics(inkPixels = inkPixels, inkThreshold = threshold, notes = listOf(note)),
    )
}
