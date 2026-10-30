package app.pictext.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** 切分方式。 */
enum class SplitMode { AUTO, FORCE_TWO, OFF }

/** 应用设置。 */
data class AppSettings(
    val targetSp: Float = 16f,
    val splitMode: SplitMode = SplitMode.AUTO,
    val rememberProgress: Boolean = true,
    /**
     * v2 版式识别（模型区域 + 阅读顺序）开关。
     *
     * v2.0 默认**关**（β，docs/07 §4.10 / 决策 D-V2-3）：模型在手机截图上的零样本召回还没有
     * 达到可以默认开的把握，先让愿意试的人打开；模型不可用时打开也只会静默走 v1。
     */
    val smartLayout: Boolean = false,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** DataStore Preferences 封装；修改后自动持久化。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val targetSp = floatPreferencesKey("target_sp")
        val splitMode = stringPreferencesKey("split_mode")
        val rememberProgress = booleanPreferencesKey("remember_progress")
        val smartLayout = booleanPreferencesKey("smart_layout")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppSettings(
                targetSp = prefs[Keys.targetSp] ?: 16f,
                splitMode = runCatching {
                    SplitMode.valueOf(prefs[Keys.splitMode] ?: SplitMode.AUTO.name)
                }.getOrDefault(SplitMode.AUTO),
                rememberProgress = prefs[Keys.rememberProgress] ?: true,
                smartLayout = prefs[Keys.smartLayout] ?: false,
            )
        }

    suspend fun setTargetSp(sp: Float) {
        context.settingsDataStore.edit { it[Keys.targetSp] = sp.coerceIn(12f, 24f) }
    }

    suspend fun setSplitMode(mode: SplitMode) {
        context.settingsDataStore.edit { it[Keys.splitMode] = mode.name }
    }

    suspend fun setRememberProgress(remember: Boolean) {
        context.settingsDataStore.edit { it[Keys.rememberProgress] = remember }
    }

    suspend fun setSmartLayout(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.smartLayout] = enabled }
    }
}
