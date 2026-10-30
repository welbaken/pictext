package app.pictext.core.layout

import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReadingOrder] 的验收测试（docs/07 §4.7）。
 *
 * 所有用例都只用几何框，不依赖像素；行高中位数统一取 32（默认参数下
 * `minGapY = max(4, 0.25×32) = 8`、`minGapX = max(4, 0.5×32) = 16`），
 * 需要验证兜底值的用例单独传 `<= 0`。
 */
class ReadingOrderTest {

    private val reader = ReadingOrder()

    // -----------------------------------------------------------------------
    // 1. 截图用例（§4.7.2）
    // -----------------------------------------------------------------------

    @Test
    fun `screenshot layout reads A C B then D then E F G H then I J K L`() {
        // 全页 1000×640，四层横带之间各留 28px 贯通空白（≥ minGapY=8）。
        // 第 1 带里 B 的 y 范围盖住 A/C 之间的空隙，所以整带不能横切，只能先竖切成
        // [A,C] 与 [B]；[A,C] 再按 y 切成 A、C。
        val a = box(A, RegionKind.TEXT, 40, 40, 460, 70)
        val c = box(C, RegionKind.TEXT, 40, 82, 460, 112)
        val b = box(B, RegionKind.FIGURE, 560, 40, 960, 112)
        val d = box(D, RegionKind.TEXT, 40, 140, 960, 180)
        val e = box(E, RegionKind.FIGURE, 40, 208, 300, 400)
        val f = box(F, RegionKind.TEXT, 320, 208, 620, 260)
        val g = box(G, RegionKind.TEXT, 320, 272, 620, 324)
        val h = box(H, RegionKind.TEXT, 320, 336, 620, 388)
        val i = box(I, RegionKind.FIGURE, 40, 428, 300, 600)
        val j = box(J, RegionKind.TEXT, 320, 428, 620, 480)
        val k = box(K, RegionKind.TEXT, 320, 492, 620, 544)
        val l = box(L, RegionKind.TEXT, 320, 556, 620, 608)

        // 入参故意打乱，且 C 排在 A 前面：输出只能由几何决定，不能依赖列表顺序。
        val input = listOf(d, b, i, c, a, l, f, k, e, h, j, g)

        val result = reader.order(input, 32f)

        assertEquals(listOf(A, C, B, D, E, F, G, H, I, J, K, L), ids(result))
        assertFalse(result.ambiguous)
        assertSequentialOrder(result)
        assertTrue("入参对象不能被写回 order", input.all { it.order == -1 })
    }

    // -----------------------------------------------------------------------
    // 2. 双栏
    // -----------------------------------------------------------------------

    @Test
    fun `two columns read the whole left column before the right`() {
        // 右栏段落的 y 起点与左栏错开，使全页 y 投影 40..212 连续：横向断带不成立，
        // 只能按 x 投影（340..420 的 80px 空白）切成左右两列。
        val l1 = box(1, RegionKind.TEXT, 60, 40, 340, 80)
        val l2 = box(2, RegionKind.TEXT, 60, 96, 340, 136)
        val l3 = box(3, RegionKind.TEXT, 60, 152, 340, 192)
        val r1 = box(4, RegionKind.TEXT, 420, 60, 700, 100)
        val r2 = box(5, RegionKind.TEXT, 420, 116, 700, 156)
        val r3 = box(6, RegionKind.TEXT, 420, 172, 700, 212)

        val result = reader.order(listOf(r3, l1, r1, l3, r2, l2), 32f)

        assertEquals(listOf(1, 2, 3, 4, 5, 6), ids(result))
        assertFalse(result.ambiguous)
        assertSequentialOrder(result)
    }

    // -----------------------------------------------------------------------
    // 3. 通栏标题 + 双栏
    // -----------------------------------------------------------------------

