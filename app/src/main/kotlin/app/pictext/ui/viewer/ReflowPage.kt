package app.pictext.ui.viewer

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.pictext.R
import app.pictext.core.model.RenderItem
import app.pictext.render.BitmapRecycler
import app.pictext.session.DocumentSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 重排正文：1024px 瓦片 + LazyColumn 虚拟化。每张瓦片滚出屏幕即取消渲染并回收位图，
 * 峰值内存与文档总高度无关。
 *
 * 双指缩放是**纯视觉放大**（v1.6）：整页按 1×–3× 缩放，不重新规划、不重新切分。
 * 重排倍率只由底栏的 ×2 / ×4 决定（见 [ViewerViewModel.setLevel]）——捏合时每帧重新规划
 * 既卡，又会把版面字号和切点跳来跳去。
 */
@Composable
fun ReflowPage(
    session: DocumentSession,
    page: PageState,
    onProgress: (Float) -> Unit,
    onBarsVisible: (Boolean) -> Unit,
    onFigureClick: (RenderItem.Figure) -> Unit,
    onEmptyTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = page.plan ?: return
    if (plan.tileCount <= 0) return
    val density = LocalDensity.current
    val background = Color(plan.backgroundColor)
    val initialIndex = (page.progress.coerceIn(0f, 1f) * plan.tileCount)
        .toInt()
        .coerceIn(0, plan.tileCount - 1)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = 0,
    )
    var lastIndex by remember { mutableIntStateOf(listState.firstVisibleItemIndex) }
    var latestRatio by remember { mutableFloatStateOf(page.progress) }

    // 双指缩放 = 纯视觉放大（1×–3×），不触发重新规划：重排倍率只由底栏的 ×2/×4 决定。
    var zoom by remember(page.key) { mutableFloatStateOf(1f) }
    var panX by remember(page.key) { mutableFloatStateOf(0f) }
    var viewportW by remember { mutableIntStateOf(0) }
    fun maxPanX(z: Float): Float = (viewportW * (z - 1f)).coerceAtLeast(0f)

    LaunchedEffect(listState, plan.height) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collect { (scrolling, index, offset) ->
                val ratio = if (plan.height <= 0) {
                    0f
                } else {
                    // offset 是“放大后”的像素，换回内容像素要除以 zoom。
                    ((index * plan.tileHeightPx + offset / zoom).toFloat() / plan.height).coerceIn(0f, 1f)
                }
                latestRatio = ratio
                if (scrolling) {
                    if (index > lastIndex) onBarsVisible(false)
                    else if (index < lastIndex) onBarsVisible(true)
                } else {
                    onProgress(ratio)
                }
                lastIndex = index
            }
    }

    DisposableEffect(page.key) {
        onDispose { onProgress(latestRatio) }
    }

    // 换档位会换一份计划，总高度也跟着变。这里按“阅读比例”重新定位，
    // 否则 LazyColumn 会保留旧的 item 下标，用户会觉得换档后内容跳了一段。
    LaunchedEffect(page.version, plan.height) {
        // 新计划从 1× 开始，否则上一档的视觉放大会叠在新版面上。
        zoom = 1f
        panX = 0f
        if (plan.height <= 0 || plan.tileHeightPx <= 0) return@LaunchedEffect
        val target = (page.progress.coerceIn(0f, 1f) * plan.height).roundToInt()
        val index = (target / plan.tileHeightPx).coerceIn(0, (plan.tileCount - 1).coerceAtLeast(0))
        listState.scrollToItem(index, target % plan.tileHeightPx)
    }

    val cd = stringResource(R.string.cd_image_content)
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .onSizeChanged { viewportW = it.width },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .pointerInput(page.key) {
                    awaitEachGesture {
                        // Initial pass：先于 LazyColumn 的滚动与 Pager 的翻页拿到事件。
                        // 1× 时只消费“两指以上”，单指滚动/翻页照旧；
                        // 放大后单指也是平移（和原图模式一致），此时不再翻页。
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var dragging = false
                        var moved = 0f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                dragging = false
                                val zoomChange = event.calculateZoom()
                                if (zoomChange.isFinite() && zoomChange != 1f) {
                                    val next = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                                    if (abs(next - zoom) > 0.001f) {
                                        zoom = next
                                        panX = panX.coerceIn(0f, maxPanX(next))
                                    }
                                }
                                if (zoom > 1.001f) {
                                    // 两指平移：手指往右拖，画面跟着往右走。
                                    val pan = event.calculatePan()
                                    if (pan.x.isFinite() && pan.x != 0f) {
                                        panX = (panX - pan.x).coerceIn(0f, maxPanX(zoom))
                                    }
                                }
                                if (abs(zoom - 1f) > 0.01f || panX > 0.5f) {
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (pressed == 1 && zoom > 1.001f) {
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null) {
                                    val dx = change.position.x - change.previousPosition.x
                                    val dy = change.position.y - change.previousPosition.y
                                    // 先过触摸阈值再接管，否则单击图块会被判成拖动。
                                    if (!dragging) {
                                        moved += abs(dx) + abs(dy)
                                        if (moved > viewConfiguration.touchSlop) dragging = true
                                    }
                                    if (dragging) {
                                        if (dx != 0f) panX = (panX - dx).coerceIn(0f, maxPanX(zoom))
                                        // 纵向仍然走列表滚动，进度条和虚拟化才跟得上。
                                        if (dy != 0f) listState.dispatchRawDelta(-dy)
                                        change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .semantics { contentDescription = cd },
            contentPadding = PaddingValues(top = statusTop + 56.dp, bottom = navBottom + 56.dp),
        ) {
            items(plan.tileCount, key = { it }) { index ->
                val h = plan.tileHeight(index)
                val viewH = (h * zoom).roundToInt().coerceAtLeast(1)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { viewH.toDp() })
                        .clipToBounds(),
                ) {
                    TileItem(
                        session = session,
                        page = page,
                        index = index,
                        background = background,
                        zoom = zoom,
                        panX = panX,
                        modifier = Modifier.fillMaxSize(),
                        onFigureClick = onFigureClick,
                        onEmptyTap = onEmptyTap,
                    )
                }
            }
        }
    }
}

