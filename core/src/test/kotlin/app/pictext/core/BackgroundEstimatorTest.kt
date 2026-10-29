package app.pictext.core

import app.pictext.core.analyze.BackgroundEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundEstimatorTest {

    private val estimator = BackgroundEstimator()

    @Test
    fun `pure white has no ink`() {
        val img = grayPage(64, 64, 255)
        val r = estimator.estimate(img)
        assertFalse(r.isDark)
        assertEquals(0, r.ink.count { (it.toInt() and 0xFF) > 5 })
    }

    @Test
    fun `pure black has no ink and is dark`() {
        val img = grayPage(64, 64, 0)
        val r = estimator.estimate(img)
        assertTrue(r.isDark)
        assertEquals(0, r.ink.count { (it.toInt() and 0xFF) > 5 })
    }

    @Test
    fun `dark block is not eaten by background`() {
        val img = grayPage(64, 64, 255)
        img.fillRect(20, 20, 40, 40, 30)
        val r = estimator.estimate(img)
        assertTrue(r.inkAt(30, 30, 64) > 100)
        assertTrue(r.inkAt(3, 3, 64) < 5)
    }

    @Test
    fun `light card is background and its black text is ink`() {
        val img = grayPage(64, 64, 255)
        img.fillRect(12, 12, 52, 52, 244)
        img.fillRect(28, 28, 36, 36, 0)
        val r = estimator.estimate(img)
        assertTrue(
            "card ink=${r.inkAt(20, 20, 64)} seed=${r.seed} bg=${r.background[20 * 64 + 20]}",
            r.inkAt(20, 20, 64) < 5,
        )
        assertTrue("text ink=${r.inkAt(32, 32, 64)}", r.inkAt(32, 32, 64) > 100)
    }

    @Test
    fun `text on a horizontal gradient still has high ink`() {
        val w = 80
        val h = 40
        val img = grayPage(w, h, 0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = 100 + (155 * x / (w - 1))
                img.data[y * w + x] = v.toByte()
            }
        }
        img.fillRect(6, 15, 14, 25, 0)
        img.fillRect(66, 15, 74, 25, 0)
        val r = estimator.estimate(img)
        assertTrue("left=${r.inkAt(10, 20, w)}", r.inkAt(10, 20, w) > 80)
        assertTrue("right=${r.inkAt(70, 20, w)}", r.inkAt(70, 20, w) > 80)
    }
}
