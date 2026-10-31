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
     * 强化重排：打开后才用 v2（模型区域 + 阅读顺序）；默认 false，所有图片走 v1。
     *
     * V2-51 起版本由用户显式选择，不再自动判断、也不再把 v2 结果偷偷回退成 v1。
     * 新键名 `enhanced_reflow`：老 β 开关（`smart_layout`）不再读取，升级后统一回到 v1。
     */
    val enhancedReflow: Boolean = false,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** DataStore Preferences 封装；修改后自动持久化。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val targetSp = floatPreferencesKey("target_sp")
        val splitMode = stringPreferencesKey("split_mode")
        val rememberProgress = booleanPreferencesKey("remember_progress")
        val enhancedReflow = booleanPreferencesKey("enhanced_reflow")
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
                enhancedReflow = prefs[Keys.enhancedReflow] ?: false,
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

    suspend fun setEnhancedReflow(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.enhancedReflow] = enabled }
    }
}
