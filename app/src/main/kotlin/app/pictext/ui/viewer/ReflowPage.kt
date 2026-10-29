package app.pictext.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictext.R
import app.pictext.core.model.RenderItem
import app.pictext.session.DocumentSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * 重排正文：1024px 瓦片 + LazyColumn 虚拟化。每张瓦片滚出屏幕即取消渲染，
 * 峰值内存与文档总高度无关。
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

    val cd = stringResource(R.string.cd_image_content)
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(modifier = modifier.fillMaxSize().background(background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(background)
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
            null
        }
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
