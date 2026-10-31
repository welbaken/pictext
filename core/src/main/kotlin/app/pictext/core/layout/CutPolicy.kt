package app.pictext.core.layout

import app.pictext.core.model.CutKind
import app.pictext.core.model.InkProfile
import app.pictext.core.model.LineCutFeatures
import app.pictext.core.model.TextLine
import app.pictext.core.plan.ReflowOptions
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 一行的三级切点决策（docs/07 §4.8.2）：**硬缝 → 软缝 → 强制**。
 *
 * 规划期全页只有一个倍率 `s`：某行按 `s` 放大后放不进输出列宽，就切成 2..`hardMaxSegments` 段，
 * 而不是把整页 `s` 压小。切点按“对内容伤害从小到大”依次退让：
 *
 * 1. [CutKind.HARD] 硬缝：整行为零墨的列组，宽度 ≥ `max(2, 0.10×行高)`，永远不会破坏字形；
 * 2. [CutKind.SOFT] 软缝：低墨列组（字与字之间的自然缝），只在没有硬缝时用；
 * 3. [CutKind.FORCED] 强制：目标附近墨量最小且是局部最小的单列，可能从笔画中间断开。
 *
 * ### 为什么强制切要有这么多前置条件
 *
 * 强制切是“可读性优先”的例外，不是默认策略：一行 46 字、`s=2`，对半劈开只坏 1 个字却换来
 * 全页 2×，对汉字/假名正文这笔账划算；但对 `¥1,299`、URL、代码、英文单词，切错是**语义错误**
 * 而不是观感问题。所以只有“汉字主导 + 墨密度正常 + 笔画不竖长 + 切点两侧不是都窄”的行才允许，
 * 并且每行最多 [ReflowOptions.forcedCutPerLine] 刀（默认 1）——不允许一行三个刀口全在劈字。
 *
 * ### 为什么窗口是 ±8% 行宽
 *
 * 切点是为“均分”服务的：偏离理想分界点太远，输出的几段宽度差会直接被读者看出来；
 * 而 ±8% 已经足够覆盖一个字宽（汉字约 1 个行高，满行通常有 6–10 个行高），
 * 所以不会因为窗口太窄而错过真正的字缝，也不会退化成“在附近随便找一列切”。
 *
 * ### 为什么 sensitive 必须零强制
 *
 * `sensitive`（拉丁 / URL / 代码 / 价格 / 长数字）被劈开后**无法靠上下文还原**：
 * `¥1,299` 切成 `¥1,2` + `99` 会读成另一个价格。因此 [LineCutFeatures.sensitive] 为 true 时
 * 绝不出现 [CutKind.FORCED]，宁可整行压缩（占比超过
 * [ReflowOptions.sensitiveCompressedRatio] 时由上层回退 v1）。
 *
 * 本类无状态：同一页可以按不同 [ReflowOptions.outputWidth] 反复调用 [decide]
 * （旋转/分屏只重规划，不重新分析）。
 */
class CutPolicy(private val options: ReflowOptions = ReflowOptions(outputWidth = 1080)) {

    /**
     * 决定一行切成几段、每段在哪里断开。
     *
     * @param contentWidth 输出列宽（像素）
     * @param scale 全页统一倍率 s（>0）
     * @param allowForced 该行所在区域是否允许强制切（只有 TEXT/TITLE 为 true）
     * @param maxSegments 软上限，默认 options.maxSegmentsPerLine
     * @param hardMaxSegments 硬上限，默认 options.hardMaxSegments
     */
    fun decide(
        line: TextLine,
        contentWidth: Int,
        scale: Float,
        allowForced: Boolean,
        maxSegments: Int = options.maxSegmentsPerLine,
        hardMaxSegments: Int = options.hardMaxSegments,
    ): CutDecision {
        val width = line.band.width
        if (width <= 0) return CutDecision.single(line)

        // `s > 0` 是调用契约；防御性处理 0/负数/NaN，避免除零后把整页算成天文数字段数。
        if (contentWidth <= 0 || scale <= 0f || !scale.isFinite()) return CutDecision.single(line)

        val needed = idealSegments(width, scale, contentWidth)
        if (needed <= 1) return CutDecision.single(line, compressed = false, needed = 1)

        // 段数上限：软上限与硬上限取小。
        val cap = min(maxSegments, hardMaxSegments)

        // 第一轮：从 `needed` **往上**试，找到“每一段都放得下 `s`”的最少段数。
        //
        // 为什么不是从 needed 往下试：理想等分时 `w/pieces × s ≤ contentW` 恰好成立，
        // 但挑刀有 ±8% 的窗口余量，落在窗口边缘的一刀会让某一段比理想宽 8%，
        // 那一段就只能被压缩（字号比同页小一截，审计会判 scale_inconsistent）。
        // 段数加一反而能把这一段拆开、继续保持全页同一个 `s`——“宁可多切，不降字号”。
        for (pieces in needed..cap) {
            val cuts = pickCuts(line, pieces, allowForced) ?: continue
            val segments = segmentsFrom(line, cuts)
            if (fits(segments, scale, contentWidth)) {
                return CutDecision(segments, compressed = false, needed = needed)
            }
        }
        // 第二轮：段数不够（`needed > cap` 或找不到合适刀口）→ 从少往多退，
        // 只要段数少于 needed，某一段必然超宽；这里只求“尽量不整行压缩”，
        // 超宽的那一段由规划器压缩并计入 `compressedLines`。
        for (pieces in minOf(needed - 1, cap) downTo 2) {
            val cuts = pickCuts(line, pieces, allowForced) ?: continue
            return CutDecision(segmentsFrom(line, cuts), compressed = false, needed = needed)
        }
        // 一段都放不下（或找不到任何刀口）→ 该行压缩，不拉低全页 s。
        return CutDecision.single(line, compressed = true, needed = needed)
    }

