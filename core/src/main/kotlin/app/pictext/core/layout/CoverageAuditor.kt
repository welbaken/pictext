package app.pictext.core.layout

import app.pictext.core.analyze.BackgroundEstimator
import app.pictext.core.analyze.Binarizer
import app.pictext.core.model.GrayImage
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.RectI
import app.pictext.core.util.Runs

/**
 * 覆盖审计与残差补区（docs/07 §4.6 / V2-22）。
 *
 * 它不猜“模型漏了什么”，而是把 **墨迹里没有被任何已接受区域覆盖的部分**逐像素量出来，
 * 聚成框交给细化/二次判定补区；量出来的残差太多时由 [CoverageReport.fallbackCode] 触发整页回退。
 * 这是“模型漏检不丢内容”的最后一道闸。
 *
 * ## 为什么覆盖必须在 T 空间（480×480 模型输入）算
 *
 * 1. **墨迹只存在于模型输入里**。tile 是模型真正“看见”的那块像素：`tileGray` 是 480×480，
 *    模型的每个检测框也定义在 0..480 上。只有在同一张栅格上，`ink`（墨迹）和 `covered`
 *    （区域并集）才能逐像素做布尔运算；换到 S 空间，就得把 480 空间里的每个墨点重采样回
 *    1080/20000 的源图，tile 内劣化一次、跨 tile 边界再漏/重一次。
 * 2. **比例一般不是整数**（1080/480 = 2.25）。在 S 空间逐像素统计必须再引入一次取整，
 *    如果取整方向与 [Tile.modelRect] / [Tile.sourceRect] 不一致，边缘上“覆盖到了”和
 *    “没覆盖到”会各说各话，残差框就会沿区域边界长出一圈假墨。统一在 T 空间只做两次映射
 *    （区域框 S→T、残差框 T→S），误差是单向的、可度量的（1080/480 下 ≤1px）。
 * 3. **只跟一个 tile 走**。一页可能有几十个 tile，审计在 480×480 的缓冲上算，内存与耗时
 *    都不随页高增长（§2.2 的立场：不要在 1080×20000 的全页分析图上做任何事）。
 *
 * ## 为什么必须复用 v1 的同一套二值化
 *
 * 审计的分子/分母必须和细化、切行用的是**同一份 ink**。细化（§4.5）走的是
 * [BackgroundEstimator] + [Binarizer]：前者先估背景曲面，深底浅字、渐变阴影、整块深色照片
 * 都不会被误当成“本地背景”而吃掉；后者是 Otsu + 孤立点清理，参数（`inkFloor/inkCeil`）
 * 就是 v1 的黄金基线。如果审计另外写一套（固定阈值、自己算 Otsu、只看灰度差），就会出现
 * 两种典型错判，而且两种都是产品级的错误：
 *
 * - 审计认为“这里有墨”，细化却细化不出行 → 补出一堆假区域，阅读页出现重复内容；
 * - 审计认为“这块已经覆盖”，细化其实没覆盖 → 覆盖率虚高，真正的漏检被藏起来。
 *
 * 所以这里只调用既有实现：宁可多一点耦合，也不要第二个“真相源”。
 */
class CoverageAuditor(private val options: LayoutOptions = LayoutOptions()) {

    private val background = BackgroundEstimator()
    private val binarizer = Binarizer()

