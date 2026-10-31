package app.pictext.ui.viewer

import app.pictext.core.model.FallbackReason
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.data.AppSettings
import app.pictext.image.FailureKind

/** 阅读模式。 */
enum class ViewerMode { REFLOW, ORIGINAL }

/** 一页的 UI 状态。 */
data class PageState(
    val key: String,
    val mode: PlanMode = PlanMode.ORIGINAL,
    val plan: ReflowPlan? = null,
    val fallback: FallbackReason = FallbackReason.NONE,
    val loading: Boolean = false,
    val failure: FailureKind? = null,
    val progress: Float = 0f,
    val version: Int = 0,
    /** 生成 [plan] 时用的重排倍率（1.0 = ×2 档，2.0 = ×4 档；双指缩放可在两者之间）。 */
    val multiplier: Float = 1f,
)

/** 阅读页整体状态。所有异常都必须收敛到 [Failed]。 */
sealed interface ViewerUiState {
    data object Loading : ViewerUiState

    data class Ready(
        val pages: List<PageState>,
        val current: Int,
        val showBars: Boolean = true,
        val mode: ViewerMode = ViewerMode.REFLOW,
        /** 底栏三档：原图 / ×2 / ×4。 */
        val level: ViewerLevel = ViewerLevel.X2,
        val settings: AppSettings = AppSettings(),
        val viewportWidth: Int = 1080,
    ) : ViewerUiState

    data class Failed(val kind: FailureKind) : ViewerUiState
}
