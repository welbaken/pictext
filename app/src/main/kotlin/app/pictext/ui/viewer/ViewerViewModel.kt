package app.pictext.ui.viewer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pictext.PicTextApp
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import app.pictext.core.plan.SmartReflow
import app.pictext.data.AppSettings
import app.pictext.data.SplitMode
import app.pictext.image.BitmapDecoder
import app.pictext.image.DecodeException
import app.pictext.image.FailureKind
import app.pictext.image.SourceImage
import app.pictext.image.SourceStripCache
import app.pictext.layout.AndroidTileSampler
import app.pictext.core.layout.PageContext
import app.pictext.render.TileRenderer
import app.pictext.session.DocumentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 阅读页 ViewModel：接收 URI → 拷贝 → 解码 → 分析 → 规划；设置变化只重新规划。
 */
private const val TAG = "PicText"

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PicTextApp).container
    private val planner = ReflowPlanner()

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private data class Entry(
        val file: File?,
        val key: String,
        val failure: FailureKind?,
    )

    private var entries = listOf<Entry>()
    private var sessions = mutableListOf<DocumentSession?>()
    private var pages = mutableListOf<PageState>()
    private val processing = HashSet<Int>()
    private val pageJobs = mutableListOf<Job>()
    private var openJob: Job? = null

    private var settings = AppSettings()
    private var viewportWidth = 0
    private var mode = ViewerMode.REFLOW

    /** 底栏三档。首次规划沿用“自动判断”，结果决定落在原图还是 ×2。 */
    private var level = ViewerLevel.X2
    private var autoLevel = true

    /** 双指缩放相对当前档位基准的偏移；×2 档最多 2.0（= ×4），×4 档最多 1.0。 */
    private var zoom = 1f
    private val scrollRatios = HashMap<Int, Float>()

    /** Activity 重建（旋转）时用来避免重复 intake；进程死亡后新 ViewModel 为 false。 */
    /** 设置流是否已完成第一次发射（首次发射只是读盘，不算用户操作）。 */
    private var settingsLoaded: Boolean = false

    var hasOpened: Boolean = false
        private set

    init {
        viewModelScope.launch {
            container.settings.settings.collect { newSettings ->
                val old = settings
                settings = newSettings
                // 第一次发射是“从磁盘读回已保存的设置”，不是用户改了什么。当成变更处理的话，
                // 冷启动后打开图片时会把 in-flight 的 open() 取消掉，界面就永远停在
                // “正在分析版式…”。这就是 V2-44 卡死的根因（docs/07 §13.8）。
                if (!settingsLoaded) {
                    settingsLoaded = true
                    updateReadySettings()
                    return@collect
                }
                if (old.smartLayout != newSettings.smartLayout) {
                    Log.d(TAG, "settings: smartLayout ${old.smartLayout}->${newSettings.smartLayout} entries=${entries.size}")
                    // 版式开关改变的是“分析结果”本身，不能只重新规划（docs/07 §4.10）。
                    reanalyzeAll()
                } else if (old.targetSp != newSettings.targetSp ||
                    old.splitMode != newSettings.splitMode
                ) {
                    replanAll()
                }
                updateReadySettings()
            }
        }
        viewportWidth = getApplication<Application>().resources.displayMetrics.widthPixels
    }

    /** 由阅读页在尺寸变化时调用；只重新规划，不重新分析。 */
    fun setViewportWidth(px: Int) {
        if (px <= 0 || px == viewportWidth) return
        viewportWidth = px
        replanAll()
        updateReadySettings()
    }

    /** 打开一批 URI（最多 5 个）。重复调用会关闭上一组会话。 */
    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        hasOpened = true
        cancelAllJobs()
        closeSessions()
        entries = emptyList()
        pages = mutableListOf()
        sessions = mutableListOf()
        mode = ViewerMode.REFLOW
        _state.value = ViewerUiState.Loading

        openJob = viewModelScope.launch {
            Log.d(TAG, "open: start uris=${uris.size}")
            container.intake.cleanupOldFiles()
            val results = withContext(Dispatchers.IO) {
                uris.take(5).map { uri -> runCatching { container.intake.copy(uri) } }
            }
            Log.d(TAG, "open: copied ok=${results.count { it.isSuccess }}/${results.size}")
            entries = results.map { result ->
                result.fold(
                    onSuccess = { Entry(it.file, it.key, null) },
                    onFailure = { Entry(null, "", FailureKind.UNREADABLE) },
                )
            }
            Log.d(TAG, "open: entries=${entries.size}")
            sessions = MutableList(entries.size) { null }
            pages = entries.mapIndexed { index, entry ->
                PageState(
                    title = "",
                    key = entry.key,
                    loading = entry.failure == null,
                    failure = entry.failure,
                    progress = if (entry.key.isNotEmpty()) {
                        container.progress.get(entry.key)
                    } else {
                        0f
                    },
                )
            }.toMutableList()

            if (entries.isEmpty()) {
                _state.value = ViewerUiState.Failed(FailureKind.UNREADABLE)
                return@launch
            }
            if (entries.all { it.failure != null }) {
                _state.value = ViewerUiState.Failed(FailureKind.UNREADABLE)
                return@launch
            }
            Log.d(TAG, "open: ready")
            _state.value = ViewerUiState.Ready(
                pages = pages.toList(),
                current = 0,
                showBars = true,
                mode = mode,
                settings = settings,
                viewportWidth = viewportWidth,
            )
            Log.d(TAG, "open: ensureProcessed(0)")
            ensureProcessed(0)
            if (entries.size > 1) ensureProcessed(1)
        }
    }

    /** 切换底栏档位：原图 / ×2 / ×4。切分方式与档位互相独立。 */
    fun setLevel(newLevel: ViewerLevel) {
        autoLevel = false
        val levelChanged = level != newLevel
        // 点同一个档位 = 回到该档的标准倍率（清掉双指缩放留下的偏移）。
        val zoomChanged = zoom != 1f
        if (!levelChanged && !zoomChanged) return
        level = newLevel
        zoom = 1f
        mode = if (newLevel == ViewerLevel.ORIGINAL) ViewerMode.ORIGINAL else ViewerMode.REFLOW
        val ready = _state.value as? ViewerUiState.Ready
        if (ready != null) {
            _state.value = ready.copy(level = newLevel, mode = mode, showBars = true)
        }
        replanAll()
        publish()
    }

    /**
     * 双指缩放：在“×2 ↔ ×4”这条连续轴上移动。
     *
     * 倍率 = 档位基准 × 手势缩放，范围 [1.0, 2.0]：上限就是 ×4 的基准，因为再往上一条满宽行
     * 要切 5 段以上，二次重排只能把字号压小，“同页一个字号”就保不住了。
     * 到达两端时档位一起切过去，底栏高亮才跟得上。
     */
    fun setZoomMultiplier(target: Float) {
        if (!target.isFinite() || level == ViewerLevel.ORIGINAL) return
        val m = target.coerceIn(ViewerLevel.MIN_MULTIPLIER, ViewerLevel.MAX_MULTIPLIER)
        val snapped = when {
            m >= ViewerLevel.MAX_MULTIPLIER - 0.02f -> ViewerLevel.X4
            m <= ViewerLevel.MIN_MULTIPLIER + 0.02f -> ViewerLevel.X2
            else -> level
        }
        val newZoom = (m / snapped.baseMultiplier).coerceIn(0.5f, 2f)
        if (snapped == level && kotlin.math.abs(newZoom - zoom) < 0.01f) return
        autoLevel = false
        level = snapped
        zoom = newZoom
        mode = ViewerMode.REFLOW
        val ready = _state.value as? ViewerUiState.Ready
        if (ready != null) _state.value = ready.copy(level = snapped, mode = mode)
        replanAll()
        publish()
    }

    /** 当前生效的重排倍率（档位基准 × 双指缩放），规划器与重排页共用。 */
    fun effectiveMultiplier(): Float =
        (level.baseMultiplier * zoom).coerceIn(ViewerLevel.MIN_MULTIPLIER, ViewerLevel.MAX_MULTIPLIER)

    fun setCurrentPage(index: Int) {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        val safe = index.coerceIn(0, ready.pages.lastIndex.coerceAtLeast(0))
        if (ready.current == safe && ready.pages.getOrNull(safe)?.loading == false) {
            // 仍然做一次预取
        }
        _state.value = ready.copy(current = safe)
        ensureProcessed(safe)
        ensureProcessed(safe - 1)
        ensureProcessed(safe + 1)
    }

    fun setTargetSp(sp: Float) {
        viewModelScope.launch { runCatching { container.settings.setTargetSp(sp) } }
    }

    fun setSplitMode(split: SplitMode) {
        viewModelScope.launch { runCatching { container.settings.setSplitMode(split) } }
    }

    fun setRememberProgress(remember: Boolean) {
        viewModelScope.launch { runCatching { container.settings.setRememberProgress(remember) } }
    }

    /** 打开/关闭 v2 版式识别（β）。改的是分析链路，会重跑当前页与下一页。 */
    fun setSmartLayout(enabled: Boolean) {
        viewModelScope.launch { runCatching { container.settings.setSmartLayout(enabled) } }
    }

    /** 当前是否已启用 v2（设置项 + 模型可用性都要满足才真正生效）。 */
    fun smartLayoutEnabled(): Boolean = settings.smartLayout && container.layoutModelReady()

    fun setBarsVisible(visible: Boolean) {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        if (ready.showBars == visible) return
        _state.value = ready.copy(showBars = visible)
    }

    fun toggleBars() {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        _state.value = ready.copy(showBars = !ready.showBars)
    }

    fun closePage(index: Int) {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        val session = sessions.getOrNull(index)
        session?.close()
        if (index in entries.indices) entries = entries.filterIndexed { i, _ -> i != index }
        if (index in pages.indices) pages.removeAt(index)
        if (index in sessions.indices) sessions.removeAt(index)
        if (pages.isEmpty()) {
            _state.value = ViewerUiState.Failed(FailureKind.UNREADABLE)
        } else {
            _state.value = ready.copy(
                pages = pages.toList(),
                current = ready.current.coerceIn(0, pages.lastIndex),
            )
        }
    }

    fun retry() {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        val index = ready.current
        val entry = entries.getOrNull(index) ?: return
        if (entry.failure != null || entry.file == null) return
        sessions.getOrNull(index)?.close()
        if (index in sessions.indices) sessions[index] = null
        pages[index] = pages[index].copy(loading = true, failure = null, plan = null)
        publish()
        ensureProcessed(index)
    }

    /** 当前页的会话；UI 用它取 renderer。 */
    fun sessionAt(index: Int): DocumentSession? = sessions.getOrNull(index)

    fun reportScroll(index: Int, ratio: Float) {
        scrollRatios[index] = ratio.coerceIn(0f, 1f)
        if (index in pages.indices) pages[index] = pages[index].copy(progress = ratio.coerceIn(0f, 1f))
    }

    fun saveProgress(index: Int, ratio: Float) {
        reportScroll(index, ratio)
        if (settings.rememberProgress) {
            val key = pages.getOrNull(index)?.key
            if (!key.isNullOrEmpty()) container.progress.put(key, ratio)
        }
        // 把内存里的滚动位置写回 StateFlow，旋转/分屏重建后新 UI 才能从同一位置开始。
        publish()
    }

    fun progressFor(index: Int): Float {
        val fromMap = scrollRatios[index]
        if (fromMap != null) return fromMap
        return pages.getOrNull(index)?.progress ?: 0f
    }

    private fun ensureProcessed(index: Int) {
        val entry = entries.getOrNull(index) ?: return
        if (entry.failure != null || entry.file == null) return
        if (sessions.getOrNull(index) != null) return
        if (!processing.add(index)) return
        val job = viewModelScope.launch {
            val result: Result<DocumentSession> = try {
                Result.success(withContext(Dispatchers.Default) { createSession(index, entry) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: DecodeException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(DecodeException(FailureKind.DECODE_FAILED, "out of memory", e))
            } catch (t: Throwable) {
                Result.failure(DecodeException(FailureKind.INTERNAL, "internal", t))
            }
            processing.remove(index)
            if (index !in entries.indices) return@launch
            result.onSuccess { session ->
                sessions[index] = session
                val plan = session.plan
                pages[index] = pages[index].copy(
                    mode = plan.mode,
                    plan = plan,
                    fallback = plan.fallback,
                    loading = false,
                    failure = null,
                    multiplier = session.multiplier,
                    version = session.planVersion,
                )
                publish()
            }.onFailure { error ->
                val kind = (error as? DecodeException)?.kind ?: FailureKind.INTERNAL
                pages[index] = pages[index].copy(
                    loading = false,
                    failure = kind,
                    plan = null,
                )
                publish()
            }
        }
        pageJobs.add(job)
        job.invokeOnCompletion { pageJobs.remove(job) }
    }

    private fun createSession(index: Int, entry: Entry): DocumentSession {
        val file = entry.file ?: throw DecodeException(FailureKind.UNREADABLE, "no file")
        val started = System.nanoTime()
        Log.d(TAG, "session[$index]: decode start")
        val decoded = BitmapDecoder.decodeForAnalysis(file)
        Log.d(TAG, "session[$index]: decode ok ${decoded.srcWidth}x${decoded.srcHeight}")
        val source = SourceImage(file)
        val opts = options()

        // v2：模型区域 + 阅读顺序；模型不可用 / 审计失败 / 竖排都会自动回退到 v1，
        // 回退是无感的（docs/07 §4.9），所以这里不需要 try/catch 兜底模型异常。
        val detector = if (settings.smartLayout) container.layoutDetector() else null
        Log.d(TAG, "session[$index]: detector=${detector != null}")
        val sampler = if (detector != null) AndroidTileSampler(source) else null
        val bg = decoded.gray.backgroundColor
        // 采样器持有“整页缓存位图”（V2-40：PNG 的 decodeRegion 每次都要从头解码，24 次细化
        // 就是 24 次整图解码）。分析一结束就释放，别让它跟着 ViewModel 一直占着十几 MB。
        val smart = try {
            SmartReflow().run(
                analysis = decoded.gray,
                page = PageContext(
                    srcWidth = decoded.srcWidth,
                    srcHeight = decoded.srcHeight,
                    backgroundColor = bg,
                    isDark = luminance(bg) < 110,
                ),
                planOptions = opts,
                sampler = sampler,
                regionSampler = sampler,
                detector = detector,
            )
        } finally {
            sampler?.close()
        }
        val layout = smart.layout
        val plan = smart.plan
        val analysisMs = (System.nanoTime() - started) / 1_000_000

        // 分享进入的第一页：按自动判断的结果决定初始档位（原图 or ×2），之后才由用户手动切换。
        if (autoLevel && index == 0) {
            autoLevel = false
            level = if (plan.mode == PlanMode.ORIGINAL) ViewerLevel.ORIGINAL else ViewerLevel.X2
        }
        val strips = SourceStripCache(source, viewportWidth.coerceAtLeast(1))
        val renderer = TileRenderer(layout, plan, source, strips)
        Log.d(
            "PicText",
            "page=$index src=${decoded.srcWidth}x${decoded.srcHeight} analysis=${analysisMs}ms " +
                "strategy=${layout.strategy} v2=${smart.fallbackCode ?: "none"} " +
                "regions=${layout.regions.size} lines=${layout.regionLines().size} " +
                "figures=${layout.blocks.count { it is app.pictext.core.model.Block.Figure }} " +
                "split=${layout.splitX} clean=${layout.cleanRatio} scale=${plan.scale} gain=${plan.gain} " +
                "mode=${plan.mode} fallback=${plan.fallback} " +
                "auditV2=${smart.layoutAudit?.ok} stages=${smart.layoutStats?.summary?.stageSummary()} " +
                "notes=${plan.notes}",
        )
        return DocumentSession(
            key = entry.key,
            sourceFile = file,
            sourceImage = source,
            strips = strips,
            layout = layout,
            plan = plan,
            renderer = renderer,
        ).also { it.multiplier = opts.scaleMultiplier }
    }

    private fun luminance(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8
    }

    /**
     * 计划参数。`level` 是用户当前选的三档：
     * - 原图：不进规划；
     * - ×2：forceReflow=true，跳过“长行不足/收益不足/多栏”等前置判断，照常两段；
     * - ×4：走**二次重排**（scaleMultiplier=2）——把 ×2 的结果当新图，字号翻倍、
     *   放不下的行按候选字缝再切到 2/4 段，而不是简单乘倍率再压回列宽。
     *
     * 双指缩放会把倍率在 [1, 2] 之间连续调整（见 [setZoomMultiplier]）；倍率 > 1 时
     * 就打开二次重排，段数上限同步放宽到 4。
     */
    private fun options(): ReflowOptions {
        val app = getApplication<Application>()
        val density = app.resources.displayMetrics.scaledDensity
        val targetEm = (settings.targetSp * density).roundToInt().coerceAtLeast(14)
        val forced = !autoLevel && level != ViewerLevel.ORIGINAL
        val multiplier = effectiveMultiplier()
        return ReflowOptions(
            outputWidth = viewportWidth.coerceAtLeast(1),
            targetEm = targetEm,
            splitEnabled = settings.splitMode != SplitMode.OFF,
            forceTwo = settings.splitMode == SplitMode.FORCE_TWO,
            forceReflow = forced,
            scaleMultiplier = multiplier,
            maxSegments = if (multiplier > 1.001f) 4 else 2,
            // 倍率的基准与档位无关（都受 maxScale=3 约束），最终上限是
            // maxScale × scaleMultiplier ≤ 6。
            maxScale = 3f,
        )
    }

    /**
     * 丢弃现有分析结果，重跑当前页与下一页。
     *
     * v2 开关变的是**分析**（区域/顺序），不是规划参数，所以不能走 [replanAll]。
     * 只重跑当前页与预取页：用户翻到别的页时 [ensureProcessed] 会补上。
     */
    private fun reanalyzeAll() {
        // 还没有任何图片时什么都不用重分析。更要紧的是：这里会 `cancelAllJobs()`，
        // 而 `open()` 也在同一个 job 列表里——早期版本会在“刚打开图片、正在拷文件”时
        // 被设置项变更连坐取消，界面永远停在 Loading（V2-44）。
        if (entries.isEmpty()) return
        cancelAllJobs()
        closeSessions()
        sessions = MutableList(entries.size) { null }
        for (i in pages.indices) {
            if (entries.getOrNull(i)?.failure == null) {
                pages[i] = pages[i].copy(loading = true, failure = null, plan = null)
            }
        }
        publish()
        val current = (_state.value as? ViewerUiState.Ready)?.current ?: 0
        ensureProcessed(current)
        ensureProcessed(current + 1)
    }

    private fun replanAll() {
        var changed = false
        val opts = options()
        for (i in sessions.indices) {
            val session = sessions[i] ?: continue
            val newPlan = planner.plan(session.layout, opts)
            val newStrips = if (session.strips.outputWidth == viewportWidth.coerceAtLeast(1)) {
                session.strips
            } else {
                // 旧条带可能仍被正在渲染的瓦片使用，交给 GC 回收，立即 recycle 会造成竞态。
                SourceStripCache(session.sourceImage, viewportWidth.coerceAtLeast(1))
            }
            session.strips = newStrips
            session.plan = newPlan
            session.multiplier = opts.scaleMultiplier
            session.planVersion++
            session.renderer = TileRenderer(session.layout, newPlan, session.sourceImage, newStrips)
            if (i in pages.indices) {
                pages[i] = pages[i].copy(
                    mode = newPlan.mode,
                    plan = newPlan,
                    fallback = newPlan.fallback,
                    multiplier = session.multiplier,
                    version = session.planVersion,
                )
            }
            changed = true
        }
        if (changed) publish()
    }

    private fun updateReadySettings() {        val ready = _state.value as? ViewerUiState.Ready ?: return
        _state.value = ready.copy(settings = settings, viewportWidth = viewportWidth)
    }

    private fun publish() {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        val current = ready.current.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        _state.value = ready.copy(
            pages = pages.toList(),
            current = current,
            settings = settings,
            mode = mode,
            level = level,
            viewportWidth = viewportWidth,
        )
    }

    private fun cancelAllJobs() {
        openJob?.cancel()
        openJob = null
        pageJobs.forEach { it.cancel() }
        pageJobs.clear()
        processing.clear()
    }

    private fun closeSessions() {
        sessions.forEach { it?.close() }
        sessions.clear()
    }

    override fun onCleared() {
        cancelAllJobs()
        closeSessions()
        super.onCleared()
    }

    /** 供 UI 判断是否展示“分析中”提示。 */
    fun analyzing(): Boolean = (state.value as? ViewerUiState.Ready)?.pages?.any { it.loading } == true

    /** 兼容架构文档中的 PageState 字段命名。 */
    fun fallbackOf(index: Int): FallbackReason =
        pages.getOrNull(index)?.fallback ?: FallbackReason.NONE

    fun planOf(index: Int): ReflowPlan? = pages.getOrNull(index)?.plan

    fun modeOf(index: Int): PlanMode = pages.getOrNull(index)?.mode ?: PlanMode.ORIGINAL
}





