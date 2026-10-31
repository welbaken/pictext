package app.pictext.core

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.AnalysisScale
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.Block
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

/**
 * 真实图片回归（P0-0 / P1-2）。
 *
 * 读 `testdata/private/samples/`（或 `testdata/samples/`），不复制图片。断言分两层：
 *
 * 1. **目录级**：目录不存在时整类跳过（CI 没有真实截图也不该红）；目录存在时，里面**每一张**
 *    都必须满足硬约束。早期版本是"按名字找 4 个样板 + 缺文件就 `assumeTrue` 跳过"，样板改名后
 *    6/7 个用例都在静默跳过，这条安全网等于不存在——所以这里改成"目录在即必须全过"。
 * 2. **结构级**：src/dest 不越界、非 `ORIGINAL` 的计划必须通过 [PlanAuditor]、分析耗时受控。
 *    ×4 只在 ×2 真的重排过的页面上再跑一遍，断言段数/宽度/倍率单调。
 *
 * 目录里出现 [ImageIO] 读不了的格式（例如当前 JDK 没有 webp 插件）只打印并计入跳过，
 * 不算失败；真机/CLI 侧走的是 Android 的 `BitmapFactory`，格式支持面更宽。
 */
class RealImageRegressionTest {

    private data class Run(
        val name: String,
        val file: File,
        val layout: PageLayout,
        val plan: ReflowPlan,
        val auditOk: Boolean,
        val auditIssues: List<String>,
        val auditSummary: String,
    )

    private fun sampleDirOf(name: String): File? {
        val root = System.getProperty("pictext.root") ?: return null
        val privateDir = File(root, "testdata/private/$name")
        if (privateDir.isDirectory) return privateDir
        val publicDir = File(root, "testdata/$name")
        return if (publicDir.isDirectory) publicDir else null
    }

    /** 目录缺失 → `AssumptionViolatedException`（整类跳过）；目录存在 → 返回它。 */
    private fun requireDir(name: String): File {
        val dir = sampleDirOf(name)
        assumeTrue(
            "真实样例目录不存在（$name：放到 testdata/private/$name，或用 -Dpictext.root=仓库根目录）",
            dir != null,
        )
        return dir!!
    }

    private fun imagesIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun runFile(
        name: String,
        file: File,
        analysis: AnalysisOptions = AnalysisOptions(),
        options: ReflowOptions = ReflowOptions(outputWidth = 1080, targetEm = 44),
    ): Run {
        val image: BufferedImage = requireNotNull(ImageIO.read(file)) {
            "ImageIO 读不了 $file（当前 JDK 的图片格式支持有限）"
        }
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

        // ---- 任何样例都必须满足的硬约束（与算法判断无关） ----
        assertTrue("分析耗时异常: ${analyzeMs}ms", analyzeMs < 1500)
        for (item in plan.items) {
            assertTrue("src 越界: $item", item.src.left >= 0 && item.src.top >= 0)
            assertTrue("src 越界: $item", item.src.right <= w && item.src.bottom <= h)
            assertTrue("src 为空: $item", !item.src.isEmpty)
            assertTrue("dest 越界: $item", item.dest.left >= 0 && item.dest.right <= plan.outputWidth)
            assertTrue("dest 为空: $item", !item.dest.isEmpty)
        }
        if (plan.mode != PlanMode.ORIGINAL) {
            assertTrue("计划项为空但模式是 ${plan.mode}", plan.items.isNotEmpty())
        } else {
            assertTrue("$name 回退时不应有渲染项", plan.items.isEmpty())
        }
        // 审计结论交回调用方汇总：一次跑完整个语料再一起报，而不是死在第一张上。
        return Run(name, file, layout, plan, audit.ok, audit.issues, audit.summary())
    }

    /**
     * 目录里的每一张都要跑通并满足硬约束。
     *
     * 这个用例取代了早期按名字找 `twitter/news_jp/xerj/arknights` 的 4 个用例——那些文件
     * 早已改名/清理，用例却在 `assumeTrue` 上静默跳过，给人"回归在跑"的错觉。
     */
    @Test
    fun `every sample satisfies plan invariants`() {
        val dir = requireDir("samples")
        val files = imagesIn(dir)
        assumeTrue("$dir 里没有 jpg/png 样例", files.isNotEmpty())
        var ran = 0
        var skipped = 0
        var reflowed = 0
        val auditFailures = ArrayList<String>()
        for (file in files) {
            val readable = runCatching { ImageIO.read(file) }.getOrNull() != null
            if (!readable) {
                skipped++
                println("samples: 跳过 ${file.name}（ImageIO 不支持该格式）")
                continue
            }
            val r = runFile(file.nameWithoutExtension, file)
            ran++
            if (r.plan.mode != PlanMode.ORIGINAL) {
                reflowed++
                if (!r.auditOk) auditFailures += "${file.name}: ${r.auditIssues}"
            }
        }
        println("samples: 重排 $reflowed / $ran（跳过 $skipped），审计失败 ${auditFailures.size}")
        assertTrue("一张都没跑成：$dir", ran > 0)
        // 真实截图目录里一张都不重排，说明算法整体失效（或语料被换成了纯图片）。
        assertTrue("样例目录里没有任何一张重排：$dir", reflowed > 0)
        assertTrue(
            "以下样例的重排未通过结构审计（块丢失 / 倍率不一致 / 标题偏小）：\n" +
                auditFailures.joinToString("\n"),
            auditFailures.isEmpty(),
        )
    }

