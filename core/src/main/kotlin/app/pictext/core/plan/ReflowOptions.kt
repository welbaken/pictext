package app.pictext.core.plan

/**
 * 重排计划参数。`outputWidth` 是屏幕像素宽，旋转/分屏后需要重新规划（不必重新分析）。
 *
 * @param splitEnabled 用户设置里关闭切分时为 false，整页只放大。
 * @param forceTwo 调试/特殊版面：忽略短行门槛，所有非居中文字行都切。
 * @param forceReflow 用户明确点了“×2/×4”时打开：跳过“长行不足/收益不足/多栏”这些前置判断，
 *   只要图上还有文字行就照着重排（切点不安全时退化为整页放大，而不是整页原图）。
 * @param scaleMultiplier 在算法算出的 bodyScale 上再乘一个系数（×4 = 2.0，即“在 ×2 的基础上再放大一倍”）。\n * @param maxSegments 一行最多切成几段：2 = 左右对半；4 = 半行仍放不下时再对半（×4 模式）。
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
