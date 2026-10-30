package app.pictext.core.layout

import app.pictext.core.model.CutKind
import app.pictext.core.model.InkProfile
import app.pictext.core.model.LineCutFeatures
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import app.pictext.core.plan.ReflowOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 §4.8.2 三级切点（硬缝 → 软缝 → 强制）的验收。
 *
 * 所有用例都用 [InkProfile.of] 造**确定性剖面**，不依赖像素图；每一行都核对
 * “首尾相接、不重叠、不丢列”，这是渲染器唯一的硬契约。
 */
class CutPolicyTest {

    private val policy = CutPolicy()

    /** 用逐列墨量造剖面；[ink] 收绝对 S 列坐标。 */
    private fun profile(left: Int, width: Int, ink: (x: Int) -> Int): InkProfile =
        InkProfile.of(left, IntArray(width) { ink(left + it) })

    private fun line(
        band: RectI,
        features: LineCutFeatures? = null,
        cuts: List<Int> = emptyList(),
    ): TextLine = TextLine(band = band, cuts = cuts, cutFeatures = features)

    /**
     * 紧排 CJK：整行每列 1–2 点墨，只有 x=250 一列无墨。
     *
     * 单列空白宽度 1 < 硬缝门槛（`max(2, 0.10×24)=2`），所以只可能是强制切；
     * 其余条件（cjkDominant / density / maxRun / 两侧墨簇）全部达标。
     */
    private fun tightCjkLine(sensitive: Boolean = false): TextLine {
        val band = RectI(50, 0, 450, 24)
        val ink = profile(50, 400) { x ->
            when {
                x == 250 -> 0
                (x - 50) % 2 == 0 -> 1
                else -> 2
            }
        }
        return line(
            band,
            LineCutFeatures(
                cjkDominant = true,
                sensitive = sensitive,
                density = 0.5f,
                maxRun = 10,
                minInkColumn = 250,
                profile = ink,
            ),
        )
    }

    /** 段数守恒：`[left, right)` 全部覆盖，相邻段首尾相接，总宽等于行宽。 */
    private fun assertConservation(line: TextLine, decision: CutDecision) {
        assertTrue("至少 1 段", decision.count >= 1)
        assertEquals("首段从左边界开始", line.band.left, decision.segments.first().x0)
        assertEquals("末段到右边界结束", line.band.right, decision.segments.last().x1)
        for (i in 0 until decision.count - 1) {
            assertEquals(
                "第 $i 段右边界应等于第 ${i + 1} 段左边界",
                decision.segments[i].x1,
                decision.segments[i + 1].x0,
            )
        }
        assertEquals("总宽应等于行宽", line.band.width, decision.totalWidth)
        assertEquals("首段没有左切点", CutKind.NONE, decision.segments.first().cut)
        assertTrue("不允许 0 宽段", decision.segments.all { it.width > 0 })
    }

    // -----------------------------------------------------------------------
    // 1. 两段硬缝
    // -----------------------------------------------------------------------

    @Test
    fun `两段硬缝 - 目标附近的零墨列切成两段`() {
        val band = RectI(100, 0, 500, 24)
        val cutLine = line(
            band,
            LineCutFeatures(
                // 零墨列 300、301：宽度 2 == max(2, round(0.10×24))，中心列 301。
                hardGaps = listOf(300..301),
                cjkDominant = true,
                density = 0.5f,
                maxRun = 10,
                minInkColumn = 300,
                profile = profile(100, 400) { x -> if (x in 300..301) 0 else 2 },
            ),
        )
        // needed = ceil(400 × 2 / 540) = 2，理想分界点 = 100 + 400/2 = 300。
        val decision = policy.decide(cutLine, contentWidth = 540, scale = 2f, allowForced = true)

        assertEquals(2, decision.needed)
        assertEquals(2, decision.count)
        assertFalse(decision.compressed)
        assertEquals("第二段的左边界那一刀是硬缝", CutKind.HARD, decision.segments[1].cut)
        assertEquals(301, decision.segments[1].x0)
        assertConservation(cutLine, decision)
    }

    // -----------------------------------------------------------------------
    // 2. 紧排 CJK：无硬缝但允许强制
    // -----------------------------------------------------------------------

