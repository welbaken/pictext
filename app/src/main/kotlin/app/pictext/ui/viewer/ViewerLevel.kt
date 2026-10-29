package app.pictext.ui.viewer

/** 阅读页底栏的三档：原图 / ×2（算法重排）/ ×4（在 ×2 的基础上再放大一倍）。 */
enum class ViewerLevel {
    ORIGINAL,
    X2,
    X4;

    val isReflow: Boolean get() = this != ORIGINAL
}