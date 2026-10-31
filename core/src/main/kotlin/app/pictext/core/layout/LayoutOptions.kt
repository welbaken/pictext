package app.pictext.core.layout

import app.pictext.core.model.RectI

/**
 * v2 版式链路参数。默认值来自 docs/07 §4.1 / §4.5 / §4.6 / §4.7，改动前先改黄金基线。
 *
 * 这一组参数只服务 `LayoutStrategy.DOC_LAYOUT`；v1 的 `AnalysisOptions` 一个字都不动。
 */
data class LayoutOptions(
    // ---- tile 网格（§4.1）----
    /** 模型输入边长；官方 inference.yml 的 `target_size: [480, 480]` 已核对。 */
    val modelInput: Int = 480,

    /** 相邻 tile 的重叠像素；边缘 tile 贴边而不是补边，靠重叠 + 全局 NMS 去重。 */
    val overlapPx: Int = 64,

    /** `scale = clamp(480 / pageW, minScale, maxScale)`。 */
    val minScale: Float = 0.35f,
    val maxScale: Float = 1.0f,

    /** tile 数上限；超过直接回退 v1（`layout_too_many_tiles`）。 */
    val maxTiles: Int = 64,

    // ---- 检测后处理（§V2-10）----
    val scoreThreshold: Float = 0.40f,
    val nmsIou: Float = 0.50f,

    // ---- 区域合并（§4.4）----
    /** 包含去重阈值：`area(A∩B) / area(B) ≥ 0.8` 视为包含。 */
    val containmentRatio: Float = 0.80f,

    /** 两个 TEXT 框的横向重叠比例门槛。 */
    val textMergeOverlapRatio: Float = 0.50f,

    /** 两个 TEXT 框的垂直间距上限（× 行高代理）。 */
    val textMergeGapRatio: Float = 1.20f,

    // ---- 区域细化（§4.5）----
    /** ROI 外扩像素（模型框的容差）。 */
    val refinePadPx: Int = 6,

    /** 细化 ROI 的最大宽度；超过就降采样。 */
    val refineMaxWidth: Int = 1400,

    /** 细化 ROI 的最大像素数；超过就降采样（`sqrt(maxPixels / area)`）。 */
    val refineMaxPixels: Int = 2_500_000,

    /** FIGURE/TABLE/OTHER 的细化宽度上限（V2-50）。只判断“里面有没有字”，半分辨率足够。 */
    val refineFigureMaxWidth: Int = 700,

    /** FIGURE/TABLE/OTHER 的细化像素上限（V2-50），与 [refineFigureMaxWidth] 一起降采样。 */
    val refineFigureMaxPixels: Int = 625_000,

    /** 区域细化按区域并行（V2-50）。结果顺序由 `collect` 保证，关掉只用于基准对比/排障。 */
    val parallelRefine: Boolean = true,

    /** 行宽 < 该比例 × 区域宽时丢弃（噪声/边框）。 */
    val refineMinLineWidthRatio: Float = 0.10f,

    /** ROI 内有效墨点下限（相对 ROI 面积）；低于它就是“没字”。 */
    val refineMinInkRatio: Float = 0.0005f,

    // ---- 区域二次判定（§4.4.4）----
    val figureToTextMinLines: Int = 3,
    val figureToTextMaxHeightCv: Float = 0.25f,
    val figureToTextMinWidthRatio: Float = 0.50f,
    val figureToTextMaxDensity: Float = 0.30f,
    val textToFigureMaxDensity: Float = 0.62f,
    val textToFigureMaxRunRatio: Float = 2.60f,

    // ---- 覆盖审计（§4.6）----
    /** 整页墨迹覆盖率硬门槛。 */
    val minCoverage: Float = 0.995f,

    /** 单 tile 残差 ink 占比在此区间内才补区；超过 [residualFailRatio] 直接回退。 */
    val residualInkRatio: Float = 0.003f,
    val residualFailRatio: Float = 0.05f,

    /** 残差框合并时的膨胀像素。 */
    val residualDilatePx: Int = 8,

    /** 残差框内细化出 ≥ 该行数判 TEXT，否则整块保留为 FIGURE。 */
    val residualMinLines: Int = 2,

    /** 残差块数量上限；超过说明“模型基本没认出这一页”，直接回退（`layout_residual_high`）。
     *
     * 默认 256 而不是 64：长图（7539px、8 个 tile、14 张卡片）上，卡片内的小字会被切成
     * 几十个残差块，64 会在“本可以全部补回来”的页面上误触发回退。 */
    val maxResiduals: Int = 256,

    /** 覆盖审计的降采样倍数（V2-50）：2 = 480²→240²，像素数 /4。
     *
     * 残差框只需要 ~2 个模型像素的精度（映射回 S 后还会按 `residualDilatePx=8` 合并），
     * 覆盖率又只是面积比，所以降采样不改变判定口径，只省背景估计 + Otsu 的时间。
     * 单测传 1（`CoverageAuditor` 构造参数默认值）保证坐标断言仍精确到 1px。 */
    val auditDownsample: Int = 2,

    /** 细化 ROI 内的“细竖直装饰列”宽度上限（相对 ROI 宽）。
     *
     * v1 用页宽的 0.5%，对 ROI 来说太紧：引用块 6px 的左边线在 900px 宽的 ROI 里是 0.67%，
     * 判不出来就会被当成正文的一部分，把 3 行文字粘成一个大带（`01-article-light` 的引用块）。
     * 1.2% 足够容纳这类引用线，又远窄于表格边框/图片边缘。 */
    val refineDecorMaxWidthRatio: Float = 0.012f,

    // ---- 阅读顺序（§4.7）----
    /** 横向断带的最小空白高度 = `max(orderMinGapPx, orderMinGapYRatio × 行高中位数)`。 */
    val orderMinGapYRatio: Float = 0.25f,

    /** 纵向断列的最小空白宽度 = `max(orderMinGapPx, orderMinGapXRatio × 行高中位数)`。 */
    val orderMinGapXRatio: Float = 0.50f,
    val orderMinGapPx: Int = 4,

    /** 递归深度上限，防御异常输入。 */
    val orderMaxDepth: Int = 32,

    // ---- 竖排 / 回退（§4.7.3、§4.9）----
    /** 竖排文字区域面积占比超过它就整页展示原图。 */
    val verticalMaxRatio: Float = 0.30f,

    // ---- 可信度闸门（V2-43）----
    /** 文字类区域面积占比低于它才值得做“和 v1 核对一遍”的复核（复核要跑一次 v1 分析）。
     *
     * 0.20 来自 12 张真值夹具：文章/论文页 0.35–0.54（不复核），卡片流/聊天/海报 0.07–0.20（复核）。
     * 复核本身不改结论，只有“v2 的行数比 v1 少太多”才回退。 */
    val trustCheckTextAreaBelow: Float = 0.20f,

    /** 复核判据：`v2 文字行数 < 该比例 × v1 文字行数` 就认为“文字被框成了图片”。
     *
     * 实测：聊天夹具 v2/v1 = 0.50（气泡被框成 FIGURE），长图文流 0.72、其余 0.95–1.87。
     * 0.6 把聊天和其余分开，同时留出 0.12 的余量。 */
    val trustLineRatio: Float = 0.60f,

    /** v1 自己要检出这么多行，核对才有意义（近乎空白的页面不比行数）。 */
    val trustMinV1Lines: Int = 6,

    // ---- 切点特征（§4.8.2 / §V2-21）----
    /** 硬缝最小宽度 = `max(2, hardGapMinRatio × 行高)`。 */
    val hardGapMinRatio: Float = 0.10f,

    /** 软缝允许的最大列墨量。 */
    val softGapMaxInk: Float = 1.5f,

    /** `isCjkDominant`：宽墨簇（≥ cjkClusterRatio × 行高）占比门槛。 */
    val cjkClusterRatio: Float = 0.60f,
    val cjkDominantRatio: Float = 0.60f,

    /** `isLatinLike`：墨簇宽度中位数 < latinClusterRatio × 行高，且每字宽簇数 > latinClusterDensity。 */
    val latinClusterRatio: Float = 0.62f,
    val latinClusterDensity: Float = 1.8f,

    /** `digitHeavy`：数字/标点占墨像素比 ≥ 该值（价格、电话、长数字）。 */
    val digitHeavyRatio: Float = 0.45f,
) {
    init {
        require(modelInput in 64..2048) { "modelInput out of range: $modelInput" }
        require(overlapPx >= 0) { "overlapPx < 0: $overlapPx" }
        require(minScale > 0f && maxScale >= minScale) { "bad scale range $minScale..$maxScale" }
        require(auditDownsample in 1..4) { "auditDownsample out of range: $auditDownsample" }
    }

    /** tile 采样后送模型的边长（与 [modelInput] 相同，保留给测试覆盖动态尺寸）。 */
    val tileInput: Int get() = modelInput

    companion object {
        /** 供测试使用的“一页就是一张 tile”的最小配置。 */
        fun singleTile(modelInput: Int = 480): LayoutOptions =
            LayoutOptions(modelInput = modelInput, overlapPx = 0, minScale = 1f, maxScale = 1f)
    }
}

/**
 * 页面级上下文：源图尺寸与底色。
 *
 * 底色/深浅由调用方从解码层拿（App 用已有的分析位图，CLI 用原图边界中位色），
 * 这样 v2 不必为了一个底色再解一张全页图。
 */
data class PageContext(
    val srcWidth: Int,
    val srcHeight: Int,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val isDark: Boolean = false,
) {
    val size: RectI get() = RectI.ofSize(srcWidth, srcHeight)
}