    @Test
    fun `紧排 CJK 允许强制 - 窗口内墨量最小列劈一刀`() {
        val cutLine = tightCjkLine()
        val decision = policy.decide(cutLine, contentWidth = 540, scale = 2f, allowForced = true)

        assertEquals(2, decision.needed)
        assertEquals(2, decision.count)
        assertFalse(decision.compressed)
        assertEquals(CutKind.FORCED, decision.segments[1].cut)
        assertEquals("x=250 是窗口内唯一零墨列", 250, decision.segments[1].x0)
        assertEquals(1, decision.forcedCuts)
        assertConservation(cutLine, decision)
    }

    // -----------------------------------------------------------------------
    // 3. 敏感行禁止强制
    // -----------------------------------------------------------------------

    @Test
    fun `敏感行即使允许强制也不出现 FORCED`() {
        val cutLine = tightCjkLine(sensitive = true)
        val decision = policy.decide(cutLine, contentWidth = 540, scale = 2f, allowForced = true)

        assertEquals(2, decision.needed)
        assertEquals(1, decision.count)
        assertTrue(decision.compressed)
        assertEquals(0, decision.forcedCuts)
        assertTrue("敏感行不得出现强制切", decision.segments.none { it.cut == CutKind.FORCED })
        assertConservation(cutLine, decision)
    }

    // -----------------------------------------------------------------------
    // 4. 区域不允许强制（CAPTION/CHROME）
    // -----------------------------------------------------------------------

    @Test
    fun `区域不允许强制时 CAPTION 行压缩`() {
        val cutLine = tightCjkLine()
        val decision = policy.decide(cutLine, contentWidth = 540, scale = 2f, allowForced = false)

        assertEquals(1, decision.count)
        assertTrue(decision.compressed)
        assertEquals(0, decision.forcedCuts)
        assertTrue(decision.segments.none { it.cut == CutKind.FORCED })
        assertConservation(cutLine, decision)
    }

    // -----------------------------------------------------------------------
    // 5. 三个硬缝 → 三段
    // -----------------------------------------------------------------------

    @Test
    fun `needed 为 3 时三个硬缝切三段`() {
        val band = RectI(100, 0, 500, 24)
        val ink = profile(100, 400) { x ->
            if (x in 232..234 || x in 365..367) 0 else 2
        }
        val cutLine = line(
            band,
            LineCutFeatures(
                hardGaps = listOf(232..234, 365..367),
                cjkDominant = true,
                density = 0.5f,
                maxRun = 10,
                minInkColumn = 233,
                profile = ink,
            ),
        )
        // needed = ceil(400 × 2 / 300) = 3；理想分界点 233、366。
        val decision = policy.decide(cutLine, contentWidth = 300, scale = 2f, allowForced = false)

        assertEquals(3, decision.needed)
        assertEquals(3, decision.count)
        assertFalse(decision.compressed)
        assertEquals(
            listOf(CutKind.NONE, CutKind.HARD, CutKind.HARD),
            decision.segments.map { it.cut },
        )
        assertEquals(listOf(233, 366), decision.segments.drop(1).map { it.x0 })
        assertConservation(cutLine, decision)
    }

    // -----------------------------------------------------------------------
    // 6. 超过硬上限
    // -----------------------------------------------------------------------

    @Test
    fun `needed 12 时按硬上限切成 8 段`() {
        // 软上限也放到 8，才能验证“硬上限”而不是被默认软上限 6 先夹住。
        val capped = CutPolicy(ReflowOptions(outputWidth = 1080, maxSegmentsPerLine = 8))
        val band = RectI(100, 0, 700, 24)
        // 8 段需要 7 个理想分界点；再塞 5 个干扰缝，凑满 12 个零墨缝。
        val centers = listOf(175, 250, 325, 400, 475, 550, 625, 211, 286, 361, 436, 511)
        val blank = BooleanArray(600)
        for (c in centers) {
            for (x in c - 1..c + 1) blank[x - 100] = true
        }
        val cutLine = line(
            band,
            LineCutFeatures(
                hardGaps = centers.map { (it - 1)..(it + 1) },
                cjkDominant = true,
                density = 0.5f,
                maxRun = 10,
                minInkColumn = 175,
                profile = InkProfile.of(100, IntArray(600) { if (blank[it]) 0 else 2 }),
            ),
        )
        // needed = ceil(600 × 2 / 100) = 12；cap = min(8, 8, 12) = 8。
        val decision = capped.decide(cutLine, contentWidth = 100, scale = 2f, allowForced = false)

        assertEquals(12, decision.needed)
        assertEquals(8, decision.count)
        assertFalse(decision.compressed)
        assertTrue(
            "8 段应全部落在硬缝上",
            decision.segments.drop(1).all { it.cut == CutKind.HARD },
        )
        assertEquals(listOf(175, 250, 325, 400, 475, 550, 625), decision.segments.drop(1).map { it.x0 })
        assertConservation(cutLine, decision)
    }

