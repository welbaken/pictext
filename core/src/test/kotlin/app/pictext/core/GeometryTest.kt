package app.pictext.core

import app.pictext.core.model.Block
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.PageLayout
import app.pictext.core.model.RectI
import app.pictext.core.model.TextLine
import app.pictext.core.util.Runs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun `union skips empty rects`() {
        val a = RectI(10, 10, 20, 20)
        assertEquals(RectI(10, 10, 20, 20), a.union(RectI.EMPTY))
        assertEquals(a, RectI.EMPTY.union(a))
        assertEquals(RectI(5, 10, 20, 40), a.union(RectI(5, 30, 8, 40)))
    }

    @Test
    fun `intersect returns empty when disjoint`() {
        val a = RectI(0, 0, 10, 10)
        assertTrue(a.intersect(RectI(10, 10, 20, 20)).isEmpty)
        assertEquals(RectI(5, 5, 10, 10), a.intersect(RectI(5, 5, 20, 20)))
    }

    @Test
    fun `offset and negative coordinates`() {
        val r = RectI(10, 20, 30, 40).offset(-15, 5)
        assertEquals(RectI(-5, 25, 15, 45), r)
        assertFalse(r.isEmpty)
    }

    @Test
    fun `scale rounds each edge`() {
        assertEquals(RectI(2, 4, 6, 8), RectI(1, 2, 3, 4).scale(2f))
        assertEquals(RectI(1, 1, 2, 2), RectI(1, 2, 3, 4).scale(0.5f))
    }

    @Test
    fun `runsOf handles empty single and gaps`() {
        assertEquals(emptyList<IntRange>(), Runs.runsOf(BooleanArray(0)))
        assertEquals(listOf(0..0), Runs.runsOf(booleanArrayOf(true)))
        assertEquals(
            listOf(0..1, 3..3),
            Runs.runsOf(booleanArrayOf(true, true, false, true)),
        )
        assertEquals(emptyList<IntRange>(), Runs.runsOf(BooleanArray(4)))
    }

    @Test
    fun `merge joins close runs`() {
        val merged = Runs.merge(listOf(0..1, 3..4, 10..12), 1)
        assertEquals(listOf(0..4, 10..12), merged)
    }

    @Test
    fun `toSource expands outward and clamps`() {
        val block = Block.Paragraph(
            listOf(TextLine(RectI(10, 10, 20, 20))),
        )
        val layout = PageLayout(
            srcWidth = 200,
            srcHeight = 100,
            analysisScale = 0.5f,
            blocks = listOf(block),
            textLeft = 10,
            textRight = 20,
            splitX = 15,
            cleanRatio = 1f,
            medianLineHeight = 10f,
            medianGap = 4f,
            medianPitch = 14f,
            isDark = false,
            backgroundColor = -1,
            diagnostics = Diagnostics(),
        )
        assertEquals(RectI(20, 20, 40, 40), layout.toSource(RectI(10, 10, 20, 20)))
        assertEquals(RectI(0, 0, 200, 100), layout.toSource(RectI(-5, -5, 300, 300)))
        assertTrue(layout.toSource(RectI(300, 300, 320, 320)).isEmpty)
    }
}
