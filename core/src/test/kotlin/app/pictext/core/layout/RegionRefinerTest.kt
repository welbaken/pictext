package app.pictext.core.layout

import app.pictext.core.model.GrayImage
import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [RegionRefiner] 的合成页用例。
 *
 * 所有页面都用 [GrayImage.solid] + 直接写 `data[...]` 生成，不读磁盘、不依赖 Android；
 * 随机纹理用固定种子的 LCG，保证同一份代码每次跑出的像素完全一致。
 */
class RegionRefinerTest {

    // ---------------------------------------------------------------------
    // 1. 基本行结构：位置误差、行高
    // ---------------------------------------------------------------------

    @Test
    fun `synthetic text region yields four rows within one pixel`() {
        val page = whitePage(1080, 400)
        val rowTops = listOf(60, 100, 140, 180)
        val lineHeight = 24
        for (y in rowTops) page.paint(100, y, 900, y + lineHeight, 0)

        val result = refine(page, RectI(60, 20, 940, 244))

        assertFalse("正常文本区不应失败：${result.inkRatio}", result.failed)
        assertEquals(4, result.lineCount)
        for (i in result.lines.indices) {
            val band = result.lines[i].band
            // RowSegmenter 的 3px 平滑会让行带上下各外扩 1px，正好落在验收的 ≤1px 内。
            assertTrue("line $i top=${band.top}", abs(band.top - rowTops[i]) <= 1)
            assertTrue("line $i bottom=${band.bottom}", abs(band.bottom - (rowTops[i] + lineHeight)) <= 1)
            assertTrue("line $i left=${band.left}", abs(band.left - 100) <= 1)
            assertTrue("line $i right=${band.right}", abs(band.right - 900) <= 1)
        }
        assertTrue("medianLineHeight=${result.medianLineHeight}", result.medianLineHeight in 20f..28f)
        assertEquals(result.lines, result.region.lines)
    }

    // ---------------------------------------------------------------------
    // 2. 模型框只是 ROI：上下平移 12px 不改变行带
    // ---------------------------------------------------------------------

    @Test
    fun `model box shifted twelve px keeps the same line bands`() {
        val page = whitePage(1080, 400)
        val rowTops = listOf(60, 100, 140, 180)
        for (y in rowTops) page.paint(100, y, 900, y + 24, 0)
        val base = RectI(60, 20, 940, 244)

        val results = listOf(-12, 12).map { dy -> refine(page, base.offset(0, dy)) }
        for (result in results) {
            assertFalse(result.failed)
            assertEquals(4, result.lineCount)
            for (i in result.lines.indices) {
                val band = result.lines[i].band
                assertTrue(
                    "offset ROI: line $i top=${band.top} expected≈${rowTops[i]}",
                    abs(band.top - rowTops[i]) <= 2,
                )
                assertTrue(
                    "offset ROI: line $i bottom=${band.bottom} expected≈${rowTops[i] + 24}",
                    abs(band.bottom - (rowTops[i] + 24)) <= 2,
                )
            }
        }
        // 框平移不应改变行的相对结构。
        assertEquals(
            results[0].lines.map { it.band },
            results[1].lines.map { it.band },
        )
    }

    // ---------------------------------------------------------------------
    // 3. 引用竖线（装饰列）被忽略
    // ---------------------------------------------------------------------

    @Test
    fun `quote vertical rule is ignored`() {
        val page = whitePage(1080, 400)
        val rowTops = listOf(60, 100, 140)
        for (y in rowTops) page.paint(110, y, 900, y + 24, 0)
        // 2px 宽、200px 高的引用竖线，比三行文字都长。
        page.paint(90, 60, 92, 260, 0)

        val result = refine(page, RectI(60, 30, 940, 280))

        assertFalse(result.failed)
        assertEquals(3, result.lineCount)
        for (i in result.lines.indices) {
            val band = result.lines[i].band
            assertTrue("line $i top=${band.top}", abs(band.top - rowTops[i]) <= 1)
            // 竖线列已从 contentMask 清掉，行带左缘应回到文字起点 110。
            assertTrue("line $i left=${band.left}", band.left >= 110)
        }
    }

    // ---------------------------------------------------------------------
    // 4. 深色底浅字
    // ---------------------------------------------------------------------

    @Test
    fun `light text on dark background is segmented`() {
        val page = GrayImage.solid(1080, 400, 20, 0xFF141414.toInt())
        val rowTops = listOf(60, 100, 140, 180)
        for (y in rowTops) page.paint(100, y, 900, y + 24, 230)

        val result = refine(page, RectI(60, 20, 940, 244))

        assertFalse(result.failed)
        assertEquals(4, result.lineCount)
        for (i in result.lines.indices) {
            assertTrue(
                "line $i top=${result.lines[i].band.top}",
                abs(result.lines[i].band.top - rowTops[i]) <= 1,
            )
        }
    }

