package app.pictext.core.layout

import app.pictext.core.model.LineRole
import app.pictext.core.model.RegionKind

/**
 * 模型标签 → 产品角色。
 *
 * 标签顺序取自官方模型仓库 `config.json` 的 `label_list`（PP-DocLayout-S，23 类，Apache-2.0），
 * **不要按记忆或文档表格重排**：id 与标签错位会让所有框系统性错类。
 *
 * | id | 标签 | 中文 | RegionKind | 行角色先验 |
 * |---|---|---|---|---|
 * | 0 | paragraph_title | 段落标题 | TITLE | HEADING |
 * | 1 | image | 图片 | FIGURE | — |
 * | 2 | text | 正文 | TEXT | BODY |
 * | 3 | number | 页码 | CHROME | UI |
 * | 4 | abstract | 摘要 | TEXT | BODY |
 * | 5 | content | 目录 | TEXT | BODY |
 * | 6 | figure_title | 图题 | CAPTION | CAPTION |
 * | 7 | formula | 公式 | FIGURE | — |
 * | 8 | table | 表格 | TABLE | — |
 * | 9 | table_title | 表题 | CAPTION | CAPTION |
 * | 10 | reference | 参考文献 | TEXT | BODY |
 * | 11 | doc_title | 文档标题 | TITLE | HEADING |
 * | 12 | footnote | 脚注 | CAPTION | CAPTION |
 * | 13 | header | 页眉 | CHROME | UI |
 * | 14 | algorithm | 算法 | TEXT | BODY |
 * | 15 | footer | 页脚 | CHROME | UI |
 * | 16 | seal | 印章 | FIGURE | — |
 * | 17 | chart_title | 图表标题 | CAPTION | CAPTION |
 * | 18 | chart | 图表 | FIGURE | — |
 * | 19 | formula_number | 公式编号 | FIGURE | — |
 * | 20 | header_image | 页眉图 | CHROME | UI |
 * | 21 | footer_image | 页脚图 | CHROME | UI |
 * | 22 | aside_text | 旁注 | CAPTION | CAPTION |
 *
 * 未知 id / 未知标签一律 [RegionKind.OTHER]（整块保留、不切分），并计入 `unknownLabels`。
 */
class ModelLabels(private val labels: List<String> = LABELS) {

    private val kindById: Array<RegionKind> = Array(labels.size) { kindOf(labels[it]) }

    private val roleById: Array<LineRole?> = Array(labels.size) { roleOf(kindById[it]) }

    fun labelOf(id: Int): String = labels.getOrNull(id) ?: "unknown_$id"

    fun kindOfId(id: Int): RegionKind = kindById.getOrNull(id) ?: RegionKind.OTHER

    fun roleOfId(id: Int): LineRole? = roleById.getOrNull(id)

    /** 该 id 是否在标签表内（未知 id 不崩，但要能被统计出来）。 */
    fun isKnown(id: Int): Boolean = id in labels.indices

    val size: Int get() = labels.size

    /** 是否有标签在映射表里查不到（M0 换模型时用来报警）。 */
    fun unmappedLabels(): List<String> = labels.filter { it !in KIND_BY_LABEL }

    companion object {
        /** 官方 `label_list`，顺序即类别 id。 */
        val LABELS: List<String> = listOf(
            "paragraph_title",
            "image",
            "text",
            "number",
            "abstract",
            "content",
            "figure_title",
            "formula",
            "table",
            "table_title",
            "reference",
            "doc_title",
            "footnote",
            "header",
            "algorithm",
            "footer",
            "seal",
            "chart_title",
            "chart",
            "formula_number",
            "header_image",
            "footer_image",
            "aside_text",
        )

        private val KIND_BY_LABEL: Map<String, RegionKind> = mapOf(
            "paragraph_title" to RegionKind.TITLE,
            "doc_title" to RegionKind.TITLE,
            "text" to RegionKind.TEXT,
            "abstract" to RegionKind.TEXT,
            // 官方 label_list 里的 content = 目录，按正文处理（同一倍率、可切分）。
            "content" to RegionKind.TEXT,
            "reference" to RegionKind.TEXT,
            "algorithm" to RegionKind.TEXT,
            "figure_title" to RegionKind.CAPTION,
            "table_title" to RegionKind.CAPTION,
            "chart_title" to RegionKind.CAPTION,
            "footnote" to RegionKind.CAPTION,
            "aside_text" to RegionKind.CAPTION,
            "image" to RegionKind.FIGURE,
            "chart" to RegionKind.FIGURE,
            "formula" to RegionKind.FIGURE,
            "formula_number" to RegionKind.FIGURE,
            "seal" to RegionKind.FIGURE,
            "table" to RegionKind.TABLE,
            "header" to RegionKind.CHROME,
            "footer" to RegionKind.CHROME,
            "number" to RegionKind.CHROME,
            "header_image" to RegionKind.CHROME,
            "footer_image" to RegionKind.CHROME,
        )

        fun kindOf(label: String): RegionKind = KIND_BY_LABEL[label] ?: RegionKind.OTHER

        /** 行角色先验；模型标签只做先验，`RowClassifier` 的结论在细化期仍可翻盘（CAPTION/CHROME 例外）。 */
        fun roleOf(kind: RegionKind): LineRole? = when (kind) {
            RegionKind.TEXT -> LineRole.BODY
            RegionKind.TITLE -> LineRole.HEADING
            RegionKind.CAPTION -> LineRole.CAPTION
            RegionKind.CHROME -> LineRole.UI
            RegionKind.FIGURE, RegionKind.TABLE, RegionKind.OTHER -> null
        }
    }
}