    /** 每一段在自己的倍率下都要放得进列宽（+0.5px 容差，吸收取整误差）。 */
    private fun fits(segments: List<Segment>, scale: Float, contentWidth: Int): Boolean {
        val limit = contentWidth + 0.5f
        return segments.all { it.width * scale <= limit }
    }

    // -----------------------------------------------------------------------
    // 选刀
    // -----------------------------------------------------------------------

    /** 一刀：绝对 S 列 + 这一刀的级别。 */
    private data class Cut(val x: Int, val kind: CutKind)

    /**
     * 为 [pieces] 段挑 `pieces - 1` 刀；任何一个理想分界点挑不到刀就返回 `null`，
     * 由调用方减少段数重试。
     */
    private fun pickCuts(line: TextLine, pieces: Int, allowForced: Boolean): List<Cut>? {
        val left = line.band.left
        val right = line.band.right
        val width = right - left
        // 每段至少 1 列，否则“切”没有意义。
        if (width < pieces) return null

        val height = line.band.height
        val window = max(2, (WINDOW_RATIO * width).roundToInt())
        val features = line.cutFeatures
        val cuts = ArrayList<Cut>(pieces - 1)
        var forcedUsed = 0

        for (i in 1 until pieces) {
            val target = left + (width.toLong() * i / pieces).toInt()

            // 窗口夹在 [left+1, right-1]：切在行首/行尾会产生 0 宽段。
            var low = max(left + 1, target - window)
            val high = min(right - 1, target + window)
            // 严格递增：后一刀必须落在前一刀右侧至少 1 列。
            if (cuts.isNotEmpty()) low = max(low, cuts.last().x + 1)
            if (low > high) return null

            val cut: Cut = if (features == null) {
                // v1 行没有剖面特征：只认 line.cuts（安全字缝列），绝不强制。
                v1Cut(line, target, low, high)
            } else {
                // 拉丁行（英文单词/代码/URL）的刀口门槛要更严：抗锯齿会把词内字缝也
                // 做成 1–3px 的零墨列，按汉字行的 0.10×行高去切，就会把 "Andrius"
                // 切成 "Andriu" + "s"（V2-47 实测）。拉丁词的分界是**词间空格**，
                // 宽度通常 ≥0.25×行高，用 0.28 做门槛既避开字缝，又放得过词间空格。
                val hardMinGap = if (features.latinLike) {
                    // 拉丁行：词间空格约占 0.2–0.35×行高，词内字缝只有 1–3px。
                    // 0.18×行高加 3px 地板，既能吃掉字缝，也不会在大字号标题上把
                    // 合格的词间空格也拒掉（0.28 时会把 59px 高的标题全判成“切不动”）。
                    max(3, (LATIN_GAP_RATIO * height).roundToInt())
                } else {
                    max(2, (HARD_GAP_RATIO * height).roundToInt())
                }
                hardCut(features, height, target, low, high, hardMinGap)
                    ?: if (features.latinLike) null else softCut(features, target, low, high)
                    ?: if (allowForced && canForce(line, features, height, forcedUsed)) {
                        forcedCut(features, height, target, low, high)?.also { forcedUsed++ }
                    } else {
                        null
                    }
            } ?: return null
            cuts.add(cut)
        }
        return cuts
    }

