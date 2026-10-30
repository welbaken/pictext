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
 */
data class ReflowOptions(
    val outputWidth: Int,
    val targetEm: Int = 44,
    val insetRatio: Float = 0.018f,
    val maxScale: Float = 3.0f,
    val minGain: Float = 1.25f,
    val figMaxScale: Float = 2.2f,
    val gapClamp: IntRange = 6..40,
    val paragraphGapRatio: Float = 1.35f,
    val splitEnabled: Boolean = true,
    val forceTwo: Boolean = false,
    val forceReflow: Boolean = false,
    val scaleMultiplier: Float = 1f,
    val maxSegments: Int = 2,

    /** D-C：UI 行（头像、按钮、图标、logo）的最大放大倍数。 */
    val uiMaxScale: Float = 1.5f,
)