    @Test
    fun `full width title precedes both columns`() {
        // 标题底 60 与两栏顶 100 之间有 40px 贯通空白 → 先横切成 [标题] [两栏]，
        // 两栏内部 y 投影连续，再按 x 切成左栏、右栏。
        val title = box(1, RegionKind.TITLE, 60, 20, 700, 60)
        val l1 = box(2, RegionKind.TEXT, 60, 100, 340, 140)
        val l2 = box(3, RegionKind.TEXT, 60, 156, 340, 196)
        val l3 = box(4, RegionKind.TEXT, 60, 212, 340, 252)
        val r1 = box(5, RegionKind.TEXT, 420, 120, 700, 160)
        val r2 = box(6, RegionKind.TEXT, 420, 176, 700, 216)
        val r3 = box(7, RegionKind.TEXT, 420, 232, 700, 272)

        val result = reader.order(listOf(r2, l3, title, r3, l1, r1, l2), 32f)

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), ids(result))
        assertFalse(result.ambiguous)
        assertSequentialOrder(result)
    }

    // -----------------------------------------------------------------------
    // 4. 2×2 卡片
    // -----------------------------------------------------------------------

    @Test
    fun `2x2 cards read row major`() {
        // 行间空白 60px、列间空白 120px 都存在；XY-cut 横带优先 → 行优先。
        val tl = box(1, RegionKind.FIGURE, 40, 40, 300, 140)
        val tr = box(2, RegionKind.FIGURE, 420, 40, 680, 140)
        val bl = box(3, RegionKind.FIGURE, 40, 200, 300, 300)
        val br = box(4, RegionKind.FIGURE, 420, 200, 680, 300)

        val result = reader.order(listOf(br, tl, tr, bl), 32f)

        assertEquals(listOf(1, 2, 3, 4), ids(result))
        assertFalse(result.ambiguous)
        assertSequentialOrder(result)
    }

    // -----------------------------------------------------------------------
    // 5. 图注在图片右侧
    // -----------------------------------------------------------------------

    @Test
    fun `caption to the right of the figure comes after it`() {
        // 图注的 y 完全落在图片的 y 范围内 → 横带切不开；x 方向有 40px 贯通空白
        // （≥ minGapX=16）→ 先图后注。
        val image = box(1, RegionKind.FIGURE, 40, 40, 360, 220)
        val caption = box(2, RegionKind.CAPTION, 400, 80, 700, 140)

        val result = reader.order(listOf(caption, image), 32f)

        assertEquals(listOf(1, 2), ids(result))
        assertFalse(result.ambiguous)
        assertSequentialOrder(result)
    }

    // -----------------------------------------------------------------------
    // 6. 框互相重叠 1px
    // -----------------------------------------------------------------------

    @Test
    fun `one pixel overlap does not create a false horizontal break`() {
        // B.top=139, A.bottom=140：半开区间上重叠 [139,140)。扫描线覆盖数在 139
        // 变成 2、在 140 降回 1，从不降为 0 → 没有横带；两框左右也完全重合 → 没有竖列。
        // 若把 1px 重叠误判成断口，这里会切开并返回 ambiguous=false，故用 ambiguous=true
        // 反证“没有假断口”，同时顺序必须是 A→B。
        val a = box(1, RegionKind.TEXT, 40, 40, 360, 140)
        val b = box(2, RegionKind.TEXT, 40, 139, 360, 239)

        val result = reader.order(listOf(b, a), 32f)

        assertEquals("1px 重叠不能产生假断口，按 (top,left) 稳定排序", listOf(1, 2), ids(result))
        assertTrue("切不开时应标歧义，而不是假装切开了", result.ambiguous)
        assertSequentialOrder(result)
    }

    // -----------------------------------------------------------------------
    // 7. 切不开
    // -----------------------------------------------------------------------

    @Test
    fun `mutually overlapping boxes fall back to stable top left order`() {
        // 三个框在 y 上首尾相接成 40..260、在 x 上连成 20..380，两个方向都没有贯通空白。
        // 按照算法必须稳定排序并标歧义，不能抛异常。
        val a = box(1, RegionKind.TEXT, 40, 40, 360, 140)
        val b = box(2, RegionKind.TEXT, 60, 100, 380, 200)
        val c = box(3, RegionKind.TEXT, 20, 160, 340, 260)

        val result = reader.order(listOf(c, a, b), 32f)

        assertEquals(listOf(1, 2, 3), ids(result))
        assertTrue("两个方向都切不开必须标歧义", result.ambiguous)
        assertSequentialOrder(result)
    }

    // -----------------------------------------------------------------------
    // 8. 空列表 / 单元素
    // -----------------------------------------------------------------------

    @Test
    fun `empty list and single region keep trivial order`() {
        val none = reader.order(emptyList(), 32f)
        assertTrue(none.regions.isEmpty())
        assertFalse(none.ambiguous)

        val only = box(7, RegionKind.TEXT, 40, 40, 300, 80)
        val single = reader.order(listOf(only), 32f)

        assertEquals(listOf(7), ids(single))
        assertEquals(0, single.regions[0].order)
        assertFalse(single.ambiguous)
        assertEquals("单元素也必须只读入参", -1, only.order)
    }

    // -----------------------------------------------------------------------
    // 补充：行高兜底与深度上限（对应 §4.7.1 的两条防御规则）
    // -----------------------------------------------------------------------

    @Test
    fun `non positive median line height falls back to sixteen`() {
        // 两框之间只有 6px 横向空白。按兜底行高 16 算 minGapX = max(4, 8) = 8，6px 不够，
        // 两个方向都切不开 → ambiguous=true；若错误地按 0 行高算（minGapX=4），就会被切开。
        val left = box(1, RegionKind.TEXT, 40, 40, 340, 140)
        val right = box(2, RegionKind.TEXT, 346, 40, 646, 140)

        val zero = reader.order(listOf(left, right), 0f)
        assertEquals(listOf(1, 2), ids(zero))
        assertTrue("0 行高必须按 16 兜底，6px < minGapX=8", zero.ambiguous)

        val negative = reader.order(listOf(left, right), -5f)
        assertTrue("负行高同样按 16 兜底", negative.ambiguous)
    }

    @Test
    fun `depth cap degrades to top left order with ambiguity`() {
        // 2×2 卡片本身能按行切开（见用例 4）；把递归深度限成 1 后，根节点切成两行，
        // 行节点到达上限 → 稳定排序 + ambiguous=true，且不能抛异常。
        val tl = box(1, RegionKind.FIGURE, 40, 40, 300, 140)
        val tr = box(2, RegionKind.FIGURE, 420, 40, 680, 140)
        val bl = box(3, RegionKind.FIGURE, 40, 200, 300, 300)
        val br = box(4, RegionKind.FIGURE, 420, 200, 680, 300)

        val shallow = ReadingOrder(LayoutOptions(orderMaxDepth = 1))
        val result = shallow.order(listOf(br, tl, tr, bl), 32f)

        assertEquals("超深度时仍按 (top,left) 稳定排序，不抛异常", listOf(1, 2, 3, 4), ids(result))
        assertTrue("超深度必须置 ambiguous=true", result.ambiguous)
        assertSequentialOrder(result)
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    private fun box(
        id: Int,
        kind: RegionKind,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): LayoutRegion = LayoutRegion(id = id, kind = kind, bounds = RectI(left, top, right, bottom))

    private fun ids(result: OrderResult): List<Int> = result.regions.map { it.id }

    /** 写回必须是从 0 开始连续递增的序号。 */
    private fun assertSequentialOrder(result: OrderResult) {
        result.regions.forEachIndexed { index, region ->
            assertEquals("第 $index 个区域的 order 应写回 $index", index, region.order)
        }
    }

    private companion object {
        // 截图用例的 12 个区域：A=1 … L=12。
        const val A = 1
        const val B = 2
        const val C = 3
        const val D = 4
        const val E = 5
        const val F = 6
        const val G = 7
        const val H = 8
        const val I = 9
        const val J = 10
        const val K = 11
        const val L = 12
    }
}
