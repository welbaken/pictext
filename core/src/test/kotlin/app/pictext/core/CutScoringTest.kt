package app.pictext.core

import app.pictext.core.analyze.ColumnInk
import app.pictext.core.analyze.ColumnSplitter
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切点候选的**加权评分**（v1.4）。
 *
 * 旧实现是字典序「离中心最近 → 缝更宽」：1px 的噪点缝只要离得近，就永远赢过 12px 的真字缝。
 * 现在按 `center / gap / balance / ink` 四项加权求和，宽缝可以补回一点距离差；
 * 但同宽时仍然取离目标列最近的那条，保证全局切分列稳定、不随参差右边缘漂移。
 */
class CutScoringTest {

    private val splitter = ColumnSplitter()

    /** 整行有墨（每列 5 点），把给定区间挖成零墨缝。 */
    private fun profile(w: Int, vararg gaps: IntRange): ColumnInk {
        val counts = IntArray(w) { 5 }
        for (g in gaps) for (x in g) counts[x] = 0
        return ColumnInk(0, counts)
    }

    @Test
    fun `wide gap outranks a narrow gap nearby`() {
        // 窄缝（2px）离中心 4px，宽缝（12px）离中心 11px：加权后宽缝应当胜出。
        val p = profile(400, 196..197, 206..217)
        val cut = splitter.gapNear(p, center = 200, win = 54, medianHeight = 22f)!!.first
        assertTrue("cut=$cut 应落在 12px 宽缝里", cut in 206..217)
    }

    @Test
    fun `same width keeps the gap closest to the column`() {
        // 两条同宽（12px）：取离目标列更近的那条，列才不会漂。
        val p = profile(400, 186..197, 206..217)
        val cut = splitter.gapNear(p, center = 200, win = 54, medianHeight = 22f)!!.first
        assertTrue("cut=$cut 应取更靠近 center 的 186..197", cut in 186..197)
    }
}
