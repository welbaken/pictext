package app.pictext.core.analyze

/**
 * 版面分析参数。默认值与 docs/02-algorithm.md 第 1 节一致，修改前先改黄金基线。
 *
 * v1.1 新增的分组：
 * - `rowMergeHeightRatio`：行带合并的总高度上限（P0-1）；
 * - `decor*`：细竖直装饰列检测（引用竖线，P0-2）；
 * - `role*` / `ui*`：行级角色分类（P0-2）；
 * - `split*`：切分安全门（P0-3）；
 * - `latin*`：拉丁/URL 安全切分（P1-1，默认关）；
 * - `multiColumn*`：多栏安全网（P1-4）。
 */
data class AnalysisOptions(
    val analysisMaxSide: Int = 1600,
    val bgCell: Int = 32,
    val bgPercentileLight: Int = 92,
    val bgPercentileDark: Int = 8,
    val bgCandidateDelta: Int = 22,
    val bgMinCellRatio: Float = 0.10f,
    val inkFloor: Int = 12,
    val inkCeil: Int = 80,
    val rowMinInkPx: Float = 0.003f,
    val rowMergeGapRatio: Float = 0.45f,
    val rowMinHeightRatio: Float = 0.35f,

    /** 合并后的行带总高上限（× 行高中位数）。1.25 只容得下“同一个字的上下结构”。 */
    val rowMergeHeightRatio: Float = 1.25f,

    /** 高于该倍数的带先尝试再分解成多行（P0-2 RowDecomposer）。 */
    val rowDecomposeHeightRatio: Float = 1.8f,

    /** 再分解时的谷值阈值：谷值行墨量 < 该比例 × 带内行墨中位数。 */
    val rowValleyInkRatio: Float = 0.15f,

    /** 再分解出的子行高度允许范围（× 行高中位数）。 */
    val rowSubMinHeightRatio: Float = 0.5f,
    val rowSubMaxHeightRatio: Float = 1.8f,

    /** 子行间距的变异系数上限；超过则认为不是稳定正文，保持图块/UI。 */
    val rowSubMaxCv: Float = 0.25f,

    /** 细竖直装饰列：最小连续长度（× 页高）与最小两侧留白（× 行高）。 */
    val decorMinRunRatio: Float = 0.05f,
    val decorMaxWidthRatio: Float = 0.005f,
    val decorMinBlankRatio: Float = 0.5f,

    val figHeightRatio: Float = 2.8f,
    val figDensity: Float = 0.62f,
    val figRunRatio: Float = 2.6f,
    val figRunFrac: Float = 0.008f,
    val figMergeGapRatio: Float = 4.0f,

    /** 行级角色：标题的最小高度倍数，图注的最大高度倍数。 */
    val roleHeadingHeightRatio: Float = 1.25f,
    val roleCaptionHeightRatio: Float = 0.80f,

    /** 标题还必须至少占版心宽的这个比例，避免把“大字 logo”当标题。 */
    val roleHeadingMinWidthRatio: Float = 0.5f,

    /** UI 行判据：横向最大空白（× 行高）与整列实心块宽度（× 行高）。 */
    val roleUiGapRatio: Float = 2.2f,
    val roleUiSolidRunRatio: Float = 1.00f,

    /** 切分安全门：行必须左右都接近版心，且宽度 ≥ 该比例 × 页宽。 */
    val splitInsetRatio: Float = 0.04f,
    val splitFullWidthRatio: Float = 0.72f,

    /**
     * 左对齐且足够宽的行同样允许切分（不必顶到右版心）。
     *
     * 正文里常有“句子在段中提前换行”的行：左对齐、宽度约 0.7 版心、右边空一截。
     * 这类行如果不切，就只能按 `列宽/行宽` 压缩，字号会比同段其他行小一截——
     * Twitter 第二段就是 1.70× vs 2.39×。用“左对齐 + 至少半版心”放行，
     * 既保住字号一致，又不会去切居中标题或缩进引用（它们不左对齐）。
     */
    val splitLeftAlignedRatio: Float = 0.50f,
    /** [v1.1] 一行最多切几段：2 = 现有左右对半；4 = ×4 模式（每个半行再各切一刀）。 */
    val splitSegments: Int = 2,

    val cutWin0: Float = 0.06f,
    val cutWin1: Float = 0.025f,
    val cutCleanRatio: Float = 0.70f,
    val longLineRatio: Float = 0.70f,
    val splitLineRatio: Float = 0.62f,

    /** P1-1：拉丁/URL 安全切分（零墨空白 + 最小缝宽）。默认关。 */
    val latinSplitEnabled: Boolean = false,
    val latinMinGapRatio: Float = 0.12f,

    /** 墨簇宽度中位数 < 该比例 × 行高 → 判为拉丁/代码行（默认不切）。 */
    val latinLikeClusterRatio: Float = 0.62f,

    /** 且每“字宽”的墨簇数 > 该值（中文约 1，拉丁/代码 2–4），避免粗体中文被误判。 */
    val latinLikeClusterDensity: Float = 1.8f,

    /** P1-4：多栏安全网。 */
    val multiColumnMinWidthRatio: Float = 0.05f,
    val multiColumnMaxInkRatio: Float = 0.01f,
    val multiColumnMinCoverage: Float = 0.60f,
    val multiColumnMinSideLines: Int = 2,
)
