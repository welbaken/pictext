package app.pictext.core.plan

import app.pictext.core.layout.LayoutOptions
import app.pictext.core.layout.LayoutSummaryStats
import app.pictext.core.model.PageLayout

/**
 * V2-43 可信度闸门：**这一页的 v2 结果是不是“把文字当成了图片”**。
 *
 * ## 为什么需要它
 *
 * 真值验收发现：浅底文章页 v2 很好，但**聊天气泡、海报大字**这类页面会被模型逐行框成 FIGURE。
 * 每个气泡里只有 1–2 行，`RegionKindFixer` 不会翻成 TEXT（它要求 ≥3 行），于是排版结果是
 * 一串图片——内容没丢，但“放大重排”这个卖点完全没兑现。v1 反而会把这些行当文字排。
 *
 * ## 为什么判定要和 v1 比行数
 *
 * 试过四个“只看自己”的信号（文字面积占比、墨迹落在文字区域的比例、FIGURE 里细化出的行占比、
 * 文字区域细化失败率），在 12 张真值夹具上都分不开好坏：
 * `inkInTextRatio` 在深色文章上高达 0.79 却仍漏检，`blockLineRatio` 在正常的卡片流上到 0.44。
 * 唯一稳定的判据是**和 v1 交叉验证**：v1 是这个产品已经在用的实现，它检出的行数就是
 * “这页到底有多少文字”的可用基线。
 *
 * ## 为什么要两段式
 *
 * 跑一次 v1 分析对正常页面是纯浪费（1080×1793 在模拟器上约 150ms）。所以先看
 * [needsCheck]（一个已经在算的面积信号），只有文字面积本来就小的页面才值得复核。
 * 实测 12 张夹具里 6 张会进复核，命中 1 张（聊天）；42 张真实样例零误伤。
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
