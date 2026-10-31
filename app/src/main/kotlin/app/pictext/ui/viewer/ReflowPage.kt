package app.pictext.ui.viewer

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * 双指缩放不再只是“换个底板”：手势期间按比例做临时放大（即时反馈），松手后把
 * 目标倍率交给 ViewModel 重新规划（见 [ViewerViewModel.setZoomMultiplier]），字变大的同时
 * 行会重新切分，不会出现横向溢出。单指拖动/横向翻页完全不受影响。
 */
@Composable
fun ReflowPage(
    session: DocumentSession,
    page: PageState,
    multiplier: Float,
    onProgress: (Float) -> Unit,
    onBarsVisible: (Boolean) -> Unit,
    onZoom: (Float) -> Unit,
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
                    ((index * plan.tileHeightPx + offset).toFloat() / plan.height).coerceIn(0f, 1f)
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

    // 切换档位 / 双指缩放会换一份计划，总高度也跟着变。这里按“阅读比例”重新定位，
    // 否则 LazyColumn 会保留旧的 item 下标，用户会觉得换档后内容跳了一段。
    LaunchedEffect(page.version, plan.height) {
        if (plan.height <= 0 || plan.tileHeightPx <= 0) return@LaunchedEffect
        val target = (page.progress.coerceIn(0f, 1f) * plan.height).roundToInt()
        val index = (target / plan.tileHeightPx).coerceIn(0, (plan.tileCount - 1).coerceAtLeast(0))
        listState.scrollToItem(index, target % plan.tileHeightPx)
    }

    // 手势期间的临时倍率：只做视觉放大，松手后才重新规划（规划一次约 10ms，不必每帧跑）。
    var preview by remember { mutableFloatStateOf(1f) }
    val commitZoom by rememberUpdatedState(onZoom)
    val safeMultiplier = multiplier.coerceIn(ViewerLevel.MIN_MULTIPLIER, ViewerLevel.MAX_MULTIPLIER)
    val minFactor = (ViewerLevel.MIN_MULTIPLIER / safeMultiplier).coerceIn(0.25f, 1f)
    val maxFactor = (ViewerLevel.MAX_MULTIPLIER / safeMultiplier).coerceIn(minFactor, 4f)

    val cd = stringResource(R.string.cd_image_content)
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(modifier = modifier.fillMaxSize().background(background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .graphicsLayer {
                    // 双指缩放期间的预览；松手后归 1，由新计划接管字号。
                    scaleX = preview
                    scaleY = preview
                }
                .pointerInput(minFactor, maxFactor) {
                    awaitEachGesture {
                        // Initial pass：先于 LazyColumn 的滚动与 Pager 的翻页拿到事件，
                        // 只有两指以上才消费，单指拖动/翻页照旧。
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var factor = 1f
                        var active = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoomChange = event.calculateZoom()
                                if (zoomChange.isFinite() && zoomChange != 1f) {
                                    factor = (factor * zoomChange).coerceIn(minFactor, maxFactor)
                                    if (abs(factor - 1f) > 0.02f) active = true
                                    if (active) preview = factor
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        preview = 1f
                        if (active) {
                            val target = (safeMultiplier * factor)
                                .coerceIn(ViewerLevel.MIN_MULTIPLIER, ViewerLevel.MAX_MULTIPLIER)
                            if (abs(target - safeMultiplier) > 0.01f) commitZoom(target)
                        }
                    }
                }
                .semantics { contentDescription = cd },
            contentPadding = PaddingValues(top = statusTop + 56.dp, bottom = navBottom + 56.dp),
        ) {
            items(plan.tileCount, key = { it }) { index ->
                val h = plan.tileHeight(index)
                TileItem(
                    session = session,
                    page = page,
                    index = index,
                    background = background,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { h.coerceAtLeast(1).toDp() }),
                    onFigureClick = onFigureClick,
                    onEmptyTap = onEmptyTap,
                )
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
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.Medium,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(index, page.version) {
                    detectTapGestures { offset ->
                        val pageY = plan.tileTop(index) + offset.y.roundToInt()
                        val figure = plan.figureAt(offset.x.roundToInt(), pageY)
                        if (figure != null) onFigureClick(figure) else onEmptyTap()
                    }
                },
        )
    }
}
