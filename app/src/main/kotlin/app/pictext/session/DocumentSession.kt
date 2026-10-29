package app.pictext.session

import app.pictext.core.model.PageLayout
import app.pictext.core.model.ReflowPlan
import app.pictext.image.SourceImage
import app.pictext.image.SourceStripCache
import app.pictext.render.TileRenderer
import java.io.Closeable
import java.io.File

/**
 * 一页图片的会话：源图、分析结果、当前计划和条带缓存。
 * 改字号/切分方式只替换 [plan] 和 [renderer]，不重新分析。
 */
class DocumentSession(
    val key: String,
    val sourceFile: File,
    val sourceImage: SourceImage,
    var strips: SourceStripCache,
    var layout: PageLayout,
    var plan: ReflowPlan,
    var renderer: TileRenderer,
) : Closeable {
    var planVersion: Int = 0

    override fun close() {
        strips.clear()
        sourceImage.close()
    }
}