    /**
     * ×4（二次重排）：只在 ×2 真的重排过的页面上跑。
     *
     * 断言的是**与样例无关的性质**：segment 数不超过 4、每段不溢出、`plan.scale` 不减小；
     * 另打印"切出 3 段以上的页面数"，用来发现二次重排整体失效。
     */
    @Test
    fun `x4 never overflows and yields more rows than x2`() {
        val dir = requireDir("samples")
        val files = imagesIn(dir)
        assumeTrue("$dir 里没有 jpg/png 样例", files.isNotEmpty())
        val baseOptions = ReflowOptions(outputWidth = 1080, targetEm = 44)
        val quadOptions = ReflowOptions(
            outputWidth = 1080,
            targetEm = 44,
            forceReflow = true,
            scaleMultiplier = 2f,
            maxSegments = 4,
        )
        var pages = 0
        var multiSegmentPages = 0
        for (file in files) {
            val readable = runCatching { ImageIO.read(file) }.getOrNull() != null
            if (!readable) continue
            val base = runFile("${file.nameWithoutExtension}-x2", file, options = baseOptions)
            if (base.plan.mode == PlanMode.ORIGINAL) continue
            val quad = runFile("${file.nameWithoutExtension}-x4", file, options = quadOptions)
            pages++
            assertTrue(
                "${file.name}: ×4 应当重排，实际 ${quad.plan.mode}/${quad.plan.fallback}",
                quad.plan.mode == PlanMode.REFLOW,
            )
            assertTrue(
                "${file.name}: ×4 倍率不能小于 ×2（${base.plan.scale} vs ${quad.plan.scale}）",
                quad.plan.scale >= base.plan.scale - 1e-3f,
            )
            val rows = quad.plan.items.filterIsInstance<RenderItem.TextRow>()
            assertTrue(
                "${file.name}: ×4 行数不应少于 ×2（${rows.size} vs ${base.plan.items.size}）",
                rows.size >= base.plan.items.size,
            )
            assertTrue("${file.name}: 段宽溢出", rows.all { it.dest.width <= quad.plan.outputWidth })
            assertTrue(
                "${file.name}: 同一行不能切成超过 4 段",
                rows.groupBy { it.src.top }.values.all { it.size <= 4 },
            )
            if (rows.groupBy { it.src.top }.values.any { it.size >= 3 }) multiSegmentPages++
        }
        println("x4: $pages 页重排，其中 $multiSegmentPages 页切出 3 段以上")
        assertTrue("没有一页能跑 ×4", pages > 0)
        assertTrue("整个语料都没有一行切到 3 段以上，二次重排可能失效", multiSegmentPages > 0)
    }

    /**
     * 690×10305 的长图回归：分析按宽度定标（k=1）、gain 按源图像素算 → 必须回退原图，
     * 不能输出"左边一条窄列"。这是 0.5.1 修的那条，样例文件缺失时直接失败（不再静默跳过）。
     */
    @Test
    fun `ten thousand pixel long image falls back to the original`() {
        val dir = requireDir("samples")
        val file = File(dir, "006v119zgy1iik2r1f4z0j30j67y9npf.jpg")
        assertTrue("缺少长图样例 $file（目录存在时不允许静默跳过）", file.isFile)
        val r = runFile("long-image", file)
        assertEquals("长图必须回退原图，不能输出窄列", PlanMode.ORIGINAL, r.plan.mode)
        assertTrue("gain=${r.plan.gain}", r.plan.gain < 1.25f)
        assertTrue(r.plan.items.isEmpty())
        assertEquals("分析必须按宽度定标", 1f, r.layout.analysisScale, 1e-6f)
        assertTrue("长图的行数应接近真实行数: ${r.layout.textLines().size}", r.layout.textLines().size > 150)
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
