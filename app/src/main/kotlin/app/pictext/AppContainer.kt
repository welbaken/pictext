package app.pictext

import android.content.Context
import android.util.Log
import app.pictext.data.ProgressStore
import app.pictext.data.SettingsRepository
import app.pictext.layout.ModelAssets
import app.pictext.layout.OrtLayoutDetector
import app.pictext.core.layout.LayoutDetector
import app.pictext.share.SharedImageIntake

/**
 * 手写依赖容器：只有几个单例，不引 Hilt。
 *
 * [layoutDetector] 是**懒加载**的：模型资产缺失、ORT 初始化失败、ABI 不支持，都只返回 `null`，
 * 上层自动走 v1（docs/07 §4.10、§4.9）——任何与模型有关的失败都不允许影响打开图片。
 */
class AppContainer(context: Context) {

    val settings = SettingsRepository(context)
    val progress = ProgressStore(context)
    val intake = SharedImageIntake(context)

    private val appContext = context.applicationContext

    @Volatile
    private var detector: LayoutDetector? = null

    @Volatile
    private var detectorTried = false

    /** v2 检测器；`null` = 不可用（此时所有分析走 v1，行为与旧版一致）。 */
    fun layoutDetector(): LayoutDetector? {
        if (detectorTried) return detector
        synchronized(this) {
            if (detectorTried) return detector
            detectorTried = true
            detector = runCatching {
                val model = ModelAssets.ensureModel(appContext)
                    ?: error("layout model asset missing")
                OrtLayoutDetector(model)
            }.onFailure { Log.w("PicText", "layout detector unavailable, falling back to v1", it) }
                .getOrNull()
        }
        return detector
    }

    /** 允许设置页显示“模型已就绪/不可用”。 */
    fun layoutModelReady(): Boolean = ModelAssets.isInstalled(appContext)

    fun closeLayout() {
        runCatching { detector?.close() }
        detector = null
        detectorTried = false
    }
}
