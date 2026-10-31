package app.pictext.core.analyze

import app.pictext.core.model.LineRole
import kotlin.math.max

/**
 * 一条行带的几何特征。全部来自 `mask` / `vrun` 的一次扫描，不引入连通域。
 *
 * @param density 全页相对墨密度 `ink / (height × pageWidth)`，与图块判据同口径。
 * @param maxRun 带内最长竖直墨迹。
 * @param longFrac 超过 `figRunRatio × 行高` 的像素占比。
 * @param solidRun 最宽的“实心列组”宽度：连续列且每列墨占比 ≥ 0.6（头像、图标、色块）。
 * @param maxGap 带内最大横向空白宽度（列）。
 */
data class RowFeatures(
    val height: Int,
    val width: Int,
    val heightRatio: Float,
    val widthRatio: Float,
    val density: Float,
    val maxRun: Int,
    val longFrac: Float,
    val solidRun: Int,
    val maxGap: Int,
)

/**
 * P0-2 的行级角色分类：BODY / HEADING / CAPTION / UI。
 *
 * 判定顺序刻意把 UI 放在最前：**分类不确定时宁可 UI 也不切**。UI 行只缩放到 1.5×，
 * 内容不丢；把头像/按钮行当正文切开，会把图标裁成两半，是不可逆的破坏。
 */
class RowClassifier(private val options: AnalysisOptions = AnalysisOptions()) {

    fun features(
        mask: BooleanArray,
        width: Int,
        band: RowBand,
        medianHeight: Float,
        vrun: ShortArray,
        pageWidth: Int,
    ): RowFeatures {
        val h = max(1, band.height)
        val bw = max(1, band.width)
        val area = h * width
        val density = if (area == 0) 0f else band.inkCount.toFloat() / area

        var maxRun = 0
        var longPixels = 0
        val longLimit = options.figRunRatio * medianHeight
        for (y in band.y0 until band.y1) {
            val base = y * width
            for (x in band.x0 until band.x1) {
                val r = vrun[base + x].toInt()
                if (r > maxRun) maxRun = r
                if (r > longLimit) longPixels++
            }
        }
        val longFrac = if (h * bw == 0) 0f else longPixels.toFloat() / (h * bw)

        val colInk = IntArray(bw)
        for (y in band.y0 until band.y1) {
            val base = y * width
            for (x in band.x0 until band.x1) {
                if (mask[base + x]) colInk[x - band.x0]++
            }
        }
        var solidRun = 0
        var cur = 0
        val solidThreshold = 0.85f * h
        for (i in 0 until bw) {
            if (colInk[i] >= solidThreshold) {
                cur++
                if (cur > solidRun) solidRun = cur
            } else {
                cur = 0
            }
        }

        var maxGap = 0
        var first = 0
        while (first < bw && colInk[first] == 0) first++
        var last = bw - 1
        while (last > first && colInk[last] == 0) last--
        var gapRun = 0
        for (i in first..last) {
            if (colInk[i] == 0) {
                gapRun++
                if (gapRun > maxGap) maxGap = gapRun
            } else {
                gapRun = 0
            }
        }

        return RowFeatures(
            height = h,
            width = bw,
            heightRatio = h / medianHeight,
            widthRatio = bw.toFloat() / max(1, pageWidth),
            density = density,
            maxRun = maxRun,
            longFrac = longFrac,
            solidRun = solidRun,
            maxGap = maxGap,
        )
    }

    /** 按“UI 优先”的顺序判定角色。 */
    fun role(f: RowFeatures, medianHeight: Float): LineRole {
        if (f.solidRun >= options.roleUiSolidRunRatio * medianHeight) return LineRole.UI
        if (f.heightRatio >= 1.2f && f.solidRun >= 0.5f * medianHeight) return LineRole.UI
        if (f.maxGap >= options.roleUiGapRatio * medianHeight && f.widthRatio >= 0.5f) {
            return LineRole.UI
        }
        if (f.maxGap >= 3.0f * medianHeight) return LineRole.UI
        if (f.heightRatio >= options.roleHeadingHeightRatio &&
            f.widthRatio >= options.roleHeadingMinWidthRatio
        ) {
            return LineRole.HEADING
        }
        if (f.heightRatio <= options.roleCaptionHeightRatio) return LineRole.CAPTION
        return LineRole.BODY
    }

}
