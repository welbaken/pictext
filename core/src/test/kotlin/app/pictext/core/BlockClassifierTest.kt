package app.pictext.core

import app.pictext.core.analyze.BlockClassifier
import app.pictext.core.analyze.RowBand
import app.pictext.core.model.Block
import app.pictext.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockClassifierTest {

    private val classifier = BlockClassifier()

    private fun inkFrom(mask: BooleanArray): ByteArray =
        ByteArray(mask.size) { if (mask[it]) 255.toByte() else 0 }

    @Test
    fun `solid bar is figure and text row is text`() {
        val w = 200
        val h = 220
        val mask = BooleanArray(w * h)
        // 正文行
        for (y in 10 until 30) for (x in 10 until 190) mask[y * w + x] = x % 2 == 0
        // 150px 高的实心柱
        for (y in 40 until 190) for (x in 80 until 100) mask[y * w + x] = true
        val bands = listOf(
            RowBand(10, 30, 10, 190, 20 * 90), // 隔列填充，密度 0.5
            RowBand(40, 190, 80, 100, 150 * 20),
        )
        val result = classifier.classify(mask, w, h, bands, 20f, inkFrom(mask))
        val figures = result.blocks.filterIsInstance<Block.Figure>()
        assertEquals(1, figures.size)
        assertEquals(1, result.blocks.filterIsInstance<Block.Paragraph>().size)
    }

    @Test
    fun `figure split by sky is merged`() {
        val w = 220
        val h = 220
        val mask = BooleanArray(w * h)
        for (y in 10 until 90) for (x in 10 until 210) mask[y * w + x] = true
        for (y in 110 until 210) for (x in 10 until 210) mask[y * w + x] = true
        val bands = listOf(
            RowBand(10, 90, 10, 210, 80 * 200),
            RowBand(110, 210, 10, 210, 100 * 200),
        )
        val result = classifier.classify(mask, w, h, bands, 20f, inkFrom(mask))
        val figures = result.blocks.filterIsInstance<Block.Figure>()
        assertEquals(1, figures.size)
        assertEquals(10, figures[0].bounds.top)
        assertEquals(210, figures[0].bounds.bottom)
    }

    @Test
    fun `figure grows to low contrast card`() {
        val w = 120
        val h = 120
        val mask = BooleanArray(w * h)
        for (y in 30 until 90) for (x in 30 until 90) mask[y * w + x] = true
        val ink = ByteArray(w * h)
        // 卡片区域低反差墨迹 20
        for (y in 20 until 100) for (x in 20 until 100) ink[y * w + x] = 20
        val bands = listOf(RowBand(30, 90, 30, 90, 60 * 60))
        val result = classifier.classify(mask, w, h, bands, 10f, ink)
        val fig = result.blocks.filterIsInstance<Block.Figure>().single()
        assertTrue("top=${fig.bounds.top}", fig.bounds.top <= 22)
        assertTrue("left=${fig.bounds.left}", fig.bounds.left <= 22)
        assertTrue("right=${fig.bounds.right}", fig.bounds.right >= 98)
    }

    @Test
    fun `large title stays text`() {
        val w = 300
        val h = 60
        val mask = BooleanArray(w * h)
        for (y in 5 until 53) for (x in 50 until 250) mask[y * w + x] = x % 2 == 0
        val bands = listOf(RowBand(5, 53, 50, 250, 48 * 100)) // 隔列填充，密度 0.5
        val result = classifier.classify(mask, w, h, bands, 22f, inkFrom(mask))
        assertEquals(0, result.blocks.filterIsInstance<Block.Figure>().size)
        assertEquals(1, result.blocks.filterIsInstance<Block.Paragraph>().size)
    }

    @Test
    fun `photo only yields figure and no text`() {
        val w = 100
        val h = 100
        val mask = BooleanArray(w * h) { true }
        val bands = listOf(RowBand(0, 100, 0, 100, w * h))
        val result = classifier.classify(mask, w, h, bands, 20f, inkFrom(mask))
        assertEquals(1, result.blocks.filterIsInstance<Block.Figure>().size)
        assertEquals(0, result.blocks.filterIsInstance<Block.Paragraph>().size)
    }
}
