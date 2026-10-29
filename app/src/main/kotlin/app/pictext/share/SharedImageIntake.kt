package app.pictext.share

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 分享进来的源图副本与内容指纹。 */
data class IntakeResult(val file: File, val key: String)

/**
 * 把 `content://` URI 立即拷贝到 `cacheDir/inbox/`。来源 App 授予的临时读权限
 * 只在本次 Activity 生命周期内有效，不能等到真正解码时再去读。
 */
class SharedImageIntake(private val context: Context) {

    private val inbox: File
        get() = File(context.cacheDir, "inbox").apply { mkdirs() }

    suspend fun copy(uri: Uri): IntakeResult = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: "image/*"
        val ext = extensionFor(mime)
        val target = File(inbox, "${UUID.randomUUID()}$ext")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            } ?: throw IllegalArgumentException("cannot open $uri")
            if (target.length() <= 0L) throw IllegalArgumentException("empty file")
            val key = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            IntakeResult(target, key)
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /** 清理 7 天前的 inbox 文件。 */
    suspend fun cleanupOldFiles() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        inbox.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }

    private fun extensionFor(mime: String): String = when {
        mime.contains("jpeg", true) || mime.contains("jpg", true) -> ".jpg"
        mime.contains("png", true) -> ".png"
        mime.contains("webp", true) -> ".webp"
        mime.contains("gif", true) -> ".gif"
        mime.contains("heic", true) || mime.contains("heif", true) -> ".heic"
        else -> ".img"
    }
}
