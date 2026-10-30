package app.pictext.core.layout

import app.pictext.core.model.LayoutRegion
import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import app.pictext.core.model.TextLine
import java.io.File
import kotlin.math.roundToInt

/**
 * 一个极简 JSON 解析器：**只用于读 DOM 真值**（`ground-truth` 目录下的 `id@width.json`）。
 *
 * 为什么不引依赖：`:core` 的测试依赖只有 JUnit，而真值文件是固定结构，
 * 为了 200 行 JSON 去引一个库不划算。支持的子集：对象/数组/字符串/数字/true/false/null。
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) error("unexpected end of json")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }

        private fun expect(word: String) {
            require(s.startsWith(word, i)) { "expected $word at $i" }
            i += word.length
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(s[i] == ':') { "expected : at $i" }
                i++
                out[k] = value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> error("expected , or } at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> error("expected , or ] at $i")
                }
            }
        }

        private fun str(): String {
            skipWs()
            require(s[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                val c = s[i]
                if (c == '\\') {
                    i++
                    when (val e = s[i]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> error("bad escape \\$e")
                    }
                } else {
                    sb.append(c)
                }
                i++
            }
            i++
            return sb.toString()
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

    fun num(v: Any?, fallback: Double = 0.0): Double = (v as? Double) ?: fallback

    fun str(v: Any?): String = v as? String ?: ""

    fun bool(v: Any?, fallback: Boolean = false): Boolean = v as? Boolean ?: fallback
}

/** 真值里的一个矩形（`x, y, w, h`）。 */
data class GtBox(val x: Double, val y: Double, val w: Double, val h: Double) {
    val rect: RectI get() = RectI(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt())
}

/** 真值里的一个语义块。 */
data class GtRegion(
    val id: String,
    val kind: String,
    val order: Int,
    val box: GtBox,
    val inkBox: GtBox?,
    val lines: List<GtBox>,
) {
    /** 真值 → 产品类别；与 `ModelLabels` 的映射口径保持一致。 */
    fun regionKind(): RegionKind = when (kind.lowercase()) {
        "text" -> RegionKind.TEXT
        "title" -> RegionKind.TITLE
        "caption" -> RegionKind.CAPTION
        "figure" -> RegionKind.FIGURE
        "table" -> RegionKind.TABLE
        "ui" -> RegionKind.CHROME
        else -> RegionKind.OTHER
    }

    fun isTextual(): Boolean = regionKind() in setOf(
        RegionKind.TEXT, RegionKind.TITLE, RegionKind.CAPTION,
    )
}

/**
 * 一个夹具的 DOM 真值。
 *
 * @param orderStrict 顺序是否有唯一正确答案（仪表盘/海报为 false，只做弱断言）。
 */
data class FixtureGt(
    val id: String,
    val width: Int,
    val height: Int,
    val orderStrict: Boolean,
    val regions: List<GtRegion>,
) {
    /** 文字行的行高中位数（S 像素），ReadingOrder 的断口阈值要用。 */
    fun medianLineHeight(): Float {
        val hs = regions.filter { it.isTextual() }.flatMap { it.lines }.map { it.h }.sorted()
        if (hs.isEmpty()) return 16f
        return hs[hs.size / 2].toFloat()
    }

    /**
     * 转成 `ReadingOrder` 的输入：**故意按 y 降序/乱序给**，逼算法自己排。
     * 顺序不能直接给真值顺序，否则测的是“数组有没有被打乱”。
     */
    fun regionsForOrdering(): List<LayoutRegion> = regions
        .sortedWith(compareByDescending<GtRegion> { it.box.y + it.box.h }.thenByDescending { it.box.x })
        .map { g ->
            LayoutRegion(
                id = g.id.hashCode(),
                kind = g.regionKind(),
                bounds = g.box.rect,
                label = g.id,
            )
        }

    /** 转成页面组装/规划的输入：类别、行、顺序都来自真值。 */
    fun regionsForLayout(): List<LayoutRegion> = regions.map { g ->
        val lines = if (g.isTextual()) {
            g.lines.mapIndexed { i, l ->
                TextLine(
                    band = l.rect,
                    role = when (g.regionKind()) {
                        RegionKind.TITLE -> LineRole.HEADING
                        RegionKind.CAPTION -> LineRole.CAPTION
                        RegionKind.CHROME -> LineRole.UI
                        else -> LineRole.BODY
                    },
                    cutFeatures = SyntheticCutFeatures.of(l.rect),
                )
            }
        } else {
            emptyList()
        }
        LayoutRegion(
            id = g.id.hashCode(),
            kind = g.regionKind(),
            bounds = g.box.rect,
            lines = lines,
            order = g.order,
            label = g.id,
        )
    }
}

