package app.pictext.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pictext.R
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.PlanMode
import app.pictext.core.model.RectI
import app.pictext.image.FailureKind
import app.pictext.image.SourceImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 阅读页入口：Loading / Failed / Ready 三态，任何异常都不会冒到 Compose。 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LaunchedEffect(widthPx) { viewModel.setViewportWidth(widthPx) }
        when (val s = state) {
            is ViewerUiState.Loading -> LoadingScreen()
            is ViewerUiState.Failed -> FailureScreen(kind = s.kind, onRetry = viewModel::retry, onBack = onBack)
            is ViewerUiState.Ready -> ReadyViewer(state = s, viewModel = viewModel, onBack = onBack)
        }
    }
}

@Composable
private fun LoadingScreen() {
    var analyzing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(500)
        analyzing = true
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(if (analyzing) R.string.loading_analyzing else R.string.loading_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailureScreen(kind: FailureKind, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(failureTitle(kind)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(failureDetail(kind)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.error_back)) }
        }
    }
}

@Composable
private fun ReadyViewer(
    state: ViewerUiState.Ready,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
) {
    val pages = state.pages
    if (pages.isEmpty()) {
        FailureScreen(FailureKind.UNREADABLE, onRetry = viewModel::retry, onBack = onBack)
        return
    }
    val current = state.current.coerceIn(0, pages.lastIndex)
    val currentPage = pages[current]
    val effectiveMode = if (currentPage.plan?.mode == PlanMode.ORIGINAL) {
        ViewerMode.ORIGINAL
    } else {
        state.mode
    }
    val dark = currentPage.plan?.isDark == true
    ApplySystemBarAppearance(dark)

    val pagerState = rememberPagerState(
        initialPage = current,
        pageCount = { pages.size },
    )
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setCurrentPage(pagerState.currentPage)
    }
    LaunchedEffect(state.current) {
        if (pagerState.currentPage != state.current) {
            pagerState.animateScrollToPage(state.current)
        }
    }


    var figure by remember { mutableStateOf<Pair<SourceImage, RectI>?>(null) }
    var progress by remember(current) { mutableFloatStateOf(currentPage.progress.coerceIn(0f, 1f)) }

    val fallbackMessage = fallbackMessage(currentPage.fallback, currentPage.plan?.mode)
    var showBanner by remember(currentPage.key, currentPage.fallback) {
        mutableStateOf(fallbackMessage != null)
    }
    // 提示条停留 6 秒或由用户手动关闭；有“原图/重排”可切换时给出按钮，而不是让用户干等。
    LaunchedEffect(currentPage.key, currentPage.fallback) {
        if (fallbackMessage != null) {
            showBanner = true
            delay(6000)
            showBanner = false
        }
    }

    DisposableEffect(current) {
        onDispose { viewModel.saveProgress(current, progress) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, current) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveProgress(current, progress)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(currentPage.plan?.backgroundColor ?: 0xFFFFFFFF.toInt())),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index ->
                "$index:${pages.getOrNull(index)?.key.orEmpty()}"
            },
        ) { index ->
            val page = pages.getOrNull(index) ?: return@HorizontalPager
            val session = viewModel.sessionAt(index)
            PageBody(
                index = index,
                page = page,
                session = session,
                mode = if (page.plan?.mode == PlanMode.ORIGINAL) ViewerMode.ORIGINAL else state.mode,

                onProgress = { ratio ->
                    if (index == current) progress = ratio
                    viewModel.reportScroll(index, ratio)
                },
                onBarsVisible = { visible -> if (index == current) viewModel.setBarsVisible(visible) },
                onFigureClick = { figureItem ->
                    val s = viewModel.sessionAt(index)
                    if (s != null) figure = s.sourceImage to figureItem.src
                },
                onEmptyTap = { viewModel.toggleBars() },
                onRetry = viewModel::retry,
            )
        }

        ViewerTopBar(
            title = if (pages.size > 1) {
                stringResource(R.string.page_indicator, current + 1, pages.size)
            } else {
                stringResource(R.string.app_name)
            },
            // 原图模式也允许收起顶栏：图片已经让出了顶栏的高度（见 OriginalPage），
            // 收起后是整屏，点一下正文再唤回来。
            visible = state.showBars,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (showBanner && fallbackMessage != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                        MaterialTheme.shapes.medium,
                    )
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fallbackMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.weight(1f),
                )
                // 只有在“重排视图”下才提供“查看原图”；原图页面上这个动作没有意义。
                if (effectiveMode == ViewerMode.REFLOW) {
                    TextButton(
                        onClick = {
                            viewModel.saveProgress(current, progress)
                            viewModel.setLevel(ViewerLevel.ORIGINAL)
                            showBanner = false
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.banner_view_original),
                            color = MaterialTheme.colorScheme.inversePrimary,
                        )
                    }
                }
                TextButton(onClick = { showBanner = false }) {
                    Text(
                        text = stringResource(R.string.banner_dismiss),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }

        ViewerBottomBar(
            visible = state.showBars,
            level = state.level,
            progress = progress,
            onLevelSelected = viewModel::setLevel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        val figureValue = figure
        if (figureValue != null) {
            FigureDialog(
                source = figureValue.first,
                figureSrc = figureValue.second,
                onDismiss = { figure = null },
            )
        }

    }
}