    // -----------------------------------------------------------------------
    // 7. 段数守恒（每个用例都断言，这里再集中扫一遍代表性场景）
    // -----------------------------------------------------------------------

    @Test
    fun `段数守恒 - 首尾相接不重叠不丢列`() {
        val band = RectI(100, 0, 500, 24)
        val tight = tightCjkLine()
        val splitTwo = policy.decide(tight, contentWidth = 540, scale = 2f, allowForced = true)
        val compressed = policy.decide(
            tightCjkLine(sensitive = true),
            contentWidth = 540,
            scale = 2f,
            allowForced = true,
        )
        val noSplit = policy.decide(
            line(band),
            contentWidth = 1080,
            scale = 1f,
            allowForced = true,
        )

        assertConservation(tight, splitTwo)
        assertConservation(tightCjkLine(sensitive = true), compressed)
        assertConservation(line(band), noSplit)
        assertEquals(2, splitTwo.count)
        assertEquals(1, compressed.count)
        assertEquals(1, noSplit.count)
    }

    // -----------------------------------------------------------------------
    // 8. 每行 ≤1 次强制
    // -----------------------------------------------------------------------

    @Test
    fun `每行最多一次强制 - 第二刀用硬缝补齐`() {
        val band = RectI(100, 0, 500, 24)
        val ink = profile(100, 400) { x ->
            if (x == 233 || x in 365..367) 0 else 2
        }
        val cutLine = line(
            band,
            LineCutFeatures(
                // 只给第二刀一个硬缝；第一刀（目标 233）附近没有硬/软缝，只能强制。
                hardGaps = listOf(365..367),
                cjkDominant = true,
                density = 0.5f,
                maxRun = 10,
                minInkColumn = 233,
                profile = ink,
            ),
        )
        val decision = policy.decide(cutLine, contentWidth = 300, scale = 2f, allowForced = true)

        assertEquals(3, decision.count)
        assertFalse(decision.compressed)
        assertTrue("每行最多 1 次强制", decision.forcedCuts <= 1)
        assertEquals(1, decision.forcedCuts)
        assertEquals(CutKind.FORCED, decision.segments[1].cut)
        assertEquals(CutKind.HARD, decision.segments[2].cut)
        assertEquals(233, decision.segments[1].x0)
        assertEquals(366, decision.segments[2].x0)
        assertConservation(cutLine, decision)
    }

    // -----------------------------------------------------------------------
    // 9. v1 行（cutFeatures = null）
    // -----------------------------------------------------------------------

    @Test
    fun `v1 行只用 line_cuts 切硬缝绝不强制`() {
        val band = RectI(100, 0, 500, 24)
        val v1 = line(band, features = null, cuts = listOf(300))
        val decision = policy.decide(v1, contentWidth = 540, scale = 2f, allowForced = true)

        assertEquals(2, decision.needed)
        assertEquals(2, decision.count)
        assertFalse(decision.compressed)
        assertEquals(0, decision.forcedCuts)
        assertEquals(CutKind.HARD, decision.segments[1].cut)
        assertEquals(300, decision.segments[1].x0)
        assertConservation(v1, decision)

        // 没有安全字缝 → 压缩，绝不强制。
        val withoutCut = line(band, features = null, cuts = emptyList())
        val compressed = policy.decide(withoutCut, contentWidth = 540, scale = 2f, allowForced = true)
        assertEquals(1, compressed.count)
        assertTrue(compressed.compressed)
        assertEquals(0, compressed.forcedCuts)
        assertConservation(withoutCut, compressed)
    }

    // -----------------------------------------------------------------------
    // 10. 放得下不切
    // -----------------------------------------------------------------------

    @Test
    fun `needed 为 1 时整行一段不压缩`() {
        val cutLine = line(RectI(100, 0, 500, 24))
        // needed = ceil(400 × 1 / 1080) = 1。
        val decision = policy.decide(cutLine, contentWidth = 1080, scale = 1f, allowForced = true)

        assertEquals(1, decision.needed)
        assertEquals(1, decision.count)
        assertFalse(decision.compressed)
        assertEquals(CutKind.NONE, decision.segments[0].cut)
        assertFalse(decision.isSplit)
        assertConservation(cutLine, decision)
    }
}