/**
 * 给真值行合成的切点特征：**每 1/5 行宽有一刀硬缝**。
 *
 * 真值只有几何、没有像素，而 `CutPolicy` 需要硬缝/软缝/剖面才能切。这里给每行造一组
 * “等距可切”的理想缝，用来测**组装与规划**这条链路（V2-24 / V2-30）——
 * 一行最多能被切成 5 段，足够覆盖 1080 宽页面的需求。
 * 真实像素上的切点质量由 `FixtureCutPolicyTest` 走 `RegionRefiner` 单独测。
 */
object SyntheticCutFeatures {
    fun of(box: RectI): app.pictext.core.model.LineCutFeatures {
        val w = box.width.coerceAtLeast(1)
        // 硬缝必须是**绝对 S 列**（与 `LineCutFeatures.hardGaps` 的约定一致）。
        // 宽度给 8 列：`CutPolicy` 的门槛是 `max(2, 0.10×行高)`，标题行高 59 时门槛到 6，
        // 太窄的缝会被判成“不够宽”，测试就会退化成“全靠强制切”。
        val gaps = (1..9).map { k ->
            val at = box.left + w * k / 10
            (at - 4) until (at + 4)
        }
        val counts = IntArray(w) { x ->
            val abs = box.left + x
            if (gaps.any { abs >= it.first && abs <= it.last }) 0 else 2
        }
        return app.pictext.core.model.LineCutFeatures(
            hardGaps = gaps,
            softGaps = emptyList(),
            minInkColumn = box.left + w / 2,
            cjkDominant = true,
            density = 0.4f,
            maxRun = minOf(box.height, 12),
            profile = app.pictext.core.model.InkProfile.of(box.left, counts),
        )
    }
}

/** 夹具真值的加载器：从 `testdata/layout/ground-truth/<id>@<width>.json` 读。 */
object Fixtures {

    fun root(): File {
        val r = System.getProperty("pictext.root")
            ?: error("missing -Dpictext.root（core/build.gradle.kts 会设成仓库根目录）")
        return File(r)
    }

    fun gtDir(): File = File(root(), "testdata/layout/ground-truth")

    /** 所有已入库的真值文件（id@width）。 */
    fun all(): List<Pair<String, Int>> {
        val dir = gtDir()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f ->
                val name = f.nameWithoutExtension
                val id = name.substringBefore('@')
                val w = name.substringAfter('@', "1080").toIntOrNull() ?: return@mapNotNull null
                id to w
            }
    }

    fun load(id: String, width: Int): FixtureGt? {
        val file = File(gtDir(), "$id@$width.json")
        if (!file.isFile) return null
        val root = MiniJson.obj(MiniJson.parse(file.readText()))
        val doc = MiniJson.obj(root["doc"])
        val regions = MiniJson.arr(root["regions"]).map { r ->
            val o = MiniJson.obj(r)
            GtRegion(
                id = MiniJson.str(o["id"]),
                kind = MiniJson.str(o["kind"]),
                order = MiniJson.num(o["order"]).toInt(),
                box = box(o["box"]),
                inkBox = (o["inkBox"] as? Map<*, *>)?.let { boxOf(it) },
                lines = MiniJson.arr(o["lines"]).map { boxOf(MiniJson.obj(it)) },
            )
        }
        return FixtureGt(
            id = id,
            width = MiniJson.num(root["width"]).toInt(),
            height = MiniJson.num(root["height"]).toInt().takeIf { it > 0 }
                ?: MiniJson.num(doc["h"]).toInt(),
            orderStrict = MiniJson.bool(root["orderStrict"], true),
            regions = regions,
        )
    }

    fun screen(id: String, width: Int): File = File(root(), "testdata/layout/screens/$id@$width.png")

    private fun box(v: Any?): GtBox = boxOf(MiniJson.obj(v))

    private fun boxOf(o: Map<*, *>): GtBox = GtBox(
        x = (o["x"] as? Double) ?: 0.0,
        y = (o["y"] as? Double) ?: 0.0,
        w = (o["w"] as? Double) ?: 0.0,
        h = (o["h"] as? Double) ?: 0.0,
    )
}