@Composable
private fun PageBody(
    index: Int,
    page: PageState,
    session: app.pictext.session.DocumentSession?,
    mode: ViewerMode,
    onProgress: (Float) -> Unit,
    onBarsVisible: (Boolean) -> Unit,
    onFigureClick: (app.pictext.core.model.RenderItem.Figure) -> Unit,
    onEmptyTap: () -> Unit,
    onRetry: () -> Unit,
) {
    val failure = page.failure
    if (failure != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(failureTitle(failure)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(failureDetail(failure)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
            }
        }
        return
    }
    if (page.loading || session == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.loading_page),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val plan = page.plan
    if (mode == ViewerMode.ORIGINAL || plan == null || plan.mode == PlanMode.ORIGINAL) {
        OriginalPage(
            source = session.sourceImage,
            backgroundColor = plan?.backgroundColor ?: 0xFFFFFFFF.toInt(),
            initialProgress = page.progress,
            onProgress = onProgress,
            onTap = onEmptyTap,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        ReflowPage(
            session = session,
            page = page,
            onProgress = onProgress,
            onBarsVisible = onBarsVisible,
            onFigureClick = onFigureClick,
            onEmptyTap = onEmptyTap,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ApplySystemBarAppearance(dark: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(dark) {
        val activity = context.findActivity()
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        onDispose { }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun failureTitle(kind: FailureKind): Int = when (kind) {
    FailureKind.UNREADABLE -> R.string.error_unreadable
    FailureKind.UNSUPPORTED_FORMAT -> R.string.error_unsupported
    FailureKind.DECODE_FAILED -> R.string.error_decode
    FailureKind.INTERNAL -> R.string.error_internal
}

private fun failureDetail(kind: FailureKind): Int = when (kind) {
    FailureKind.UNREADABLE -> R.string.error_detail_unreadable
    FailureKind.UNSUPPORTED_FORMAT -> R.string.error_detail_unsupported
    FailureKind.DECODE_FAILED -> R.string.error_detail_decode
    FailureKind.INTERNAL -> R.string.error_detail_internal
}

@Composable
private fun fallbackMessage(reason: FallbackReason, mode: PlanMode?): String? = when (reason) {
    FallbackReason.NO_INK -> stringResource(R.string.fallback_no_ink)
    FallbackReason.NO_TEXT -> stringResource(R.string.fallback_no_text)
    FallbackReason.TOO_FEW_LONG_LINES -> stringResource(R.string.fallback_few_lines)
    // D-B：weak_cut 现在可能直接回退原图，提示语要跟着模式走，不能再写死“已整页放大”。
    FallbackReason.WEAK_CUT -> stringResource(
        if (mode == PlanMode.ORIGINAL) R.string.fallback_weak_original else R.string.fallback_weak,
    )
    FallbackReason.GAIN_TOO_SMALL -> stringResource(R.string.fallback_gain)
    FallbackReason.TOO_TALL -> stringResource(R.string.fallback_too_tall)
    FallbackReason.MULTI_COLUMN -> stringResource(R.string.fallback_multi_column)
    // v2：模型/版式链路不可用或审计没过，已静默降级到 v1（docs/07 §4.9：回退是无感的）。
    FallbackReason.LAYOUT_FALLBACK -> null
    FallbackReason.NONE -> null
}
