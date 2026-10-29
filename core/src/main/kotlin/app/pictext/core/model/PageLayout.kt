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
     * [v1.1] 一行切多段时的全部切点（升序，含左右边界之间的内点）。
     * 只填了 2 段时为空，规划器回退到 [cutX]；×4 模式会填 3 个切点 → 4 段。
     */
    val cuts: List<Int> = emptyList(),
) {
    val height: Int get() = band.height
    val width: Int get() = band.width
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
)

/**
 * 一页图片的版面分析结果。所有矩形坐标都是 A（分析图）坐标。
 *
 * @param analysisScale `k = analysisWidth / srcWidth`，用于换算回源图。
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
}
