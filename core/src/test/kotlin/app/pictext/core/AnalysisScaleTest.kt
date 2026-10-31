package app.pictext.core

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.AnalysisScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1：分析图**宽度优先**定标。
 *
 * 旧规则“长边 ≤ 1600”会把长图压成一条（690×10305 → 107×1598），
 * 行带/字缝/中线全部失真，重排结果就是左边一条窄列。这里锁住新规则。
 */
class AnalysisScaleTest {

    private val options = AnalysisOptions()

    @Test
    fun `long image keeps its full width`() {
        val size = AnalysisScale.size(690, 10305, options)
        assertEquals(690, size.width)
        assertEquals(10305, size.height)
        assertEquals(1f, size.factor, 1e-6f)
    }

    @Test
    fun `wide screenshot is limited by width not long side`() {
        val size = AnalysisScale.size(4000, 3000, options)
        assertEquals(1600, size.width)
        assertEquals(1200, size.height)
        assertEquals(0.4f, size.factor, 1e-6f)
    }

    @Test
    fun `narrow image is never upscaled`() {
        val size = AnalysisScale.size(320, 480, options)
        assertEquals(320, size.width)
        assertEquals(480, size.height)
        assertEquals(1f, size.factor, 1e-6f)
    }

    @Test
    fun `pixel budget decides strips without shrinking the width`() {
        // 690×10305 = 7.1MP，在 8MP 预算内：一次分析，不切条带。
        assertFalse(AnalysisScale.needsStrips(690, 10305, options))
        // 1080×20000 = 21.6MP：切条带，但宽度仍然是 1080。
        assertTrue(AnalysisScale.needsStrips(1080, 20000, options))
        val size = AnalysisScale.size(1080, 20000, options)
        assertEquals(1080, size.width)
        assertEquals(20000, size.height)
    }

    @Test
    fun `degenerate sizes are safe`() {
        assertEquals(1, AnalysisScale.size(0, 0, options).width)
        assertEquals(1f, AnalysisScale.factor(0, 0, options), 1e-6f)
    }
}
