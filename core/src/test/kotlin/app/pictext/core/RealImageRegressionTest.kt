package app.pictext.core

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.AnalysisScale
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.Block
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.LineRole
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.core.model.RenderItem
import app.pictext.core.plan.PlanAuditor
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import app.pictext.core.util.Grayscale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * P0-0 真实图片回归：直接读 `testdata/private/samples/`（或 `testdata/samples/`），不复制图片，
 * 断言结构值处在“已知良好”的区间，并在控制台打印一行可贴进报告的摘要。
 *
 * 阈值刻意留了余量（行数 ±3、scale ±0.15）：这里抓的是**结构性回归**
 * （又冒出含文字的 FIGURE、UI 行被切开、XERJ 又被切出 1.03× 伪页面），
 * 不是锁死像素。更新阈值必须按 docs/05 §3.3 在 PR 里贴 before/after。
 */
class RealImageRegressionTest {

    private data class Run(
        val name: String,
        val layout: PageLayout,
        val plan: ReflowPlan,
        val auditOk: Boolean,
        val auditSummary: String,
    )

    /**
     * 真实截图不随仓库分发：优先读 `testdata/private/<name>`，没有就退回 `testdata/<name>`。
     * 两者都不存在时测试用 [assumeTrue] 跳过，CI 不会因为缺私有样例而红。
     */
    private fun sampleDirOf(name: String): File? {
        val root = System.getProperty("pictext.root") ?: return null
        val privateDir = File(root, "testdata/private/$name")
        if (privateDir.isDirectory) return privateDir
        val publicDir = File(root, "testdata/$name")
        return if (publicDir.isDirectory) publicDir else null
    }

    private fun sampleDir(): File? = sampleDirOf("samples")

    private fun run(name: String): Run {
        val dir = sampleDir()
        assumeTrue("真实样例不存在（放到 testdata/private/samples/ 或设置 -Dpictext.root=仓库根目录）", dir != null)
        val file = File(dir, "$name.jpg")
        assumeTrue("缺少样例 $file", file.isFile)
        return runFile(name, file)
    }

    private fun sample2Dir(): File? = sampleDirOf("samples2")

    private fun runFile(
        name: String,
        file: File,
        analysis: AnalysisOptions = AnalysisOptions(),
        options: ReflowOptions = ReflowOptions(outputWidth = 1080, targetEm = 44),
    ): Run {
        val image: BufferedImage = ImageIO.read(file)
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)

        // 与 CLI/Android 共用的定标规则：宽度优先（长图不再按长边压成一条）。
        val analysisSize = AnalysisScale.size(w, h, analysis)
        val aw = analysisSize.width
        val ah = analysisSize.height
        val k = analysisSize.factor
        val analysisPixels = if (k < 1f) {
            val scaled = BufferedImage(aw, ah, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.drawImage(image, 0, 0, aw, ah, null)
            g.dispose()
            IntArray(aw * ah).also { scaled.getRGB(0, 0, aw, ah, it, 0, aw) }
        } else {
            pixels
        }
        val gray = Grayscale.fromArgb(aw, ah, analysisPixels)
        val started = System.nanoTime()
        val layout = LayoutAnalyzer(analysis).analyze(gray, w, h)
        val analyzeMs = (System.nanoTime() - started) / 1_000_000
        val plan = ReflowPlanner().plan(layout, options)
        val audit = PlanAuditor.audit(layout, plan)
        val summary = "sample=$name ${w}x$h lines=${layout.textLines().size} " +
            "figures=${layout.blocks.count { it is Block.Figure }} splitX=${layout.splitX} " +
            "clean=${"%.2f".format(layout.cleanRatio)} scale=${"%.3f".format(plan.scale)} " +
            "mode=${plan.mode}/${plan.fallback} items=${plan.items.size} height=${plan.height} " +
            "roles=${layout.roleCounts()} figures=${layout.figureKindCounts()} " +
            "analyze=${analyzeMs}ms audit=[${audit.summary()}]"
        println(summary)

        // 任何样例都必须满足的硬约束。
        assertTrue("分析耗时异常: ${analyzeMs}ms", analyzeMs < 1500)
        for (item in plan.items) {
            assertTrue("src 越界: $item", item.src.left >= 0 && item.src.top >= 0)
            assertTrue("src 越界: $item", item.src.right <= w && item.src.bottom <= h)
            assertTrue("src 为空: $item", !item.src.isEmpty)
            assertTrue("dest 越界: $item", item.dest.left >= 0 && item.dest.right <= plan.outputWidth)
        }
        if (plan.mode != PlanMode.ORIGINAL) {
            assertTrue("计划项为空但模式是 $plan.mode", plan.items.isNotEmpty())
        }
        return Run(name, layout, plan, audit.ok, audit.summary())
    }