@Composable
private fun TileItem(
    session: DocumentSession,
    page: PageState,
    index: Int,
    background: Color,
    zoom: Float,
    panX: Float,
    modifier: Modifier,
    onFigureClick: (RenderItem.Figure) -> Unit,
    onEmptyTap: () -> Unit,
) {
    val plan = page.plan ?: return
    val bitmap by produceState<Bitmap?>(initialValue = null, index, page.version, session) {
        value = try {
            session.renderer.render(index)
        } catch (t: Throwable) {
            // 渲染失败（典型是内存不足）只让这张瓦片空着，并把原因留给 logcat，
            // 绝对不要让异常冒到 Compose 把进程带走。
            Log.w("PicText", "tile $index render failed", t)
            null
        }
    }
    // 位图换掉或瓦片滚出屏幕就立刻回收：×4 的瓦片 1080×1024 一张约 4MB，
    // 只靠 GC 回收的话，快速滚动会把几十张丢给 finalizer，低内存机器直接 OOM。
    // 回收本身延迟一帧（见 BitmapRecycler）：display list 可能还持有这一帧的位图。
    DisposableEffect(bitmap) {
        val held = bitmap
        onDispose { BitmapRecycler.recycle(held) }
    }
    Box(modifier = modifier.background(background)) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            // 整页放大：瓦片只是按 zoom 直接画大，不重新渲染、不重新规划。
            // dstSize 的高度取本瓦片的显示高度（= 瓦片高 × zoom），宽度按同倍率放大。
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(-panX.roundToInt(), 0),
                    dstSize = IntSize(
                        (plan.outputWidth * zoom).roundToInt().coerceAtLeast(1),
                        size.height.roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(index, page.version, zoom, panX) {
                    detectTapGestures { offset ->
                        // 点击坐标是放大后的屏幕像素，先换回计划坐标再命中图块。
                        val contentX = ((offset.x + panX) / zoom).roundToInt()
                        val contentY = (offset.y / zoom).roundToInt()
                        val pageY = plan.tileTop(index) + contentY
                        val figure = plan.figureAt(contentX, pageY)
                        if (figure != null) onFigureClick(figure) else onEmptyTap()
                    }
                },
        )
    }
}

/** 双指放大的视觉上限：再大只是把已有的位图糊开，且横向要看的内容更少。 */
private const val MAX_ZOOM = 3f
