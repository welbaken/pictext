package app.pictext.core.plan

/**
 * 重排计划参数。`outputWidth` 是屏幕像素宽，旋转/分屏后需要重新规划（不必重新分析）。
 *
 * @param splitEnabled 用户设置里关闭切分时为 false，整页只放大。
 * @param forceTwo 调试/特殊版面：忽略短行门槛，所有非居中文字行都切。
 * @param forceReflow 用户明确点了“×2/×4”时打开：跳过“长行不足/收益不足/多栏”这些前置判断，
 *   只要图上还有文字行就照着重排（切点不安全时退化为整页放大，而不是整页原图）。
 * @param scaleMultiplier 在算法算出的 bodyScale 上再乘一个系数（×4 = 2.0）。大于 1 时规划器走
 *   **二次重排**：把 ×2 的结果当一张新图，字号翻倍后放不下的行按候选字缝再切 2/4 段，
 *   而不是简单把倍率乘上去再压回列宽（那样同页会出现两三种字号）。
 * @param maxSegments 二次重排时一行最多切几段：4 = 需要时切 4 段；2 = 只对半。
 * @param maxSegmentsPerLine [v2] 单行软上限：超过就压缩该行，不降全页 `s`。
 * @param hardMaxSegments [v2] 单行硬上限；需要更多段时优先给满硬上限再压缩。
 * @param forcedCutPerLine [v2] 每行最多几个“劈字”切点。
 * @param forcedCutPageRatio [v2] 全页强制切行占比目标（≤10% 合格，>15% 回退 v1）。
 * @param forcedCutFailRatio [v2] 强制切行占比的失败线（超过就整页回退 v1）。
 * @param sensitiveCompressedRatio [v2] 敏感行压缩占比失败线（>5% 回退 v1）。
 * @param regionGapRatio [v2] 区域间距 = `regionGapRatio × 输出字高`，与行距取大者。
 * @param headingMaxBodyRatio [v2] 标题输出字高上限（相对正文输出字高）；`0` = 不设上限
 *   （D-V2-9 默认按“统一倍率”执行，此字段等产品拍板后再打开）。
 */
data class ReflowOptions(
    val outputWidth: Int,
    val targetEm: Int = 44,
    val insetRatio: Float = 0.018f,
    val maxScale: Float = 3.0f,
    val minGain: Float = 1.25f,
    val figMaxScale: Float = 2.2f,

    /** 窄图阈值（相对页宽）：比它更窄的图块视为图标/头像/气泡，**保留左右位置**；
     * 更宽的是正文插图，居中排版。0.35 来自实测：聊天头像 ~5%、气泡 ~40–90%、
     * 文档插图通常 ≥50%。 */
    val narrowFigureRatio: Float = 0.35f,
    val gapClamp: IntRange = 6..40,
    val paragraphGapRatio: Float = 1.35f,
    val splitEnabled: Boolean = true,
    val forceTwo: Boolean = false,
    val forceReflow: Boolean = false,
    val scaleMultiplier: Float = 1f,
    val maxSegments: Int = 2,

    /** D-C：UI 行（头像、按钮、图标、logo）的最大放大倍数。 */
    val uiMaxScale: Float = 1.5f,

    /** [v2] 单行软上限：需要更多段时给满软上限再压缩该行，不降全页倍率。 */
    val maxSegmentsPerLine: Int = 6,

    /** [v2] 单行硬上限（含强制切后的总段数）。 */
    val hardMaxSegments: Int = 8,

    /** [v2] 每行最多几个强制切点。 */
    val forcedCutPerLine: Int = 1,

    /** [v2] 全页强制切行占比目标。 */
    val forcedCutPageRatio: Float = 0.10f,

    /** [v2] 强制切行占比失败线：超过则整页回退 v1。 */
    val forcedCutFailRatio: Float = 0.15f,

    /** [v2] 敏感行（拉丁/URL/代码/价格）压缩占比失败线。 */
    val sensitiveCompressedRatio: Float = 0.05f,

    /** [v2] 区域间距系数（× 输出字高）。 */
    val regionGapRatio: Float = 0.5f,

    /** [v2] 标题输出字高上限（× 正文输出字高）；`0` = 不设上限。 */
    val headingMaxBodyRatio: Float = 0f,
)

