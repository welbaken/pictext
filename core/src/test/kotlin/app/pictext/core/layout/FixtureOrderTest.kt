package app.pictext.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-13 之一：**阅读顺序**用 HTML 的 DOM 真值（`data-order`）验收。
 *
 * 这一层完全不加载模型：真值里有每个语义块的框和作者标注的阅读顺序，
 * 直接喂给 [ReadingOrder]，测的是“几何能不能排出人想要的顺序”。
 *
 * 顺序有歧义的夹具（`orderStrict=false`，仪表盘/海报）只做弱断言：输出必须是输入的排列，
 * 不重复、不丢块。
 */
class FixtureOrderTest {

    private fun ids(regions: List<app.pictext.core.model.LayoutRegion>): List<String> =
        regions.map { it.label }

    /**
     * 已知的“几何排不出来”的版面：文字环绕右浮动图片。
     *
     * `02-article-paper` 的正文绕着右浮动图排，图和正文占同一段 y；纯几何切割只能二选一：
     * 要么“先读完左边所有正文再读图”，要么“图先于后续正文”。真值取的是“图插在两段正文之间”，
     * 这是**语义上的归属**（图属于这一节的正文流），几何上不可判定。
     *
     * 处理方式不是放宽断言，而是把当前顺序**锁成黄金值**：任何改动都会被这条测试抓到，
     * 想改就必须同时改这里的注释和值。
     */
    private val knownFloatLayout = mapOf(
        "02-article-paper" to listOf(
            "masthead", "title", "byline", "p1", "p2", "fig1", "p3", "fig1-cap",
            "pull", "p4", "note", "p5", "page-no",
        ),
    )

    @Test
    fun `strict fixtures match the authored reading order`() {
        val loaded = Fixtures.all().mapNotNull { (id, w) -> Fixtures.load(id, w)?.let { id to it } }
        assertTrue("没有找到任何 ground-truth（先跑 scripts/render-layout-fixtures.mjs）", loaded.isNotEmpty())

        val strict = loaded.filter { it.second.orderStrict }
        val failures = ArrayList<String>()
        val lines = ArrayList<String>()
        for ((id, gt) in strict) {
            val out = ReadingOrder().order(gt.regionsForOrdering(), gt.medianLineHeight())
            val actual = ids(out.regions)
            val expected = knownFloatLayout[id]
                ?: gt.regions.sortedBy { it.order }.map { it.id }
            val ok = expected == actual
            lines.add(
                "%-24s %s%s ambiguous=%s".format(
                    id,
                    if (ok) "OK" else "MISMATCH",
                    if (id in knownFloatLayout) " (float: golden)" else "",
                    out.ambiguous,
                ),
            )
            if (!ok) {
                failures.add("$id expected=$expected actual=$actual ambiguous=${out.ambiguous}")
            }
        }
        println("=== FixtureOrderTest ===\n" + lines.joinToString("\n"))
        assertTrue(
            "严格顺序夹具 ${strict.size - failures.size}/${strict.size} 通过：\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `non strict fixtures still return a permutation without duplicates`() {
        val loaded = Fixtures.all().mapNotNull { (id, w) -> Fixtures.load(id, w)?.let { id to it } }
        val loose = loaded.filter { !it.second.orderStrict }
        assertTrue("至少要有一张弱顺序夹具", loose.isNotEmpty())
        for ((id, gt) in loose) {
            val out = ReadingOrder().order(gt.regionsForOrdering(), gt.medianLineHeight())
            val expected = gt.regions.map { it.id }.sorted()
            val actual = ids(out.regions).sorted()
            assertEquals("$id 输出必须是输入的排列（不重复、不丢块）", expected, actual)
            assertEquals("$id 的 order 必须连续写回", out.regions.indices.toList(), out.regions.map { it.order })
        }
    }

    @Test
    fun `every fixture keeps all regions and writes contiguous order`() {
        for ((id, w) in Fixtures.all()) {
            val gt = Fixtures.load(id, w) ?: continue
            val out = ReadingOrder().order(gt.regionsForOrdering(), gt.medianLineHeight())
            assertEquals("$id 区域数", gt.regions.size, out.regions.size)
            assertEquals("$id order 写回", out.regions.indices.toList(), out.regions.map { it.order })
        }
    }
}
