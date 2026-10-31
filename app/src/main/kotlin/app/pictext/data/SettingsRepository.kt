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
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** DataStore Preferences 封装；修改后自动持久化。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val targetSp = floatPreferencesKey("target_sp")
        val splitMode = stringPreferencesKey("split_mode")
        val rememberProgress = booleanPreferencesKey("remember_progress")
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
}
