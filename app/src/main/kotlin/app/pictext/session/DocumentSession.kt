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

    /** 生成当前 [plan] 时用的重排倍率（档位基准 × 双指缩放）；重排页用它算缩放的余量。 */
    var multiplier: Float = 1f

    override fun close() {
        strips.clear()
        sourceImage.close()
    }
}
