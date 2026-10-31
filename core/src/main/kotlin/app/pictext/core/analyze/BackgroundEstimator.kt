package app.pictext.core.analyze

import app.pictext.core.model.GrayImage
import app.pictext.core.util.Stats
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 背景曲面估计结果。 */
class BackgroundResult(
    /** 0..255 的墨迹强度。 */
    val ink: ByteArray,
    /** 是否为深色页面（深底浅字）。 */
    val isDark: Boolean,
) {
    fun inkAt(x: Int, y: Int, width: Int): Int = ink[y * width + x].toInt() and 0xFF
}

/**
 * Step 2：用接近纸色的像素估计背景曲面，再求墨迹强度。
 *
 * 不能对每个网格直接取 90 分位：深色图表会占满格子，把自己变成"本地背景"，
 * 结果整块图被吃掉，只剩边缘噪声。这里先由全图分位数取纸色种子，只让接近
 * 纸色的像素参与网格中值，空网格用种子补齐后做 3×3 中值，最后双线性放大。
 */
class BackgroundEstimator(private val options: AnalysisOptions = AnalysisOptions()) {

    fun estimate(image: GrayImage): BackgroundResult {
        val w = image.width
        val h = image.height
        if (w == 0 || h == 0) {
            return BackgroundResult(ByteArray(0), false)
        }
        val gray = image.data
        val n = w * h

        // 1. 边界中位数决定亮/暗页
        val borderCount = 2 * w + 2 * h
        val borderHist = IntArray(256)
        for (x in 0 until w) {
            borderHist[gray[x].toInt() and 0xFF]++
            borderHist[gray[(h - 1) * w + x].toInt() and 0xFF]++
        }
        for (y in 0 until h) {
            borderHist[gray[y * w].toInt() and 0xFF]++
            borderHist[gray[y * w + w - 1].toInt() and 0xFF]++
        }
        val borderMedian = Stats.percentile(borderHist, 50)
        val dark = borderMedian < 110

        // 2. 纸色种子
        val globalHist = IntArray(256)
        for (i in 0 until n) globalHist[gray[i].toInt() and 0xFF]++
        val seed = Stats.percentile(
            globalHist,
            if (dark) options.bgPercentileDark else options.bgPercentileLight,
        )

        // 3. 候选纸面像素
        val cand = BooleanArray(n)
        for (i in 0 until n) {
            val g = gray[i].toInt() and 0xFF
            cand[i] = if (dark) g <= seed + options.bgCandidateDelta else g >= seed - options.bgCandidateDelta
        }

        // 4. 32×32 网格的候选中值；小图自动缩小网格，避免一张 64×64 测试图只剩 1 个格子，
        //    也保证浅色卡片至少有 3×3 个网格，不会被后续 3×3 中值吃掉。
        val cell = options.bgCell.coerceAtLeast(4).coerceAtMost(max(4, min(w, h) / 8))
        val nx = max(1, w / cell)
        val ny = max(1, h / cell)
        val grid = FloatArray(nx * ny) { Float.NaN }
        val need = max(12, (options.bgMinCellRatio * cell * cell).toInt())
        val cellHist = IntArray(256)
        for (gy in 0 until ny) {
            val y0 = gy * cell
            val y1 = min(h, y0 + cell)
            for (gx in 0 until nx) {
                val x0 = gx * cell
                val x1 = min(w, x0 + cell)
                java.util.Arrays.fill(cellHist, 0)
                var count = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        if (cand[row + x]) {
                            cellHist[gray[row + x].toInt() and 0xFF]++
                            count++
                        }
                    }
                }
                if (count >= need) grid[gy * nx + gx] = Stats.percentile(cellHist, 50).toFloat()
            }
        }

        // 5. 补洞 + 3×3 中值
        for (i in grid.indices) if (grid[i].isNaN()) grid[i] = seed.toFloat()
        val filtered = FloatArray(nx * ny)
        val window = FloatArray(9)
        for (gy in 0 until ny) {
            for (gx in 0 until nx) {
                var k = 0
                for (dy in -1..1) {
                    val yy = (gy + dy).coerceIn(0, ny - 1)
                    for (dx in -1..1) {
                        val xx = (gx + dx).coerceIn(0, nx - 1)
                        window[k++] = grid[yy * nx + xx]
                    }
                }
                window.sort()
                filtered[gy * nx + gx] = window[4]
            }
        }

        // 6. 双线性放大 + 7. ink
        //
        // **只留 ink**：背景曲面本身（FloatArray，4 字节/像素）除了调试没有任何读者。
        // 早期把它放进返回值，LayoutAnalyzer 会一直持有到整页分析结束——8MP 分析图就是
        // 32MB 常驻，正好压在 BlockClassifier 分配竖直游程（又一个 4B/px）的那一刻。
        val ink = ByteArray(n)
        for (y in 0 until h) {
            val sy = (y + 0.5f) * ny / h - 0.5f
            val y0 = floor(sy).toInt().coerceIn(0, ny - 1)
            val y1 = (y0 + 1).coerceAtMost(ny - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)
            val rowBase = y * w
            for (x in 0 until w) {
                val sx = (x + 0.5f) * nx / w - 0.5f
                val x0 = floor(sx).toInt().coerceIn(0, nx - 1)
                val x1 = (x0 + 1).coerceAtMost(nx - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val v00 = filtered[y0 * nx + x0]
                val v10 = filtered[y0 * nx + x1]
                val v01 = filtered[y1 * nx + x0]
                val v11 = filtered[y1 * nx + x1]
                val topV = v00 + (v10 - v00) * fx
                val bottomV = v01 + (v11 - v01) * fx
                val bgv = topV + (bottomV - topV) * fy
                val g = gray[rowBase + x].toInt() and 0xFF
                val diff = if (dark) g - bgv else bgv - g
                ink[rowBase + x] = diff.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return BackgroundResult(ink, dark)
    }

    /** 供测试使用：判断两个值是否在容差内。 */
    internal fun near(a: Int, b: Int, tolerance: Int = 2): Boolean = abs(a - b) <= tolerance
}