    /** 理想段数：`ceil(行宽 × s / 输出列宽)`。 */
    private fun idealSegments(width: Int, scale: Float, contentWidth: Int): Int {
        val raw = width.toDouble() * scale.toDouble() / contentWidth.toDouble()
        if (!raw.isFinite() || raw >= Int.MAX_VALUE.toDouble()) return Int.MAX_VALUE
        val n = ceil(raw)
        return if (n <= 1.0) 1 else n.toInt()
    }

    // -----------------------------------------------------------------------
    // 一级：硬缝
    // -----------------------------------------------------------------------

    /**
     * 硬缝：`hardGaps` 中与目标最近、宽度达标的零墨列组中心列。
     *
     * 要求“中心列”落在窗口内（而不是“区间与窗口相交”）：段宽由切点列决定，
     * 只有落点本身在窗口内，均分才有意义。
     */
    private fun hardCut(
        features: LineCutFeatures,
        height: Int,
        target: Int,
        low: Int,
        high: Int,
        minGap: Int,
    ): Cut? {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (gap in features.hardGaps) {
            val gapWidth = gap.last - gap.first + 1
            if (gapWidth < minGap) continue
            val center = gapCenter(gap)
            if (center < low || center > high) continue
            val distance = abs(center - target)
            if (distance < bestDistance) {
                best = center
                bestDistance = distance
            }
        }
        return if (best < 0) null else Cut(best, CutKind.HARD)
    }

    private fun gapCenter(gap: IntRange): Int = (gap.first + gap.last + 1) / 2

    // -----------------------------------------------------------------------
    // 二级：软缝
    // -----------------------------------------------------------------------

    /** 软缝：窗口内离目标最近的软缝中心列。 */
    private fun softCut(
        features: LineCutFeatures,
        target: Int,
        low: Int,
        high: Int,
    ): Cut? {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (gap in features.softGaps) {
            if (gap.last < gap.first) continue
            val center = gapCenter(gap)
            if (center < low || center > high) continue
            val distance = abs(center - target)
            if (distance < bestDistance) {
                best = center
                bestDistance = distance
            }
        }
        return if (best < 0) null else Cut(best, CutKind.SOFT)
    }

    // -----------------------------------------------------------------------
    // 三级：强制
    // -----------------------------------------------------------------------

    /**
     * 强制切的全部准入条件（缺一不可）。理由见类注释：
     * 强制切会劈字，只对“汉字正文、墨迹不像图、且这一刀两侧不是都窄”的行开放。
     */
    private fun canForce(
        line: TextLine,
        features: LineCutFeatures,
        height: Int,
        forcedUsed: Int,
    ): Boolean {
        if (forcedUsed >= options.forcedCutPerLine) return false
        if (line.sensitive || features.sensitive) return false
        if (features.latinLike || features.hasUrlOrCode || features.digitHeavy) return false
        if (!features.cjkDominant) return false
        if (features.density >= MAX_FORCED_DENSITY) return false
        if (features.maxRun >= (MAX_FORCED_RUN_RATIO * height).roundToInt()) return false
        return true
    }

    /**
     * 在窗口内按墨量升序取第一列（同墨量时取更靠近目标、再靠左的列），
     * 该列必须同时满足：
     *
     * - 墨量 ≤ 1.5（否则是笔画实体）；
     * - 局部最小（墨量 ≤ 左右邻居）；
     * - 切点两侧的紧邻墨簇**不能都窄**：任一侧存在宽度 ≥ `0.6×行高` 的墨簇即放行。
     *   两侧都窄时，这一刀很可能落在数字/字母或汉字偏旁内部，换列或放弃。
     *
     * 没有 [InkProfile] 时无法验证“局部最小”和“墨量 ≤ 1.5”，因此一律放弃强制切
     * （v1 行本来也走不到这里）。
     */
    private fun forcedCut(
        features: LineCutFeatures,
        height: Int,
        target: Int,
        low: Int,
        high: Int,
    ): Cut? {
        val profile = features.profile ?: return null
        val from = max(low, profile.left)
        val to = min(high, profile.right - 1)
        if (from > to) return null

        var bestX = -1
        var bestInk = Int.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        for (x in from..to) {
            val ink = profile.inkAt(x)
            if (ink > MAX_FORCED_INK) continue
            if (ink > profile.inkAt(x - 1) || ink > profile.inkAt(x + 1)) continue
            if (!adjacentClusterAllows(profile, x, height)) continue
            val distance = abs(x - target)
            val better = ink < bestInk ||
                (ink == bestInk && (distance < bestDistance ||
                    (distance == bestDistance && (bestX < 0 || x < bestX))))
            if (better) {
                bestX = x
                bestInk = ink
                bestDistance = distance
            }
        }
        return if (bestX < 0) null else Cut(bestX, CutKind.FORCED)
    }