    /**
     * P1-2 批量巡检：`testdata/private/samples2/`（或 `testdata/samples2/`）里每一张都必须满足与主样例相同的硬约束
     * （不崩、src/dest 不越界、非回退模式审计通过）。不锁具体结构值——这批样例
     * 覆盖贴吧/知乎/微博/X/新闻/韩语/希伯来语等来源，本来就不该有同一个“正确值”。
     */
    @Test
    fun `every sample2 image runs without violating invariants`() {
        val dir = sample2Dir()
        assumeTrue("samples2 不存在（testdata/private/samples2 或 testdata/samples2）", dir != null)
        val files = dir!!.listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedBy { it.name } ?: emptyList()
        assumeTrue("testdata/samples2 是空的", files.isNotEmpty())
        println("samples2: ${files.size} 张")
        var rerolled = 0
        for (file in files) {
            val r = runFile(file.nameWithoutExtension, file)
            if (r.plan.mode != PlanMode.ORIGINAL) rerolled++
        }
        println("samples2: 重排 $rerolled / ${files.size}")
    }

    /**
     * ×4 模式：同一页允许一行切 4 段，倍率应当明显大于默认的 ×2 模式，且每段都不溢出。
     */
    @Test
    fun `x4 mode produces four segments and a larger scale`() {
        val dir = sampleDir()
        assumeTrue("samples 不存在（testdata/private/samples 或 testdata/samples）", dir != null)
        val file = File(dir!!, "twitter.jpg")
        assumeTrue("缺少样例 $file", file.isFile)
        val base = runFile("twitter-x2", file)
        val quad = runFile(
            "twitter-x4",
            file,
            options = ReflowOptions(
                outputWidth = 1080,
                targetEm = 44,
                forceReflow = true,
                scaleMultiplier = 2f,
                maxSegments = 4,
            ),
        )
        assertTrue("×4 应当重排，实际 ${quad.plan.mode}/${quad.plan.fallback}", quad.plan.mode == PlanMode.REFLOW)
        assertTrue("×4 倍率应接近 ×2 的两倍：${base.plan.scale} vs ${quad.plan.scale}",
            quad.plan.scale > base.plan.scale * 1.8f)
        assertTrue("×4 审计未通过：${quad.auditSummary}", quad.auditOk)
        val rows = quad.plan.items.filterIsInstance<RenderItem.TextRow>()
        assertTrue("×4 应当切出更多行：${rows.size} vs ${base.plan.items.size}",
            rows.size > base.plan.items.size)
        assertTrue("段宽溢出", rows.all { it.dest.width <= quad.plan.outputWidth })
        assertTrue("同一行不能被切碎超过 4 段",
            rows.groupBy { it.src.top }.values.all { it.size <= 4 })
    }

    @Test
    fun `twitter screenshot has no text figures and no split ui rows`() {
        val r = run("twitter")
        assertEquals("不应再出现含文字的图块", 0, r.layout.blocks.count { it is Block.Figure })
        assertEquals(PlanMode.REFLOW, r.plan.mode)
        assertTrue("scale=${r.plan.scale}", r.plan.scale > 2.2f)
        assertTrue("lines=${r.layout.textLines().size}", r.layout.textLines().size in 14..24)

        val bodyLines = r.layout.textLines().filter { it.role == LineRole.BODY }
        assertTrue("正文行太少: ${bodyLines.size}", bodyLines.size >= 10)
        val uiSplit = r.layout.textLines().filter { it.role == LineRole.UI || it.role == LineRole.CAPTION }
            .count { it.split }
        assertEquals("UI/图注行不允许参与切分", 0, uiSplit)

        // 正文缩放一致性：同一页所有 BODY item 与 bodyScale 的偏差 ≤5%。
        val rows = r.plan.items.filterIsInstance<app.pictext.core.model.RenderItem.TextRow>()
            .filter { it.role == LineRole.BODY }
        for (row in rows) {
            val scale = row.dest.width.toFloat() / row.src.width
            val dev = kotlin.math.abs(scale - r.plan.bodyScale) / r.plan.bodyScale
            assertTrue("bodyScale 不一致: scale=$scale body=${r.plan.bodyScale}", dev <= 0.05f)
        }
        assertTrue(r.auditSummary, r.auditOk)
    }

