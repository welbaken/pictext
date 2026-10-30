package app.pictext.core.layout

import app.pictext.core.model.CutKind
import app.pictext.core.model.RegionKind
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.util.Grayscale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * V2-13 之三：**真实屏幕像素上的切点与切分**（不加载模型）。
 *
 * 这条测试走的是产品真实链路的一半：屏幕 PNG → `RegionRefiner`（ROI 细化 + 三级切点特征）
 * → `CutPolicy`（硬缝 → 软缝 → 强制）。真值只提供“哪些区域是文字”，
 * **切点完全是算出来的**，所以它能抓住“紧排中文切不动”“敏感行被劈开”这类回归。
 *
 * 重点验收（docs/07 §4.8.2、V2-21）：
 * - 敏感行（价格/URL/代码/电话）**永远不出现 FORCED**；
 * - 强制切只对“汉字正文、墨迹不像图”的行开放，且全页强制比例要低；
 * - 任何一行的段都首尾相接、不丢列。
 */
class FixtureCutPolicyTest {

    private val contentWidth = 1042 // 1080 - 2×19
    private val scale = 2f

    private data class FixtureCuts(
        val id: String,
        val lines: Int,
        val split: Int,
        val forced: Int,
        val sensitive: Int,
        val sensitiveForced: Int,
        val compressed: Int,
        val safeCut: Int,
    ) {
        val forcedRatio: Float get() = if (split == 0) 0f else forced.toFloat() / split
    }

    private fun grayOf(file: File): app.pictext.core.model.GrayImage? {
        if (!file.isFile) return null
        val img = ImageIO.read(file) ?: return null
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        return Grayscale.fromArgb(w, h, pixels)
    }

    private fun run(id: String): FixtureCuts? {
        val gt = Fixtures.load(id, 1080) ?: return null
        val gray = grayOf(Fixtures.screen(id, 1080)) ?: return null
        val page = PageContext(srcWidth = gray.width, srcHeight = gray.height)
        val sampler = GrayPageRegionSampler(gray)
        val refiner = RegionRefiner()
        val policy = CutPolicy(ReflowOptions(outputWidth = 1080))

        var lines = 0
        var split = 0
        var forced = 0
        var sensitive = 0
        var sensitiveForced = 0
        var compressed = 0
        var safeCut = 0

        for (region in gt.regionsForLayout().filter { it.isTextual }) {
            val refined = refiner.refine(sampler, page, region)
            for (line in refined.lines) {
                lines++
                val allowForced = line.role != app.pictext.core.model.LineRole.CAPTION &&
                    region.kind != RegionKind.CAPTION
                val decision = policy.decide(line, contentWidth, scale, allowForced)
                // 段必须首尾相接、覆盖整行、宽度为正。
                assertEquals("$id 段起点", line.band.left, decision.segments.first().x0)
                assertEquals("$id 段终点", line.band.right, decision.segments.last().x1)
                for (i in 0 until decision.segments.size - 1) {
                    assertEquals(
                        "$id 段必须首尾相接",
                        decision.segments[i].x1,
                        decision.segments[i + 1].x0,
                    )
                }
                assertTrue("$id 段宽必须为正", decision.segments.all { it.width > 0 })
                assertEquals("$id 段总宽", line.band.width, decision.totalWidth)

                if (decision.isSplit) split++
                val forcedCuts = decision.forcedCuts
                if (forcedCuts > 0) forced++
                if (decision.compressed) compressed++
                if (line.sensitive) {
                    sensitive++
                    if (forcedCuts > 0) sensitiveForced++
                }
                if (decision.segments.any { it.cut != CutKind.FORCED }) safeCut++
                if (line.sensitive) {
                    assertTrue(
                        "$id 敏感行不得出现强制切：band=${line.band} features=${line.cutFeatures}",
                        decision.segments.none { it.cut == CutKind.FORCED },
                    )
                }
            }
        }
        return FixtureCuts(id, lines, split, forced, sensitive, sensitiveForced, compressed, safeCut)
    }

    @Test
    fun `tight sensitive fixture never force cuts a sensitive line`() {
        val r = run("12-tight-sensitive")
        assumeTrue("缺少 screens/12-tight-sensitive@1080.png", r != null)
        r!!
        println(
            "=== FixtureCutPolicyTest(12-tight-sensitive) lines=${r.lines} split=${r.split} " +
                "forced=${r.forced} sensitive=${r.sensitive} compressed=${r.compressed}",
        )
        assertTrue("细化必须出得来行（lines=${r.lines}）", r.lines > 0)
        assertEquals("敏感行零强制", 0, r.sensitiveForced)
        // 这张夹具就是“紧排粗体中文”，强制切允许存在，但必须是小比例（失败线是 15%）。
        assertTrue(
            "强制切比例 ${"%.3f".format(r.forcedRatio)} 超过 15%（split=${r.split} forced=${r.forced}）",
            r.forcedRatio <= 0.15f,
        )
    }

    @Test
    fun `refined lines never cross the fixture page`() {
        for (id in listOf("12-tight-sensitive", "01-article-light", "08-dark-article")) {
            val gt = Fixtures.load(id, 1080) ?: continue
            val gray = grayOf(Fixtures.screen(id, 1080)) ?: continue
            val page = PageContext(srcWidth = gray.width, srcHeight = gray.height)
            val sampler = GrayPageRegionSampler(gray)
            val refiner = RegionRefiner()
            for (region in gt.regionsForLayout().filter { it.isTextual }) {
                val refined = refiner.refine(sampler, page, region)
                for (line in refined.lines) {
                    assertTrue("$id 行越界 ${line.band}", line.band.left >= 0 && line.band.top >= 0)
                    assertTrue(
                        "$id 行越界 ${line.band}",
                        line.band.right <= gray.width && line.band.bottom <= gray.height,
                    )
                }
            }
        }
    }

    @Test
    fun `normal fixtures produce a safe cut for most rows`() {
        val ids = listOf("01-article-light", "03-news-columns", "08-dark-article", "11-doc-two-column")
        val rows = ArrayList<String>()
        for (id in ids) {
            val r = run(id) ?: continue
            rows.add(
                "%-22s lines=%-3d split=%-3d forced=%-2d safe=%-3d compressed=%-2d".format(
                    r.id, r.lines, r.split, r.forced, r.safeCut, r.compressed,
                ),
            )
            assertTrue("$id 强制比 ${r.forcedRatio}", r.forcedRatio <= 0.15f)
            // 正常文档里，绝大多数行都能找到安全缝；找不到的应该被压缩而不是劈字。
            assertTrue(
                "$id 安全缝覆盖 ${r.safeCut}/${r.lines}",
                r.safeCut >= r.lines * 0.6,
            )
        }
        assumeTrue("没有可用的夹具屏幕图", rows.isNotEmpty())
        println("=== FixtureCutPolicyTest ===\n" + rows.joinToString("\n"))
    }
}
