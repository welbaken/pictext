package app.pictext.core.model

/**
 * 文字行的版面角色。角色决定“怎么缩放、能不能切”，是 v1.1 的核心字段。
 *
 * - [BODY] 正文/评论/引用：统一按 `bodyScale`，满宽行参与左右切分；
 * - [HEADING] 标题：不小于正文；装不下 `bodyScale` 时允许按同一安全门切分；
 * - [CAPTION] 图注/时间戳/翻译元信息：保持比正文小，不切分；
 * - [UI] 头像、按钮行、图标、logo、工具条：最多 1.5×，绝不切分。
 */
enum class LineRole {
    BODY,
    HEADING,
    CAPTION,
    UI,
}

/** 图块来源。`UI` 与 `DECOR` 不按宽度放大到 2.2×，避免头像/按钮/细装饰被拉糊。 */
enum class FigureKind {
    PHOTO,
    UI,
    DECOR,
}

/**
 * 一条文字行带。坐标为 A 坐标（分析图）。
 *
 * @param band 行带外框：y 范围来自投影，x 范围来自该带内墨迹的最左/最右列。
 * @param cutX 该行的切分列；`-1` 表示没有可用切点。
 * @param split 是否按左右两半切开。
 * @param centered 是否为居中短行（不切分，渲染时居中）。
 * @param role 版面角色；默认 [LineRole.BODY]，保证旧构造调用仍然有效。
 */
data class TextLine(
    val band: RectI,
    val cutX: Int = -1,
    val split: Boolean = false,
    val centered: Boolean = false,
    val role: LineRole = LineRole.BODY,
    /**
     * 二次重排的候选切点：这一行所有**安全字缝**的列坐标（升序）。
     *
     * 与 [split] 无关：`split` 是 ×2 切分门（左对齐、够宽、非 UI）的结论，只决定
     * “这一行要不要在 ×2 里切成左右两半”；这里则是 ×4 二次重排的备用刀位——×4 把
     * ×2 的每条输出行当一张新图再排一次，要按新倍率挑最近的缝切 2 段或 4 段。
     *
     * 空列表表示这一行找不到安全缝，×4 时只能整行放大（会被列宽压小，属已知限制）。
     */
    val cuts: List<Int> = emptyList(),
    /**
     * [v2] 三级切点特征（硬缝 / 软缝 / 强制切用的最小墨量列）与敏感行标记。
     *
     * `null` 表示这一行来自 v1 投影链路，没有 v2 特征；v2（`LayoutStrategy.DOC_LAYOUT`）
     * 的每一行都必须带它，否则规划器只能把它当“不可切”。
     */
    val cutFeatures: LineCutFeatures? = null,
) {
    val height: Int get() = band.height
    val width: Int get() = band.width

    /** 该行是否禁止强制切（拉丁/URL/代码/价格/长数字）。 */
    val sensitive: Boolean get() = cutFeatures?.sensitive == true
}

/** 版面块。文字行按相邻关系聚成段落；非文字块整体放大，绝不切分。 */
sealed interface Block {
    val bounds: RectI

    data class Paragraph(val lines: List<TextLine>) : Block {
        override val bounds: RectI
            get() = lines.fold(RectI.EMPTY) { acc, l -> acc.union(l.band) }
    }

    data class Figure(
        override val bounds: RectI,
        val kind: FigureKind = FigureKind.PHOTO,
    ) : Block
}

/** 分析期诊断信息，只用于调参和本地日志。 */
data class Diagnostics(
    val inkPixels: Int = 0,
    val inkThreshold: Int = 0,
    val rowCount: Int = 0,
    val figureCount: Int = 0,
    val cleanRatio: Float = 0f,
    val notes: List<String> = emptyList(),
    /** 参与切分的行数（cleanRatio 的分母）。 */
    val splitCount: Int = 0,
    /** 其中切点干净的行数（分子）。条带合并按总计数重算 cleanRatio。 */
    val splitClean: Int = 0,
    /** 长行数；条带合并后用它判断 too_few_long_lines。 */
    val longLineCount: Int = 0,
)

/**
 * 一页图片的版面分析结果。矩形坐标由 [strategy] 决定：
 *
 * - [LayoutStrategy.PROJECTION]（v1）：A 坐标（分析图）；
 * - [LayoutStrategy.DOC_LAYOUT]（v2）：**S 坐标**，`analysisScale = 1.0`。
 *
 * @param analysisScale `k = analysisWidth / srcWidth`，用于换算回源图；v2 恒为 1。
 * @param regions v2 的区域列表，已按阅读顺序排好（`order` 单调递增）；v1 为空。
 * @param strategy 哪条分析链路产出的这一页；规划器据此分流。
 */
data class PageLayout(
    val srcWidth: Int,
    val srcHeight: Int,
    val analysisScale: Float,
    val blocks: List<Block>,
    val textLeft: Int,
    val textRight: Int,
    val splitX: Int,
    val cleanRatio: Float,
    val medianLineHeight: Float,
    val medianGap: Float,
    val medianPitch: Float,
    val isDark: Boolean,
    val backgroundColor: Int,
    val diagnostics: Diagnostics,
    val regions: List<LayoutRegion> = emptyList(),
    val strategy: LayoutStrategy = LayoutStrategy.PROJECTION,
) {
    /** 所有文字行，按页面顺序展开。 */
    fun textLines(): List<TextLine> = blocks.flatMap { block ->
        when (block) {
            is Block.Paragraph -> block.lines
            is Block.Figure -> emptyList()
        }
    }

    /** 每个角色的行数，供 CLI/测试输出。 */
    fun roleCounts(): Map<LineRole, Int> =
        textLines().groupingBy { it.role }.eachCount()

    /** 每个图块类型的数量。 */
    fun figureKindCounts(): Map<FigureKind, Int> =
        blocks.filterIsInstance<Block.Figure>().groupingBy { it.kind }.eachCount()

    /** A 坐标矩形换算到 S 坐标（向外取整并裁剪）。 */
    fun toSource(r: RectI): RectI = r.toSourceOutward(
        analysisScale.coerceAtLeast(0.0001f),
        srcWidth,
        srcHeight,
    )

    /** v2：按阅读顺序展开所有区域内的文字行。 */
    fun regionLines(): List<TextLine> = regions.sortedBy { it.order }.flatMap { it.lines }

    /** v2：每个语义类别的区域数。 */
    fun regionKindCounts(): Map<RegionKind, Int> =
        regions.groupingBy { it.kind }.eachCount()

    /** v2：所有文字行的行高中位数（BODY 优先，没有 BODY 时退回全部文字行）。 */
    fun bodyMedianLineHeight(): Float {
        val all = regionLines()
        val body = all.filter { it.role == LineRole.BODY }
        val src = body.ifEmpty { all }
        if (src.isEmpty()) return 0f
        val heights = IntArray(src.size) { src[it].band.height }.sortedArray()
        return heights[heights.size / 2].toFloat()
    }
}
