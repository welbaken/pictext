package app.pictext.core.plan

import app.pictext.core.layout.CoverageReport
import app.pictext.core.layout.LayoutOptions
import app.pictext.core.layout.LayoutSummaryStats
import app.pictext.core.layout.PageComposer
import app.pictext.core.layout.PageContext
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.PageLayout
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.TextLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-43 可信度闸门的判定逻辑。
 *
 * 场景取自聊天夹具的真实故障：模型把每个气泡（1–2 行字）框成 FIGURE，
 * 于是 v2 排出来的“文字行”远少于 v1，页面变成一串图片。
 * 这里用真实 `PageLayout`（走 `PageComposer`）构造各种情形，把两侧阈值都锁住。
 */
class TrustGateDecisionTest {

    private val options = LayoutOptions()

    /** 造一个 `lines` 行的文字布局：每行 30px 高、800px 宽，竖着排下来。 */
    private fun layoutWithLines(lines: Int): PageLayout {
        val regions = (0 until lines).map { i ->
            LayoutRegion(
                id = i,
                kind = RegionKind.TEXT,
                bounds = RectI(40, 40 + i * 36, 840, 40 + i * 36 + 30),
                lines = listOf(TextLine(RectI(40, 40 + i * 36, 840, 40 + i * 36 + 30))),
            )
        }
        return PageComposer().compose(
            PageContext(srcWidth = 1080, srcHeight = 2400),
            regions,
            CoverageReport.EMPTY,
        )
    }

    private fun summary(textAreaRatio: Float) = LayoutSummaryStats(
        tiles = 1,
        detections = 1,
        regions = 1,
        residuals = 0,
        coverage = 1f,
        orderAmbiguous = false,
        verticalRatio = 0f,
        maxScore = 0.9f,
        textAreaRatio = textAreaRatio,
        inkInTextRatio = 0.5f,
    )

    @Test
    fun `check only runs on pages whose text area is small`() {
        assertTrue("文字面积 0.10 应触发复核", TrustGate.needsCheck(summary(0.10f), options))
        assertFalse("文字面积 0.45 不必复核", TrustGate.needsCheck(summary(0.45f), options))
        assertFalse("没有统计时不复核", TrustGate.needsCheck(null, options))
    }

    @Test
    fun `v2 finding far fewer lines than v1 means text was boxed as pictures`() {
        // 聊天夹具的实测比例：v2 9 行 / v1 18 行 = 0.50。
        assertTrue(TrustGate.looksLikePicture(layoutWithLines(9), layoutWithLines(18), options))
        // 长图文流：31 / 43 = 0.72，属于正常波动，不能回退。
        assertFalse(TrustGate.looksLikePicture(layoutWithLines(31), layoutWithLines(43), options))
        // 文章页 v2 通常比 v1 找到更多行。
        assertFalse(TrustGate.looksLikePicture(layoutWithLines(28), layoutWithLines(15), options))
    }

    @Test
    fun `pages v1 barely sees any text are left to the other gates`() {
        // 海报/纯图页：v1 只找到 5 行，行数比较没有意义（阈值 trustMinV1Lines=6）。
        assertFalse(TrustGate.looksLikePicture(layoutWithLines(0), layoutWithLines(5), options))
    }

    @Test
    fun `threshold is a ratio not an absolute count`() {
        // 100 行 vs 40 行：比例 0.40 < 0.6，判为塌方。
        assertTrue(TrustGate.looksLikePicture(layoutWithLines(40), layoutWithLines(100), options))
        // 60 行 vs 100 行：比例刚好 0.60，不判（避免边界抖动）。
        assertFalse(TrustGate.looksLikePicture(layoutWithLines(60), layoutWithLines(100), options))
    }
}


