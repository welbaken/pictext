package app.pictext.core

import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.Block
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.PlanMode
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import app.pictext.core.util.Grayscale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 端到端黄金测试：与 `prototype/reflow_prototype.py` 在同一张合成图上对拍。
 *
 * 只比对结构数值（行数、图块数、切点、scale、items），不做像素级截图对比。
 */
class GoldenTest {

    private fun run(name: String): Triple<BufferedImage, app.pictext.core.model.PageLayout, app.pictext.core.model.ReflowPlan> {
        val stream = javaClass.getResourceAsStream("/golden/$name.png")
            ?: error("missing golden fixture $name")
        val image = ImageIO.read(stream)
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)
        val gray = Grayscale.fromArgb(w, h, pixels)
        val layout = LayoutAnalyzer().analyze(gray, w, h)
        val plan = ReflowPlanner().plan(layout, ReflowOptions(outputWidth = 1080, targetEm = 44))
        println(
            "golden=$name ${w}x$h lines=${layout.textLines().size} figures=${layout.blocks.count { it is Block.Figure }} " +
                "splitX=${layout.splitX} clean=${"%.2f".format(layout.cleanRatio)} scale=${"%.3f".format(plan.scale)} " +
                "mode=${plan.mode}/${plan.fallback} items=${plan.items.size} h=${plan.height} roles=${layout.roleCounts()} " +
                "unsplitBody=" + layout.textLines()
                .filter { it.role == app.pictext.core.model.LineRole.BODY && !it.split }
                .joinToString { "y" + it.band.top + "x" + it.band.left + "-" + it.band.right + "h" + it.band.height },
        )
        return Triple(image, layout, plan)
    }

    @Test
    fun `main fixture matches prototype`() {
        val (_, layout, plan) = run("main")
        val textLines = layout.textLines().size
        val figures = layout.blocks.count { it is Block.Figure }
        assertEquals("text lines", 21.0, textLines.toDouble(), 2.0)
        assertEquals("figures", 2.0, figures.toDouble(), 1.0)
        assertEquals("splitX", 543.0, layout.splitX.toDouble(), 3.0)
        assertTrue("cleanRatio=${layout.cleanRatio}", layout.cleanRatio >= 0.9f)
        assertEquals("scale", 1.993f, plan.scale, 0.05f)
        assertEquals("items", 37.0, plan.items.size.toDouble(), 2.0)
        assertEquals(PlanMode.REFLOW, plan.mode)
        assertEquals(1080, plan.outputWidth)
    }

    @Test
    fun `dark fixture keeps dark background`() {
        val (_, layout, plan) = run("dark")
        assertTrue(layout.isDark)
        assertEquals("text lines", 21.0, layout.textLines().size.toDouble(), 2.0)
        assertEquals("figures", 2.0, layout.blocks.count { it is Block.Figure }.toDouble(), 1.0)
        assertEquals(PlanMode.REFLOW, plan.mode)
        assertTrue("scale=${plan.scale}", plan.scale > 1.8f)
    }

    @Test
    fun `photo only fixture falls back to original`() {
        val (_, layout, plan) = run("photo")
        assertEquals(0, layout.textLines().size)
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertEquals(FallbackReason.NO_TEXT, plan.fallback)
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `analysis is fast enough on a 1080x1540 page`() {
        val (_, _, _) = run("main") // 预热
        val start = System.nanoTime()
        val (_, layout, _) = run("main")
        val ms = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("analysis took ${ms}ms (JVM 调试阈值 400ms)", ms < 400.0)
        assertTrue(layout.textLines().isNotEmpty())
    }
}