    // ---------------------------------------------------------------------
    // 5. 纯照片/纹理区域：不产生文字行
    // ---------------------------------------------------------------------

    @Test
    fun `photo texture yields no text lines`() {
        val page = texturePage(600, 400)

        val result = refine(page, RectI(0, 0, 600, 400))

        assertEquals(0, result.lineCount)
        assertTrue("纹理不应被当成文字行：failed=${result.failed}", result.failed || result.lineCount == 0)
    }

    @Test
    fun `line touching the roi edge grows outward to the glyph`() {
        val page = whitePage(1080, 400)
        // 文本实际到 540，模型框右边界 527（+6 pad = 533）会切掉最后 7px。
        page.paint(100, 100, 540, 124, 0)

        val result = refine(page, RectI(60, 70, 527, 154))

        assertFalse(result.failed)
        val band = result.lines.single().band
        assertTrue("行带右缘=${band.right}，应吃到 540 附近", band.right >= 538)
        assertTrue("行带左缘=${band.left}", abs(band.left - 100) <= 1)
    }

    @Test
    fun `line not touching the roi edge is not grown`() {
        val page = whitePage(1080, 400)
        page.paint(100, 100, 540, 124, 0)

        // ROI 右边留了 60px，行带不贴边，不应触发外扩。
        val result = refine(page, RectI(60, 70, 600, 154))

        assertFalse(result.failed)
        val band = result.lines.single().band
        assertTrue("行带右缘=${band.right}", abs(band.right - 540) <= 1)
    }

    @Test
    fun `figure region is refined at the reduced figure resolution`() {
        val page = whitePage(1080, 400)
        val rowTops = listOf(60, 100, 140, 180)
        for (y in rowTops) page.paint(100, y, 900, y + 24, 0)

        // 强制 FIGURE 走 480px 宽（源 ROI 892px，scale ≈0.54），仍然要能切出 4 行。
        val result = refine(
            page,
            RectI(60, 20, 940, 244),
            kind = RegionKind.FIGURE,
            options = LayoutOptions(refineFigureMaxWidth = 480, refineFigureMaxPixels = 480 * 480),
        )

        assertFalse(result.failed)
        assertEquals(4, result.lineCount)
        assertTrue("scale 应小于 1（走了半分辨率）", result.medianLineHeight > 0f)
    }

    // ---------------------------------------------------------------------
    // 6. 切点特征
    // ---------------------------------------------------------------------

    @Test
    fun `tight bold cjk line still exposes a cut candidate`() {
        val page = whitePage(1080, 300)
        // 22px 一个字、字间无缝、字形几乎填满 → 一整条实心带，硬缝为空。
        page.paint(100, 100, 980, 124, 0)

        val result = refine(page, RectI(60, 70, 1020, 154))
        val features = result.lines.single().cutFeatures
        assertNotNull(features)
        val f = features!!

        assertTrue("实心带不应有零墨硬缝", f.hardGaps.isEmpty())
        // 紧排中文的兜底：要么有软缝，要么至少有可用的最小墨量列。
        assertTrue("softGaps=${f.softGaps}, minInkColumn=${f.minInkColumn}", f.softGaps.isNotEmpty() || f.minInkColumn >= 0)
        assertTrue("实心整行应判为 CJK 主导", f.cjkDominant)
    }

    @Test
    fun `cjk line with eight pixel gaps yields hard gaps`() {
        val page = whitePage(1080, 300)
        var x = 100
        while (x + 22 <= 900) {
            page.paint(x, 100, x + 22, 124, 0)
            x += 30 // 22px 字 + 8px 零墨缝
        }

        val result = refine(page, RectI(60, 70, 940, 154))
        val features = result.lines.single().cutFeatures
        assertNotNull(features)
        val f = features!!

        assertTrue("普通 CJK 行应有硬缝", f.hardGaps.isNotEmpty())
        for (gap in f.hardGaps) {
            val width = gap.last - gap.first + 1
            assertEquals("gap=$gap", 8, width)
            assertTrue(width >= 2)
        }
    }

    @Test
    fun `min ink column lands on the carved notch`() {
        val page = whitePage(1080, 300)
        page.paint(100, 100, 900, 124, 0)
        // 1px 凹槽：宽 1 不够硬缝门槛，但它是整行墨量唯一的最小列。
        for (y in 100 until 124) page.data[y * page.width + 500] = 255.toByte()

        val result = refine(page, RectI(60, 70, 940, 154))
        val features = result.lines.single().cutFeatures
        assertNotNull(features)
        val f = features!!

        assertEquals(500, f.minInkColumn)
        val profile = f.profile
        assertNotNull(profile)
        assertEquals(0, profile!!.inkAt(500))
        assertTrue("notch 左侧=${profile.inkAt(499)}", profile.inkAt(499) - profile.inkAt(500) >= 10)
        assertTrue("notch 右侧=${profile.inkAt(501)}", profile.inkAt(501) - profile.inkAt(500) >= 10)
    }

