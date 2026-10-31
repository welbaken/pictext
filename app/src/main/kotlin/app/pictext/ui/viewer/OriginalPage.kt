package app.pictext.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.pictext.image.SourceImage
import app.pictext.image.ViewportFrame
import app.pictext.render.BitmapRecycler
import app.pictext.image.ViewportImageLoader
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 原图模式：按视口解码，1×–5× 缩放、双击 1×/2.5×、拖动。
 *
 * 上下留出与阅读页一致的空带（状态栏/导航栏 + 56dp 顶栏/底栏），所以顶栏出现时不会压住图片；
 * 顶栏本身可以像重排模式一样点正文收起（见 [ViewerScreen]）。
 *
 * 手势进行中不重新解码；停止 120ms 后只解码当前视口覆盖的源图区域，
 * 所以 1080×20000 的图也不会被整张放进内存。
 */
@Composable
fun OriginalPage(
    source: SourceImage,
    backgroundColor: Int,
    initialProgress: Float,
    onProgress: (Float) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var loaded by remember { mutableStateOf<ViewportFrame?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var latestRatio by remember { mutableFloatStateOf(initialProgress.coerceIn(0f, 1f)) }
    val loader = remember(source) { ViewportImageLoader(source) }
    val density = LocalDensity.current
    // 与重排页的 contentPadding 保持一致：图片不钻到顶栏/底栏下面。
    val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 56.dp
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 56.dp

    // 视口帧换掉时立即回收上一张，避免反复缩放/拖动时把几十张整屏位图留给 GC。
    // 注意：必须在组合期把值取成局部变量，否则 onDispose 里读到的是已经替换后的新帧。
    // 回收排到下一帧（BitmapRecycler）：display list 可能还持有这一帧的位图。
    DisposableEffect(loaded) {
        val held = loaded
        onDispose { BitmapRecycler.recycle(held?.bitmap) }
    }

    val baseScale = if (viewport.width > 0) viewport.width.toFloat() / source.width else 1f

    fun clampOffset(value: Offset, z: Float): Offset {
        if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
        val scale = baseScale * z
        val maxX = max(0f, source.width * scale - viewport.width)
        val maxY = max(0f, source.height * scale - viewport.height)
        return Offset(value.x.coerceIn(0f, maxX), value.y.coerceIn(0f, maxY))
    }

    LaunchedEffect(viewport) {
        if (viewport.width > 0 && viewport.height > 0) {
            if (!initialized) {
                initialized = true
                val scale = baseScale
                val maxY = max(0f, source.height * scale - viewport.height)
                offset = Offset(0f, initialProgress.coerceIn(0f, 1f) * maxY)
            } else {
                offset = clampOffset(offset, zoom)
            }
        }
    }

    LaunchedEffect(zoom, offset, viewport) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        delay(120)
        // 解码失败（含 OOM/文件被回收）只显示转圈，不让异常冒到 Compose。
        loaded = runCatching { loader.load(offset, baseScale * zoom, viewport) }.getOrNull()
    }

    LaunchedEffect(zoom, offset, viewport) {
        if (viewport.width <= 0) return@LaunchedEffect
        val scale = baseScale * zoom
        val maxY = max(0f, source.height * scale - viewport.height)
        latestRatio = if (maxY <= 0f) 0f else (offset.y / maxY).coerceIn(0f, 1f)
        onProgress(latestRatio)
    }

    DisposableEffect(Unit) {
        onDispose { onProgress(latestRatio) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(backgroundColor))
            .padding(top = topInset, bottom = bottomInset)
            .onSizeChanged { viewport = it }
            .pointerInput(viewport) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    val oldScale = baseScale * zoom
                    val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                    val newScale = baseScale * newZoom
                    val factor = if (oldScale > 0f) newScale / oldScale else 1f
                    // offset 是“视口左上角在缩放后图片上的位置”，画面画在 `-offset` 处：
                    // 手指往右/下拖，画面要跟着往右/下走，所以视口要减掉 pan；加 pan 会变成
                    // “手指往右、图片往左”的镜像拖动。缩放部分仍以两指中点为锚点。
                    val next = Offset(
                        (offset.x + centroid.x) * factor - centroid.x - pan.x,
                        (offset.y + centroid.y) * factor - centroid.y - pan.y,
                    )
                    zoom = newZoom
                    offset = clampOffset(next, newZoom)
                }
            }
            .pointerInput(viewport, zoom) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { position ->
                        val oldScale = baseScale * zoom
                        val newZoom = if (zoom > 1.2f) 1f else 2.5f
                        val newScale = baseScale * newZoom
                        val factor = if (oldScale > 0f) newScale / oldScale else 1f
                        val next = Offset(
                            (offset.x + position.x) * factor - position.x,
                            (offset.y + position.y) * factor - position.y,
                        )
                        zoom = newZoom
                        offset = clampOffset(next, newZoom)
                    },
                )
            },
    ) {
        val frame = loaded
        val imageBitmap: ImageBitmap? = remember(frame) { frame?.bitmap?.asImageBitmap() }
        if (frame != null && imageBitmap != null) {
            Canvas(Modifier.fillMaxSize()) {
                val scale = baseScale * zoom
                val left = (frame.src.left * scale - offset.x).roundToInt()
                val top = (frame.src.top * scale - offset.y).roundToInt()
                val w = max(1, (frame.src.width * scale).roundToInt())
                val h = max(1, (frame.src.height * scale).roundToInt())
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(w, h),
                    filterQuality = FilterQuality.Medium,
                )
            }
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}
