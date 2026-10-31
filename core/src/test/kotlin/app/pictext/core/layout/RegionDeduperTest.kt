package app.pictext.core.layout

import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.RegionSource
import app.pictext.core.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2-48：细化后的行带级去重（残差 ROI 重新切出已覆盖的行 / 跨 tile 重复块）。 */
class RegionDeduperTest {

    private val deduper = RegionDeduper()

    private fun region(
        bounds: RectI,
        lines: List<RectI>,
        source: RegionSource = RegionSource.MODEL,
        kind: RegionKind = RegionKind.TEXT,
        id: Int = 0,
    ) = LayoutRegion(
        id = id,
        kind = if (lines.isEmpty() && kind == RegionKind.TEXT) RegionKind.FIGURE else kind,
        bounds = bounds,
        lines = lines.map { TextLine(it) },
        source = source,
    )

    @Test
    fun `residual line already covered by a model region is dropped`() {
        val model = region(RectI(20, 600, 670, 700), listOf(RectI(20, 620, 670, 636)))
        // 残差 ROI 从 499 开始、外扩后把同一条行又切了一遍。
        val residual = region(
            RectI(0, 499, 690, 690),
            listOf(RectI(20, 620, 671, 636), RectI(20, 650, 670, 666)),
            source = RegionSource.RESIDUAL,
            id = 1000,
        )

        val out = deduper.dedupe(listOf(model), listOf(residual))

        assertEquals(2, out.size)
        // 模型行保留；残差只剩真正漏掉的那一条。
        assertEquals(listOf(RectI(20, 620, 670, 636)), out[0].lines.map { it.band })
        assertEquals(listOf(RectI(20, 650, 670, 666)), out[1].lines.map { it.band })
    }

    @Test
    fun `residual whose every line is a duplicate is dropped entirely`() {
        val model = region(
            RectI(20, 848, 659, 900),
            listOf(
                RectI(20, 850, 656, 863),
                RectI(20, 869, 642, 881),
                RectI(20, 888, 583, 900),
            ),
        )
        // 第二个 tile 补出的残差：整块都落在模型区域内，行也一样。
        val residual = region(
            RectI(0, 843, 690, 972),
            listOf(RectI(19, 850, 656, 863), RectI(20, 869, 640, 881), RectI(20, 888, 583, 900)),
            source = RegionSource.RESIDUAL,
            id = 1005,
        )

        val out = deduper.dedupe(listOf(model), listOf(residual))

        assertEquals(1, out.size)
        assertEquals(RegionSource.MODEL, out[0].source)
    }

    @Test
    fun `the same line in two overlapping tiles keeps the first one`() {
        val a = region(RectI(20, 620, 670, 636), listOf(RectI(20, 620, 670, 636)), id = 1)
        val b = region(
            RectI(18, 620, 672, 638),
            listOf(RectI(18, 620, 672, 638)),
            source = RegionSource.RESIDUAL,
            id = 1000,
        )

        val out = deduper.dedupe(listOf(a), listOf(b))

        assertEquals(1, out.size)
        assertEquals(listOf(RectI(20, 620, 670, 636)), out[0].lines.map { it.band })
    }

    @Test
    fun `two columns sharing the same y are not merged`() {
        val left = region(RectI(0, 600, 300, 620), listOf(RectI(0, 600, 300, 620)), id = 1)
        val right = region(RectI(400, 600, 700, 620), listOf(RectI(400, 600, 700, 620)), id = 2)

        val out = deduper.dedupe(listOf(left, right), emptyList())

        assertEquals(2, out.size)
    }

    @Test
    fun `adjacent lines on the same column are not merged`() {
        val region = region(
            RectI(0, 600, 700, 680),
            listOf(RectI(0, 600, 700, 616), RectI(0, 620, 700, 636)),
        )

        val out = deduper.dedupe(listOf(region), emptyList())

        assertEquals(2, out[0].lines.size)
    }

    @Test
    fun `a residual block inside another block is kept once`() {
        val model = region(
            RectI(600, 1000, 680, 1080),
            emptyList(),
            kind = RegionKind.FIGURE,
            id = 1,
        )
        val residual = region(
            RectI(640, 1060, 666, 1083),
            emptyList(),
            source = RegionSource.RESIDUAL,
            id = 1004,
        )

        val out = deduper.dedupe(listOf(model), listOf(residual))

        assertEquals(1, out.size)
        assertEquals(1, out[0].id)
    }

    @Test
    fun `identical residual blocks from two tiles are kept once`() {
        val first = region(
            RectI(33, 1031, 59, 1054),
            emptyList(),
            source = RegionSource.RESIDUAL,
            id = 1003,
        )
        val second = region(
            RectI(33, 1031, 59, 1054),
            emptyList(),
            source = RegionSource.RESIDUAL,
            id = 1006,
        )

        val out = deduper.dedupe(emptyList(), listOf(first, second))

        assertEquals(1, out.size)
        assertTrue(out[0].id == 1003)
    }
}