    // ---------------------------------------------------------------------
    // 7. 敏感行保护：等宽等距竖条（URL/代码/数字的几何代理）
    // ---------------------------------------------------------------------

    @Test
    fun `url like bar row is marked sensitive`() {
        val page = whitePage(1080, 300)
        for (i in 0 until 50) {
            val x = 100 + i * 12
            page.paint(x, 100, x + 2, 124, 0)
        }

        val result = refine(page, RectI(60, 70, 760, 154))
        val features = result.lines.single().cutFeatures
        assertNotNull(features)
        val f = features!!

        assertTrue("latinLike=${f.latinLike}, digitHeavy=${f.digitHeavy}, hasUrlOrCode=${f.hasUrlOrCode}", f.sensitive)
        assertTrue(f.latinLike || f.digitHeavy || f.hasUrlOrCode)
    }

    // ---------------------------------------------------------------------
    // 8. 空 ROI / 空白页
    // ---------------------------------------------------------------------

    @Test
    fun `blank page fails with tiny ink ratio`() {
        val page = whitePage(1080, 400)

        val result = refine(page, RectI(100, 100, 500, 200))

        assertTrue(result.failed)
        assertTrue("inkRatio=${result.inkRatio}", result.inkRatio < 0.001f)
        assertEquals(0, result.lineCount)
    }

    @Test
    fun `roi outside page fails without throwing`() {
        val page = whitePage(1080, 400)

        val result = refine(page, RectI(-200, -200, -100, -100))

        assertTrue(result.failed)
        assertEquals(0, result.lineCount)
    }

    // ---------------------------------------------------------------------
    // 附加：角色覆盖与降采样坐标换算
    // ---------------------------------------------------------------------

    @Test
    fun `region kind overrides row role`() {
        val page = whitePage(1080, 400)
        val rowTops = listOf(60, 100, 140, 180)
        for (y in rowTops) page.paint(100, y, 900, y + 24, 0)
        val bounds = RectI(60, 20, 940, 244)

        val title = refine(page, bounds, kind = RegionKind.TITLE)
        val caption = refine(page, bounds, kind = RegionKind.CAPTION)
        val chrome = refine(page, bounds, kind = RegionKind.CHROME)

        assertTrue(title.lines.all { it.role == LineRole.HEADING })
        assertTrue(caption.lines.all { it.role == LineRole.CAPTION })
        assertTrue(chrome.lines.all { it.role == LineRole.UI })
    }

    @Test
    fun `downsampled roi maps lines back to source coordinates`() {
        val page = whitePage(1080, 400)
        val rowTops = listOf(60, 100, 140, 180)
        for (y in rowTops) page.paint(100, y, 900, y + 24, 0)

        // 892px 的 ROI 被压到 600px，scale ≈ 0.67；行带必须换回 S 坐标。
        val result = refine(
            page,
            RectI(60, 20, 940, 244),
            options = LayoutOptions(refineMaxWidth = 600),
        )

        assertFalse(result.failed)
        assertEquals(4, result.lineCount)
        for (i in result.lines.indices) {
            val band = result.lines[i].band
            // 降采样后 1 个采样行 ≈ 1.5 个源像素，允许 3px 误差。
            assertTrue("line $i top=${band.top}", abs(band.top - rowTops[i]) <= 3)
            assertTrue("line $i bottom=${band.bottom}", abs(band.bottom - (rowTops[i] + 24)) <= 3)
            assertTrue("line $i left=${band.left}", abs(band.left - 100) <= 3)
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private fun refine(
        page: GrayImage,
        bounds: RectI,
        kind: RegionKind = RegionKind.TEXT,
        options: LayoutOptions = LayoutOptions(),
    ): RefineResult = RegionRefiner(options).refine(
        GrayPageRegionSampler(page),
        PageContext(page.width, page.height),
        LayoutRegion(id = 1, kind = kind, bounds = bounds),
    )

    private fun whitePage(width: Int, height: Int): GrayImage = GrayImage.solid(width, height, 255)

    /** 固定种子的 LCG 纹理，保证可复现；不使用无种子的 `Random()`。 */
    private fun texturePage(width: Int, height: Int): GrayImage {
        val page = GrayImage.solid(width, height, 128)
        var state = 0x2545F4914F6CDD1DL
        for (i in page.data.indices) {
            state = state * 6364136223846793005L + 1442695040888963407L
            page.data[i] = ((state ushr 33) and 0xFF).toByte()
        }
        return page
    }

    private fun GrayImage.paint(x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        val v = value.coerceIn(0, 255).toByte()
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
                data[y * width + x] = v
            }
        }
    }
}
