package app.pictext.ui.viewer

/**
 * 阅读页底栏的三档：原图 / ×2（算法重排）/ ×4（把 ×2 的结果再排一次）。
 *
 * 两档重排是**同一条连续缩放轴**的两端（见 [baseMultiplier]）：双指缩放可以在两者之间
 * 连续移动，底栏按钮是这条轴上的预设。
 */
enum class ViewerLevel {
    ORIGINAL,
    X2,
    X4;

    val isReflow: Boolean get() = this != ORIGINAL

    /** 该档在缩放轴上的基准倍率：×2 = 1.0，×4 = 2.0（×4 就是“基于 ×2 再放大一倍”）。 */
    val baseMultiplier: Float get() = when (this) {
        ORIGINAL -> 1f
        X2 -> 1f
        X4 -> 2f
    }

    companion object {
        /** 缩放轴下限：再小就是 ×2 的基准倍率，更小应该切原图。 */
        const val MIN_MULTIPLIER = 1f

        /**
         * 缩放轴上限 = ×4 的基准倍率。再往上，一条满宽行需要切 5 段以上，
         * 二次重排只能把字号压小，同页一个字号的前提就没了。
         */
        const val MAX_MULTIPLIER = 2f
    }
}
