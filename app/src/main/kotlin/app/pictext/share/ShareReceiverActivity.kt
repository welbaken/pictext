package app.pictext.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.pictext.R
import app.pictext.ui.theme.PicTextTheme
import app.pictext.ui.viewer.ViewerScreen
import app.pictext.ui.viewer.ViewerViewModel

/**
 * 分享入口，同时也是阅读页。少一次 Activity 转场，返回键直接回到来源 App。
 * `singleTask` 下再次分享会走 [onNewIntent]，必须重新 intake。
 */
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PicTextTheme {
                ViewerScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
        // 旋转/分屏重建时 ViewModel 仍然存活，不能重新 intake；进程死亡后 hasOpened 为 false。
        if (!viewModel.hasOpened) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_image, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (uris.size > MAX_IMAGES) {
            Toast.makeText(this, R.string.toast_max_images, Toast.LENGTH_SHORT).show()
        }
        viewModel.open(uris.take(MAX_IMAGES))
    }

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val single = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (single != null) out.add(single)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val many = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                many?.forEach { out.add(it) }
            }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { out.add(it) }
            }
        }
        return out.filter { it.scheme != null }.toList()
    }

    companion object {
        const val MAX_IMAGES = 5
    }
}