    /**
     * 审计一个 tile：墨迹在 T 空间（模型输入 480×480）统计，只算有效区（去掉 padding）。
     *
     * 步骤（§4.6）：
     * 1. 用 v1 的同一套二值化得到 `ink`；
     * 2. 把所有已接受区域映射到 T、裁剪到 [Tile.validModelRect] 后**光栅化成掩膜**
     *    （不是逐区域累加面积：模型框会重叠，累加会把覆盖率算成 >100%）；
     * 3. `uncovered = ink && !covered`；
     * 4. 行投影 → 行区间；每个行区间内列投影 → 列区间；(行区间 × 列区间) 就是一个原始残差框；
     * 5. 丢弃墨点 < [MIN_RESIDUAL_INK] 的框（噪点），再按 [LayoutOptions.residualDilatePx]
     *    膨胀合并，最后映射回 S。
     *
     * @param tileGray 模型输入尺寸的灰度图（`tileGray.width == tile.modelInput`）。
     * @param regions 已接受区域（S 坐标）。
     * @return 该 tile 的统计；`residuals` 已映射回 S 并按 (top, left) 排序，保证确定性。
     */
    fun auditTile(tileGray: GrayImage, tile: Tile, regions: List<LayoutRegion>): TileCoverage {
        require(tileGray.width == tile.modelInput) {
            "tileGray.width=${tileGray.width} != tile.modelInput=${tile.modelInput}"
        }
        require(tileGray.height == tile.modelInput) {
            "tileGray.height=${tileGray.height} != tile.modelInput=${tile.modelInput}"
        }

        val n = tile.modelInput
        // padding 区是模型输入的一部分（padValue=114，灰度上比纸面暗得多），
        // 它“看起来像墨”但不是页面内容；有效区必须先裁到模型输入之内再统计。
        val valid = tile.validModelRect().intersect(RectI(0, 0, n, n))
        if (valid.isEmpty) return TileCoverage(tile.index, 0, 0)

        val ink = inkMask(tileGray)
        val covered = coveredMask(tile, regions, valid, n)

        var inkPixels = 0
        var coveredPixels = 0
        for (y in valid.top until valid.bottom) {
            val base = y * n
            for (x in valid.left until valid.right) {
                val i = base + x
                if (!ink[i]) continue
                inkPixels++
                if (covered[i]) coveredPixels++
            }
        }
        if (inkPixels == 0) return TileCoverage(tile.index, 0, 0)

        val residuals = clusterResiduals(ink, covered, valid, n, tile)
        return TileCoverage(
            tileIndex = tile.index,
            ink = inkPixels,
            covered = coveredPixels,
            residuals = residuals,
        )
    }

    /**
     * 汇总整页：`ink/covered` 直接累加各 tile，`residuals` 按 tile 顺序拼接。
     *
     * 不在这里重算 coverage —— [CoverageReport] 的 `coverage/residualRatio/fallbackCode`
     * 都是从这两个和数派生的，保持单一真相源。
     */
    fun report(tiles: List<TileCoverage>): CoverageReport {
        var ink = 0
        var covered = 0
        val residuals = ArrayList<ResidualRegion>()
        for (t in tiles) {
            ink += t.ink
            covered += t.covered
            residuals.addAll(t.residuals)
        }
        return CoverageReport(ink = ink, covered = covered, residuals = residuals, tiles = tiles)
    }

    /** v1 的同一套墨迹链路：背景曲面 → Otsu → 孤立点清理。 */
    private fun inkMask(gray: GrayImage): BooleanArray {
        val bg = background.estimate(gray)
        return binarizer.binarize(bg.ink, gray.width, gray.height).mask
    }

    /**
     * 区域并集的光栅掩膜（T 空间）。逐像素合并而不是累加区域面积：
     * 跨 tile 的框、图注套在图片里这类重叠是常态，累加会让 `covered > ink`。
     */
    private fun coveredMask(
        tile: Tile,
        regions: List<LayoutRegion>,
        valid: RectI,
        n: Int,
    ): BooleanArray {
        val mask = BooleanArray(n * n)
        for (region in regions) {
            val r = tile.modelRect(region.bounds).intersect(valid)
            if (r.isEmpty) continue
            for (y in r.top until r.bottom) {
                java.util.Arrays.fill(mask, y * n + r.left, y * n + r.right, true)
            }
        }
        return mask
    }

