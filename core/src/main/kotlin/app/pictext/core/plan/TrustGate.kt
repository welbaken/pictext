package app.pictext.core.plan

import app.pictext.core.layout.LayoutOptions
import app.pictext.core.layout.LayoutSummaryStats
import app.pictext.core.model.PageLayout

/**
 * V2-43 可信度闸门：**已停用**（V2-51 起产品链路不再自动回退 v2→v1）。
 *
 * 保留这个类与 `TrustGateDecisionTest` 只作诊断参考：它测过的四个“和 v1 比行数/面积”的
 * 信号，在调试“v2 输出像不像把文字当图片”时仍然有用；但 [SmartReflow] 不再调用它。
 *
 * 历史背景：真值验收发现浅底文章页 v2 很好，但**聊天气泡、海报大字**这类页面会被模型逐行
 * 框成 FIGURE；当时用“v2 行数 < 0.6 × v1 行数”自动回退。盲评（docs/07 §13.6.8）后，
 * 版式改为用户手选，v2 失败直接显示原图，不再偷偷换成 v1。
 */
object TrustGate {

    /** 回退诊断码：文字被框成了图片。 */
    const val CODE = "layout_text_as_figure"

    /**
     * 第一段：值得跑一次 v1 复核吗？
     *
     * 文字类区域几乎铺满页面的（文章/论文）没必要——它们不可能“全是图片”。
     * 统计缺失（`null`）时保守地不复核。
     */
    fun needsCheck(summary: LayoutSummaryStats?, options: LayoutOptions): Boolean {
        if (summary == null) return false
        return summary.textAreaRatio < options.trustCheckTextAreaBelow
    }

    /**
     * 第二段：v2 的行数相对 v1 塌了，就认定“文字被框成图片”。
     *
     * @param v1Layout v1 在同一张分析图上的布局（调用方已经算好，回退时直接复用）。
     * @return true 表示应当整体回退 v1。
     */
    fun looksLikePicture(v2: PageLayout, v1: PageLayout, options: LayoutOptions): Boolean {
        val v1Lines = v1.textLines().size
        // v1 自己都几乎没找到字（纯图页/海报）：无从比较，交给既有的 other 闸门。
        if (v1Lines < options.trustMinV1Lines) return false
        val v2Lines = v2.textLines().size
        // 用千分整数比较而不是 `v2 < 0.6f * v1`：`0.6f` 实际是 0.6000000238，
        // 60 行 vs 100 行会在浮点上差一点点而误触发，边界必须确定。
        val permille = (options.trustLineRatio * 1000f).toInt()
        return v2Lines * 1000 < permille * v1Lines
    }
}
