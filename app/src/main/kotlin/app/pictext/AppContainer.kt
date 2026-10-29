package app.pictext

import android.content.Context
import app.pictext.data.ProgressStore
import app.pictext.data.SettingsRepository
import app.pictext.share.SharedImageIntake

/** 手写依赖容器：只有三个单例，不引 Hilt。 */
class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val progress = ProgressStore(context)
    val intake = SharedImageIntake(context)
}