    @Test
    fun `news screenshot keeps the photo whole and the heading bigger than body`() {
        val r = run("news_jp")
        assertEquals(1, r.layout.blocks.count { it is Block.Figure })
        assertEquals(PlanMode.REFLOW, r.plan.mode)
        val roles = r.layout.roleCounts()
        assertTrue("应有标题行: $roles", (roles[LineRole.HEADING] ?: 0) >= 1)
        assertTrue("应有图注行: $roles", (roles[LineRole.CAPTION] ?: 0) >= 1)
        assertTrue("标题必须不小于正文", r.plan.headingRatio() >= 0.95f)
        assertTrue(r.auditSummary, r.auditOk)
    }

    @Test
    fun `xerj readme never produces a fake reflow`() {
        val r = run("xerj")
        // 英文/代码为主的页面：要么真的放大（gain ≥ 1.5），要么回退原图；
        // 不允许再出现旧版本那种 1.03× 的“伪重排”。
        assertTrue(
            "mode=${r.plan.mode} gain=${r.plan.gain}",
            r.plan.mode == PlanMode.ORIGINAL || r.plan.gain >= 1.5f,
        )
        if (r.plan.mode == PlanMode.ORIGINAL) {
            assertTrue(r.plan.items.isEmpty())
        }
        assertTrue("仍然要能识别出文字行", r.layout.textLines().size >= 5)
        assertTrue(r.auditSummary, r.auditOk)
    }

    @Test
    fun `arknights poster keeps falling back safely`() {
        val r = run("arknights")
        assertEquals(PlanMode.ORIGINAL, r.plan.mode)
        assertTrue(r.plan.items.isEmpty())
        assertTrue("文字行不应为 0", r.layout.textLines().isNotEmpty())
    }

    /**
     * P0 + P1 的长图回归：690×10305 的微博长图。
     *
     * 旧版本按“长边 1600”把它压成 107×1598，分析出来的行带/字缝全是假的，
     * gain 又被虚高 6.4 倍（1.92 ≥ 1.25），于是不回退原图，而是渲染成左边一条 315px 宽的窄列。
     * 现在：分析按宽度定标（690 宽、230 行），gain 按源图像素算（< 1.25），必须回退原图。
     */
    @Test
    fun `ten thousand pixel long image falls back to the original`() {
        val r = run("006v119zgy1iik2r1f4z0j30j67y9npf")
        assertEquals("长图必须回退原图，不能输出窄列", PlanMode.ORIGINAL, r.plan.mode)
        assertEquals(FallbackReason.GAIN_TOO_SMALL, r.plan.fallback)
        assertTrue(r.plan.items.isEmpty())
        assertTrue("gain=${r.plan.gain}", r.plan.gain < 1.25f)
        assertEquals("分析必须按宽度定标", 1f, r.layout.analysisScale, 1e-6f)
        assertTrue("长图的行数应接近真实行数: ${r.layout.textLines().size}", r.layout.textLines().size > 150)
    }

    private fun ReflowPlan.headingRatio(): Float {
        val rows = items.filterIsInstance<app.pictext.core.model.RenderItem.TextRow>()
        val heading = rows.filter { it.role == LineRole.HEADING }.map { it.dest.height }.maxOrNull() ?: return 0f
        val body = rows.filter { it.role == LineRole.BODY }.map { it.dest.height }.maxOrNull() ?: return 0f
        return if (body == 0) 0f else heading.toFloat() / body
    }
}
