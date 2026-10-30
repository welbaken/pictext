package app.pictext.layout

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 模型资产的安装：`assets/doclayout/PP-DocLayout-S.onnx` → `filesDir/models/`。
 *
 * 三个必须处理的点：
 * 1. **不要在读取路径用 assets**：ORT 需要真实文件路径，且 asset 是压缩流；
 * 2. **版本戳**：模型升级时重拷，靠 `version.txt` 比对，而不是每次启动都拷 4.9MB；
 * 3. **失败只降级**：任何 IO 异常都返回 `null`，调用方自动走 v1，绝不因为模型文件把 App 弄崩。
 */
object ModelAssets {

    /** 换模型/换导出就要改这个字符串，安装时会重拷。 */
    const val MODEL_VERSION = "pp-doclayout-s-onnx-opset13-v1"

    private const val ASSET_PATH = "doclayout/PP-DocLayout-S.onnx"
    private const val FILE_NAME = "PP-DocLayout-S.onnx"

    @Volatile
    private var cached: File? = null

    /**
     * @return 可用的模型文件；资产缺失/拷贝失败返回 `null`（调用方回退 v1）。
     */
    fun ensureModel(context: Context): File? {
        cached?.let { if (it.isFile) return it }
        val dir = File(context.filesDir, "models")
        val out = File(dir, FILE_NAME)
        val stamp = File(dir, "version.txt")
        if (out.isFile && out.length() > 0 && stamp.isFile && stamp.readText().trim() == MODEL_VERSION) {
            cached = out
            return out
        }
        return runCatching {
            dir.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                // 先写临时文件再原子改名：中途被杀不会留下半截模型。
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
            }
            stamp.writeText(MODEL_VERSION)
            Log.d("PicText", "layout model installed: ${out.length()} bytes")
            out
        }.onFailure { Log.w("PicText", "layout model install failed", it) }.getOrNull()
            ?.also { cached = it }
    }

    /** 是否已经装好（不触发拷贝），用于设置页展示。 */
    fun isInstalled(context: Context): Boolean {
        val dir = File(context.filesDir, "models")
        val out = File(dir, FILE_NAME)
        val stamp = File(dir, "version.txt")
        return out.isFile && out.length() > 0 && stamp.isFile && stamp.readText().trim() == MODEL_VERSION
    }
}
