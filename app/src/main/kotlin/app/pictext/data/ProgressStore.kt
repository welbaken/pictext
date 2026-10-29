package app.pictext.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 阅读位置持久化（0..1 比例）。用一个小 JSON 文件，避免为几十条记录引入数据库。
 * 键是源图内容的 SHA-256，重新分享同一张图也能回到上次的位置。
 */
class ProgressStore(context: Context) {

    private val file = File(context.filesDir, "progress.json")
    private val values = HashMap<String, Float>()
    private var loaded = false

    @Synchronized
    fun get(key: String): Float {
        ensureLoaded()
        return values[key]?.coerceIn(0f, 1f) ?: 0f
    }

    @Synchronized
    fun put(key: String, ratio: Float) {
        ensureLoaded()
        values[key] = ratio.coerceIn(0f, 1f)
        // 最多保留 100 条，避免文件无限增长
        if (values.size > 100) {
            val first = values.keys.firstOrNull() ?: return
            values.remove(first)
        }
        save()
    }

    @Synchronized
    fun remove(key: String) {
        ensureLoaded()
        if (values.remove(key) != null) save()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            val obj = JSONObject(file.readText())
            for (key in obj.keys()) values[key] = obj.getDouble(key).toFloat()
        }
    }

    private fun save() {
        runCatching {
            val obj = JSONObject()
            for ((k, v) in values) obj.put(k, v.toDouble())
            val tmp = File(file.parentFile, "progress.json.tmp")
            tmp.writeText(obj.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