    /**
     * “切点两侧不能都是窄墨簇”。
     *
     * 窄 = 宽度 < `0.6×行高`。汉字/假名的一个整字约 1 个行高，拉丁字母、数字和汉字偏旁
     * 只有 0.2–0.6 个行高；两侧都窄说明这一刀很可能劈在字/数字内部。
     * 判据与 `ColumnInk.splitsWord` 同源，这里直接用 [InkProfile] 的游程算，避免为一行剖面
     * 再建一个 [app.pictext.core.analyze.ColumnInk]。
     */
    private fun adjacentClusterAllows(profile: InkProfile, x: Int, height: Int): Boolean {
        val minWide = max(1, (WIDE_CLUSTER_RATIO * height).roundToInt())
        if (profile.inkAt(x) > 0) {
            // 切点落在墨簇内部：两侧紧邻的就是同一个簇。
            return runWidthAt(profile, x) >= minWide
        }
        val leftWidth = runWidthLeftOf(profile, x)
        val rightWidth = runWidthRightOf(profile, x)
        return leftWidth >= minWide || rightWidth >= minWide
    }

    /** 含 [x] 的连续非零游程宽度；调用前保证 `inkAt(x) > 0`。 */
    private fun runWidthAt(profile: InkProfile, x: Int): Int {
        var a = x
        while (a - 1 >= profile.left && profile.inkAt(a - 1) > 0) a--
        var b = x
        while (b + 1 < profile.right && profile.inkAt(b + 1) > 0) b++
        return b - a + 1
    }

    /** [x] 左侧最近的连续非零游程宽度；没有则 0。 */
    private fun runWidthLeftOf(profile: InkProfile, x: Int): Int {
        var i = x - 1
        while (i >= profile.left && profile.inkAt(i) == 0) i--
        if (i < profile.left) return 0
        return runWidthAt(profile, i)
    }

    /** [x] 右侧最近的连续非零游程宽度；没有则 0。 */
    private fun runWidthRightOf(profile: InkProfile, x: Int): Int {
        var i = x + 1
        while (i < profile.right && profile.inkAt(i) == 0) i++
        if (i >= profile.right) return 0
        return runWidthAt(profile, i)
    }

    // -----------------------------------------------------------------------
    // v1 行
    // -----------------------------------------------------------------------

    /** v1 行：`line.cuts` 是分析期定下的安全字缝列，只能当硬缝用。 */
    private fun v1Cut(line: TextLine, target: Int, low: Int, high: Int): Cut? {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (x in line.cuts) {
            if (x < low || x > high) continue
            val distance = abs(x - target)
            if (distance < bestDistance) {
                best = x
                bestDistance = distance
            }
        }
        return if (best < 0) null else Cut(best, CutKind.HARD)
    }

    // -----------------------------------------------------------------------
    // 拼段
    // -----------------------------------------------------------------------

    /**
     * 把切点拼成首尾相接的段：左段 `[x0, c)`、右段 `[c, x1)`，`c` 归右段。
     * 每段的 `cut` 记它**左边界**那一刀的级别，第一段是 [CutKind.NONE]。
     */
    private fun segmentsFrom(line: TextLine, cuts: List<Cut>): List<Segment> {
        val out = ArrayList<Segment>(cuts.size + 1)
        var x0 = line.band.left
        var kind = CutKind.NONE
        for (cut in cuts) {
            out.add(Segment(x0, cut.x, kind))
            x0 = cut.x
            kind = cut.kind
        }
        out.add(Segment(x0, line.band.right, kind))
        return out
    }

    private companion object {
        /** 切点窗口 = ±8% 行宽（见类注释）。 */
        const val WINDOW_RATIO = 0.08f

        /** 硬缝宽度下限 = 0.10×行高。 */
        const val HARD_GAP_RATIO = 0.10f

        /** 拉丁行的硬缝下限 = 0.28×行高：词间空格才够宽，词内字缝会被排除（V2-47）。 */
        const val LATIN_GAP_RATIO = 0.18f

        /** “宽墨簇” = 0.60×行高；两侧都窄就不许强制。 */
        const val WIDE_CLUSTER_RATIO = 0.60f

        /** 强制切允许的最大列墨量。 */
        const val MAX_FORCED_INK = 1.5f

        /** 墨密度达到这个值说明更可能是图/表，不强制。 */
        const val MAX_FORCED_DENSITY = 0.62f

        /** 最长竖直墨迹达到 2.6×行高时先怀疑“这是图不是字”。 */
        const val MAX_FORCED_RUN_RATIO = 2.6f
    }
}


