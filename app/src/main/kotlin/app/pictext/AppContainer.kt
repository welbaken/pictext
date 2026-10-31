package app.pictext

import android.content.Context
import app.pictext.data.ProgressStore
import app.pictext.data.SettingsRepository
import app.pictext.share.SharedImageIntake

/**
 * 手写依赖容器：只有几个单例，不引 Hilt。
 *
 * 0.5 只发 v1，容器里不再持有 ORT/模型；v2 的代码留在 `:core` / `:cli` 做技术验证。
 */
class AppContainer(context: Context) {

    val settings = SettingsRepository(context)
    val progress = ProgressStore(context)
    val intake = SharedImageIntake(context)
}
