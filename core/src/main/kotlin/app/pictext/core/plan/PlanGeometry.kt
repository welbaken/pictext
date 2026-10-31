package app.pictext.core.plan

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 输出几何（O 坐标）：屏幕宽 [outW]、左右边距 [inset]、版心宽 [contentW]。
 *
 * v1（[ReflowPlanner]）与 v2（[planDocLayout]）共用同一套换算。放在这里而不是各自算一份，
 * 是因为两边的间距/居中/threshold 全部以 `contentW` 为基准，公式漂移会让 CLI 与 App 分叉。
 */
internal data class PlanGeometry(val outW: Int, val inset: Int, val contentW: Int) {
    companion object {
        fun of(options: ReflowOptions): PlanGeometry {
            val outW = options.outputWidth.coerceAtLeast(1)
            val inset = max(10, (options.insetRatio * outW).roundToInt())
            return PlanGeometry(outW = outW, inset = inset, contentW = max(1, outW - 2 * inset))
        }
    }
}
