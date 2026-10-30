package app.pictext.core.layout

import app.pictext.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tile 网格验收（docs/07 §2.5 / §4.1 / V2-11）。
 *
 * 注意 600×800：把 §2.5 表格里的“1 个 tile”按公式算出来是 **2 个**——
 * `tileH = tileW = 600`、`stride = 536`，`ceil((800-600)/536)+1 = 2`。
 * 表格那一格是笔误，公式（§4.1）才是准的；docs/07 已同步改正。
 */
class TileGridTest {

    private val tiler = LayoutTiler(LayoutOptions())

    @Test
    fun `tile counts match the plan table`() {
        assertGrid(1080, 2400, tiles = 3, scale = 480f / 1080f)
        assertGrid(1080, 20000, tiles = 20, scale = 480f / 1080f)
        assertGrid(1920, 1080, tiles = 2, scale = 0.35f)
        assertGrid(3000, 2000, tiles = 6, scale = 0.35f)
        assertGrid(600, 800, tiles = 2, scale = 480f / 600f)
    }

    private fun assertGrid(w: Int, h: Int, tiles: Int, scale: Float) {
        val grid = tiler.grid(w, h)
        assertEquals("tiles for ${w}x$h", tiles, grid.count)
        assertEquals("scale for ${w}x$h", scale, grid.scale, 0.002f)
    }

    @Test
    fun `single column pages tile vertically and the last tile is pinned to the bottom`() {
        val grid = tiler.grid(1080, 2400)
        assertEquals(1, grid.cols)
        assertEquals(3, grid.rows)
        for (t in grid.tiles) assertEquals(1080, t.src.width)
        assertEquals(0, grid.tiles[0].src.top)
        assertEquals(1016, grid.tiles[1].src.top)
        assertEquals(1320, grid.tiles[2].src.top)
        assertEquals(2400, grid.tiles[2].src.bottom)
    }

    @Test
    fun `tiles cover the page without a 1px gap`() {
        val grid = tiler.grid(1080, 2400)
        assertTrue("grid must cover 1080x2400", grid.covers(1080, 2400))

        val wide = tiler.grid(3000, 2000)
        assertTrue("grid must cover 3000x2000", wide.covers(3000, 2000))
    }

    @Test
    fun `stride keeps the configured overlap between neighbours`() {
        val grid = tiler.grid(1080, 2400)
        val a = grid.tiles[0].src
        val b = grid.tiles[1].src
        assertEquals(64, a.bottom - b.top)
        assertEquals(1080 - 64, b.top - a.top)
    }

    @Test
    fun `wide page produces two columns`() {
        val grid = tiler.grid(1920, 1080)
        assertEquals(2, grid.cols)
        assertEquals(1, grid.rows)
        assertEquals(1920, grid.tiles[1].src.right)
        // 页面比 tile 矮时会有上下 padding，提示“有效区不是满 480”。
        assertTrue(grid.tiles[0].padY > 0)
    }

    @Test
    fun `narrow page scales up to at most 1x and keeps padding at zero`() {
        val grid = LayoutTiler(LayoutOptions()).grid(600, 800)
        assertEquals(0.8f, grid.scale, 0.001f)
        assertEquals(2, grid.count)
        val t = grid.tiles[0]
        assertEquals(600, t.src.width)
        assertEquals(480, t.innerWidth)
        assertEquals(0, t.padX)
        assertEquals(0, t.padY)
    }

    @Test
    fun `page smaller than the model input is centred with padding`() {
        val grid = LayoutTiler(LayoutOptions.singleTile()).grid(400, 400)
        assertEquals(1, grid.count)
        val t = grid.tiles[0]
        // innerWidth 是“缩放到模型空间后的有效边长”，pad 才是填充。
        assertEquals(400, t.innerWidth)
        assertEquals(40, t.padX)
        assertEquals(40, t.padY)
        assertTrue(t.isPadded)
        assertEquals(RectI(40, 40, 440, 440), t.validModelRect())
        assertEquals(480, t.padX + t.innerWidth + t.padX)
    }

    @Test
    fun `too many tiles is a fallback signal`() {
        val strict = LayoutTiler(LayoutOptions(maxTiles = 18))
        assertTrue(strict.exceedsLimit(strict.grid(1080, 20000)))
        val ok = tiler.grid(1080, 2400)
        assertFalse(tiler.exceedsLimit(ok))
    }

    @Test
    fun `model to source mapping round trips within one pixel`() {
        val grid = tiler.grid(1080, 2400)
        val tile = grid.tiles[0]
        for (mx in 10..470 step 37) {
            val sx = tile.sourceX(mx.toFloat())
            val back = tile.modelX(sx)
            assertTrue("mx=$mx sx=$sx", abs(back - mx) <= 1f)
        }
        val rect = tile.sourceRect(0f, 0f, 479f, 479f, RectI.ofSize(1080, 2400))
        assertTrue(rect.width >= 1070)
    }

    @Test
    fun `every tile has a non empty inner size`() {
        for ((w, h) in listOf(1080 to 2400, 1920 to 1080, 600 to 800, 3000 to 2000)) {
            for (t in tiler.grid(w, h).tiles) {
                assertTrue("inner ${t.innerWidth}x${t.innerHeight}", t.innerWidth > 0 && t.innerHeight > 0)
                assertTrue("pad", t.padX >= 0 && t.padY >= 0)
                assertEquals(t.modelInput, LayoutOptions().modelInput)
            }
        }
    }
}
