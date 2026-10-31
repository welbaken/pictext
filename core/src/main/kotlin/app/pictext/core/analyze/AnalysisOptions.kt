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
 *
 * v1.2：分析期会为每条 BODY/HEADING 行预计算“候选字缝”（`TextLine.cuts`，行内所有安全切点），
 * 供 ×4 的二次重排按需要的段数挑最近的一刀。它不是开关：用户在阅读页随时可能点 ×4，
 * 而分析只做一次。
 */
data class AnalysisOptions(
    /**
     * 分析图宽度上限（P1）。**按宽度定标，不按长边**：长图的长边是高度，用长边定标会把
     * 宽度压成一条（690×10305 → 107×1598），行带、字缝、中线全部失真。
     */
    val analysisMaxWidth: Int = 1600,

    /**
     * 单张分析图（或单条带）的像素预算（P2）。超过就切条带分析，宽度保持不变。
     *
     * **实测口径不是 3 字节/像素**：灰度 1B + 墨迹 1B + 候选 1B + 二值 mask 1B +
     * 竖直游程 **2B**（`BlockClassifier.verticalRuns` 用 `ShortArray`）≈ **7B/像素**；再加上解码期那张
     * ARGB（4B/像素，随后可回收）。8MP 单次分析峰值约 60–70MB——低端机 128MB 堆要留余量，
     * 所以这个阈值不要再往上调。（背景曲面曾经是另一个 4B/像素常驻，现已不返回。）
     */
    val analysisMaxPixels: Int = 8_000_000,

    /** 条带高度（分析像素，P2 建议 2000–4000）与相邻条带的重叠（约一到两行高）。 */
    val stripHeight: Int = 3000,
    val stripOverlap: Int = 48,

    /**
     * 条带并行分析的线程数；0 = 自动，**上限 2**（内存优先，不是 CPU 优先）。
     *
     * 每条并发条带常驻 ≈ 6 字节/像素（ink + 两种 mask + `ShortArray` 游程），
     * 1080×3000 约 16MB；4 并发会把 1080×20000 长图的峰值顶到 ~124MB。要更快可以显式调大，
     * 但要先确认 `:core:longPageMemoryTest`（96MB 堆）仍然通过。
     */
    val stripParallelism: Int = 0,
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

    /** 细竖直装饰列：最小两侧留白（× 行高）。 */
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

    val cutWin0: Float = 0.06f,

    /**
     * 逐行贴回全局列的窗口（× 分析图宽）。
     *
     * 原来 2.5% 太窄：缩进行/字宽不一的行明明贴着全局列，却因为窗口够不着而找不到缝，
     * 只能被压小（audit 的 body_scale_inconsistent）。放宽到 5% 后仍由“两半均衡”代价
     * 与 [ColumnInk.splitsWord] 保护，不会把切点拉飞。
     */
    val cutWin1: Float = 0.05f,
    val cutCleanRatio: Float = 0.70f,

    /**
     * 切点候选的**加权评分**权重（v1.4）。四个权重取相对大小即可，不必和为 1。
     *
     * 旧实现是字典序：先“离目标列最近”，再“缝更宽”。字典序的毛病是 1px 的噪点缝
     * 只要离得近就永远赢过 12px 的真字缝。改成加权后，宽缝可以用“宽度分”补回距离差，
     * 但权重设计成距离仍然占主导，避免全局切分列随参差的右边缘漂移。
     *
     * - [cutWeightCenter]：离目标列（组列/页面列）的远近；
     * - [cutWeightGap]：缝宽（以 `0.35 × 行高` 为满分基准，字距 2–4px 只有一半分）；
     * - [cutWeightBalance]：两半均衡度（逐行切分时它才是主角）；
     * - [cutWeightInk]：缝内最干净那一列的墨量（零墨满分）。
     *
     * `balanceFirst = true`（逐行贴回切点）时 center 与 balance 的权重**互换**：
     * 估算全局列要稳（靠 center），逐行下刀要均衡（靠 balance）。
     */
    val cutWeightCenter: Float = 0.45f,
    val cutWeightGap: Float = 0.30f,
    val cutWeightBalance: Float = 0.15f,
    val cutWeightInk: Float = 0.10f,

    /** 缝宽的满分基准（× 行高）：22px 正文的 `0.35 × 22 ≈ 8px`，字距 2–4px 只有一半分。 */
    val cutGapFullRatio: Float = 0.35f,

    /**
     * 按段落左边距分组的容差（× 版心宽）。同缩进的段落共用一根切分列，
     * 缩进引用/列表项因此也能切；只有 1 行的“孤立缩进行”仍走页面列，不会被单独劈开。
     */
    val splitGroupTolerance: Float = 0.02f,
    val longLineRatio: Float = 0.70f,

    /** P1-1：拉丁/URL 安全切分（零墨空白 + 最小缝宽）。默认关。 */
    val latinSplitEnabled: Boolean = false,
    val latinMinGapRatio: Float = 0.12f,

    /** 墨簇宽度中位数 < 该比例 × 行高 → 判为拉丁/代码行（默认不切）。 */
    val latinLikeClusterRatio: Float = 0.62f,

    /** 且每“字宽”的墨簇数 > 该值（中文约 1，拉丁/代码 2–4），避免粗体中文被误判。 */
    val latinLikeClusterDensity: Float = 1.8f,

    /**
     * v1.3：超高行带的**带内竖切**（图文混排）。
     *
     * 图的纵向范围会让水平投影出一条超高的行带，右侧的几行字被并进同一条带，
     * 之后整条带被判成图块、文字跟着图一起缩放。[BandRegionSplitter] 在带内找一条
     * **贯通整带高度**的空白竖缝，把带拆成左右两个子区，再各自重做行投影与角色判定。
     *
     * 与 `split*` / `cutWin*` 不是同一层：这里切的是**区域**（图 vs 文），
     * 不是行内字缝；两者混用会把照片劈成两半。
     */
    val bandSplitMinHeight: Int = 40,

    /** 竖缝最小宽度（× 分析图宽）：字缝只有 1–4px，图文通道通常 ≥ 1%。 */
    val bandSplitMinGapRatio: Float = 0.008f,

    /** 竖切位置到左右边界的最小距离（× 带宽）：过于靠边就不切，避免切出细条。 */
    val bandSplitMinSideRatio: Float = 0.15f,

    /** 竖缝内每列的墨量上限（× 带高）：允许抗锯齿噪点，但必须基本贯通。 */
    val bandSplitMaxGapInkRatio: Float = 0.02f,

    /**
     * 竖切验收：带高必须 ≥ 该倍数 × 子区文字行高中位数。
     *
     * 没有这条，一条普通文字行里的宽字距也会被当成竖缝，把一句话拆成两行。
     */
    val bandSplitMinHeightToLineRatio: Float = 2.5f,

    /** P1-4：多栏安全网。 */
    val multiColumnMinWidthRatio: Float = 0.05f,
    val multiColumnMaxInkRatio: Float = 0.01f,
    val multiColumnMinCoverage: Float = 0.60f,
    val multiColumnMinSideLines: Int = 2,
)
