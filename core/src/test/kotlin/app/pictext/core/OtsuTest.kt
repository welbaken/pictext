package app.pictext.core

import app.pictext.core.analyze.Binarizer
import app.pictext.core.util.Otsu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtsuTest {

    @Test
    fun `two peaks give a separating threshold`() {
        val h = IntArray(256)
        h[0] = 500
        h[200] = 500
        val t = Otsu.threshold(h)
        assertTrue("threshold=$t", t in 90..110)
    }

    @Test
    fun `empty histogram is safe`() {
        assertEquals(0, Otsu.threshold(IntArray(256)))
    }

    @Test
    fun `single peak does not crash`() {
        val h = IntArray(256)
        h[128] = 1000
        assertTrue(Otsu.threshold(h) in 0..255)
    }

    @Test
    fun `threshold clamps to floor and ceil`() {
        val b = Binarizer()
        val allZero = ByteArray(16)
        val r0 = b.binarize(allZero, 4, 4)
        assertEquals(12, r0.threshold)
        assertFalse(r0.mask.any { it })

        val ink = ByteArray(16) { if (it % 2 == 0) 0 else 255.toByte() }
        val r1 = b.binarize(ink, 4, 4)
        assertEquals(80, r1.threshold)
    }

    @Test
    fun `isolated point is cleaned but long thin line is kept`() {
        val b = Binarizer()
        val w = 60
        val h = 20
        val mask = BooleanArray(w * h)
        mask[10 * w + 10] = true // 一个孤立点
        for (x in 5 until 55) mask[5 * w + x] = true // 1px 长横线
        val cleaned = b.clean(mask, w, h)
        assertFalse(cleaned[10 * w + 10])
        assertTrue(cleaned[5 * w + 30])
        assertTrue(cleaned[5 * w + 5])
    }
}