    /**
     * 残差聚类：行投影 → 行区间 → 区间内列投影 → (行 × 列) 原始框 → 噪点过滤 → 膨胀合并。
     *
     * 先在行方向切、再在列方向切，是因为正文的残差几乎总是“整行”的（漏掉的一段文字、
     * 一条图注）；先列后行会把同一行的字拆成按列碎片，合并阶段还得再拼回去。
     */
    private fun clusterResiduals(
        ink: BooleanArray,
        covered: BooleanArray,
        valid: RectI,
        n: Int,
        tile: Tile,
    ): List<ResidualRegion> {
        val w = valid.width
        val h = valid.height

        val rowHasInk = BooleanArray(h)
        for (yy in 0 until h) {
            val base = (valid.top + yy) * n + valid.left
            for (xx in 0 until w) {
                val i = base + xx
                if (ink[i] && !covered[i]) {
                    rowHasInk[yy] = true
                    break
                }
            }
        }

        // 残差框本来就在 src 内：把“页面”取成以 src 右下角为界的矩形即可，
        // 真正的页面裁剪由调用方（区域组装/渲染）负责。
        val page = RectI(0, 0, tile.src.right, tile.src.bottom)
        val raw = ArrayList<ResidualRegion>()
        for (band in Runs.runsOf(rowHasInk)) {
            val colInk = IntArray(w)
            for (yy in band) {
                val base = (valid.top + yy) * n + valid.left
                for (xx in 0 until w) {
                    val i = base + xx
                    if (ink[i] && !covered[i]) colInk[xx]++
                }
            }
            for (cols in Runs.runsOf(BooleanArray(w) { colInk[it] > 0 })) {
                var count = 0
                for (xx in cols) count += colInk[xx]
                if (count < MIN_RESIDUAL_INK) continue
                val bounds = tile.sourceRect(
                    (valid.left + cols.first).toFloat(),
                    (valid.top + band.first).toFloat(),
                    (valid.left + cols.last + 1).toFloat(),
                    (valid.top + band.last + 1).toFloat(),
                    page,
                )
                if (bounds.isEmpty) continue
                raw.add(ResidualRegion(bounds, count))
            }
        }
        return mergeResiduals(raw)
    }

    /**
     * 反复合并，直到稳定：两个框各膨胀 [LayoutOptions.residualDilatePx] 后相交或接触，
     * 就 union 成一个框，墨点数相加。
     *
     * 膨胀只用于“是否相邻”的判定，**不写进输出**——输出框必须贴着真实墨迹，
     * 否则补出来的区域会带一圈空白（分栏、行距都会被它污染）。
     */
    private fun mergeResiduals(raw: List<ResidualRegion>): List<ResidualRegion> {
        var current = raw.sortedWith(RESIDUAL_ORDER)
        while (true) {
            val used = BooleanArray(current.size)
            val next = ArrayList<ResidualRegion>(current.size)
            var changed = false
            for (i in current.indices) {
                if (used[i]) continue
                used[i] = true
                var bounds = current[i].bounds
                var count = current[i].ink
                // 一个簇吞下新框后可能又挨到别的框，所以这里要反复扫到不再增长。
                var grew = true
                while (grew) {
                    grew = false
                    for (j in current.indices) {
                        if (used[j]) continue
                        if (!adjacent(bounds, current[j].bounds)) continue
                        bounds = bounds.union(current[j].bounds)
                        count += current[j].ink
                        used[j] = true
                        grew = true
                        changed = true
                    }
                }
                next.add(ResidualRegion(bounds, count))
            }
            current = next.sortedWith(RESIDUAL_ORDER)
            if (!changed) return current
        }
    }

    /** 两个框各自膨胀 `residualDilatePx` 后是否相交或接触。 */
    private fun adjacent(a: RectI, b: RectI): Boolean {
        val d = options.residualDilatePx.coerceAtLeast(0)
        return a.left - d <= b.right + d &&
            b.left - d <= a.right + d &&
            a.top - d <= b.bottom + d &&
            b.top - d <= a.bottom + d
    }

    companion object {
        /** 残差框的最小墨点数；更少的当噪点丢弃（孤立笔划/压缩噪点）。 */
        const val MIN_RESIDUAL_INK: Int = 4
    }
}

/** 残差框的确定性顺序：先上后下、再左后右，最后用右下角兜底。 */
private val RESIDUAL_ORDER: Comparator<ResidualRegion> = compareBy<ResidualRegion>(
    { it.bounds.top },
    { it.bounds.left },
    { it.bounds.bottom },
    { it.bounds.right },
)
