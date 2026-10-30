package app.pictext.core.layout

import app.pictext.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-45：深色页把**模型输入**反相，但灰度（覆盖审计用）必须保持原图。
 *
 * 为什么这两件事必须分开：模型是在浅底文档上训的，深底浅字属于分布外，反相能把它拉回分布内；
 * 而覆盖审计走的是 v1 的背景估计 + Otsu，那套算法本来就吃得下深底，把反相后的像素喂给它
 * 反而会让“墨迹”的定义和细化（吃原图）不一致，覆盖率与残差框都会飘。
 */
class LayoutPreprocessorTest {

    private val contract = ModelContract()
    private val preprocessor = LayoutPreprocessor(contract)

    /**
     * 无 padding 的 tile：源图 [size]×[size] 被拉伸到整个 480×480 模型输入。
     *
     * `innerWidth/innerHeight` 必须是模型边长——预处理 loop 的写回范围是
     * `pad..pad+inner`，它们就是“模型输入里被 tile 覆盖的那块”。
     */
    private fun tile(size: Int = 64): Tile = Tile(
        index = 0,
        row = 0,
        col = 0,
        src = RectI(0, 0, size, size),
        innerWidth = 480,
        innerHeight = 480,
        padX = 0,
        padY = 0,
        modelInput = 480,
    )

    /** 一整块纯色（ARGB）的 tile。 */
    private fun solid(size: Int, argb: Int): IntArray = IntArray(size * size) { argb }

    private fun modelValueAt(input: LayoutInput, x: Int, y: Int, channel: Int): Float =
        input.data[input.plane(channel) + y * contract.modelInput + x]

    private fun grayAt(gray: ByteArray, x: Int, y: Int): Int =
        gray[y * contract.modelInput + x].toInt() and 0xFF

    @Test
    fun `dark page input is inverted while gray keeps the source pixels`() {
        val t = tile()
        val argb = solid(64, 0xFF202020.toInt()) // 深底
        val input = LayoutInput(480, 480, 3)
        val gray = ByteArray(480 * 480)

        preprocessor.process(argb, 64, 64, t, input, gray, invert = true)

        // 模型输入：0x20(32) → 反相成 223，归一化后应等于 normalize(223)。
        val expected = contract.normalize(0, 255f - 32f)
        assertEquals(expected, modelValueAt(input, 240, 240, 0), 1e-4f)
        // 灰度：仍然是原图的 32，不受反相影响。
        assertEquals(32, grayAt(gray, 240, 240))
    }

    @Test
    fun `light page input stays untouched`() {
        val t = tile()
        val argb = solid(64, 0xFFF5F5F5.toInt())
        val input = LayoutInput(480, 480, 3)
        val gray = ByteArray(480 * 480)

        preprocessor.process(argb, 64, 64, t, input, gray, invert = false)

        assertEquals(contract.normalize(0, 245f), modelValueAt(input, 240, 240, 0), 1e-4f)
        assertEquals(245, grayAt(gray, 240, 240))
    }

    @Test
    fun `inverting swaps dark and light inputs`() {
        val dark = LayoutInput(480, 480, 3)
        val light = LayoutInput(480, 480, 3)
        val t = tile()
        // 0x1F 与 0xE0 是精确互补（31 + 224 = 255），避免取整带来的 1 点误差。
        preprocessor.process(solid(64, 0xFF1F1F1F.toInt()), 64, 64, t, dark, null, invert = false)
        preprocessor.process(solid(64, 0xFFE0E0E0.toInt()), 64, 64, t, light, null, invert = true)

        assertEquals(modelValueAt(dark, 240, 240, 0), modelValueAt(light, 240, 240, 0), 1e-4f)
        assertNotEquals(
            modelValueAt(dark, 240, 240, 0),
            modelValueAt(light, 240, 240, 1),
        )
    }

    @Test
    fun `padding follows the inversion so the border does not glow`() {
        // 页面比 tile 小时会居中补边；反相时补边值也要取反，否则四周会出现一圈亮边。
        val small = Tile(
            index = 0,
            row = 0,
            col = 0,
            src = RectI(0, 0, 32, 32),
            innerWidth = 32,
            innerHeight = 32,
            padX = 224,
            padY = 224,
            modelInput = 480,
        )
        val input = LayoutInput(480, 480, 3)
        preprocessor.process(solid(32, 0xFF202020.toInt()), 32, 32, small, input, null, invert = true)

        val padExpected = contract.normalize(0, 255f - contract.padValue)
        assertEquals(padExpected, modelValueAt(input, 10, 10, 0), 1e-4f)
        assertTrue("pad 区应和反相后的深底接近，而不是原 padValue",
            kotlin.math.abs(modelValueAt(input, 10, 10, 0) - padExpected) < 1e-4f)
    }
}
