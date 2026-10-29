package app.pictext.core

import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.GrayImage
import app.pictext.core.model.PlanMode
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 异常输入只允许回退，不允许崩溃。 */
class ChaosTest {

    private fun analyzeAndPlan(image: GrayImage) =
        LayoutAnalyzer().analyze(image).let {
            ReflowPlanner().plan(it, ReflowOptions(outputWidth = 1080))
        }

    @Test
    fun `one pixel image`() {
        val plan = analyzeAndPlan(grayPage(1, 1, 255))
        assertEquals(PlanMode.ORIGINAL, plan.mode)
        assertTrue(plan.fallback != FallbackReason.NONE)
    }

    @Test
    fun `solid color image`() {
        val plan = analyzeAndPlan(grayPage(400, 400, 128))
        assertEquals(PlanMode.ORIGINAL, plan.mode)
    }

    @Test
    fun `random noise image does not crash`() {
        val r = Random(42)
        val data = ByteArray(200 * 200) { r.nextInt(256).toByte() }
        val image = GrayImage(200, 200, data)
        val plan = analyzeAndPlan(image)
        assertTrue(plan.mode == PlanMode.ORIGINAL || plan.mode == PlanMode.NO_SPLIT || plan.mode == PlanMode.REFLOW)
    }

    @Test
    fun `extreme long strip`() {
        val w = 300
        val h = 3000
        val img = grayPage(w, h, 255)
        for (y in 20 until h - 20 step 40) {
            img.fillRect(20, y, w - 20, y + 14, 20)
        }
        val plan = analyzeAndPlan(img)
        assertTrue(plan.outputWidth == 1080)
    }

    @Test
    fun `zero size image`() {
        val plan = analyzeAndPlan(GrayImage(0, 0, ByteArray(0)))
        assertEquals(PlanMode.ORIGINAL, plan.mode)
    }
}
