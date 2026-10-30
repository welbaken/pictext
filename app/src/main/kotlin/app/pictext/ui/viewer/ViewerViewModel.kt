package app.pictext.ui.viewer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pictext.PicTextApp
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan
import app.pictext.core.plan.ReflowOptions
import app.pictext.core.plan.ReflowPlanner
import app.pictext.data.AppSettings
import app.pictext.data.SplitMode
import app.pictext.image.BitmapDecoder
import app.pictext.image.DecodeException
import app.pictext.image.FailureKind
import app.pictext.image.SourceImage
import app.pictext.image.SourceStripCache
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
    var hasOpened: Boolean = false
        private set

    init {
        viewModelScope.launch {
            container.settings.settings.collect { newSettings ->
                val old = settings
                settings = newSettings
                if (old.targetSp != newSettings.targetSp ||
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
            container.intake.cleanupOldFiles()
            val results = withContext(Dispatchers.IO) {
                uris.take(5).map { uri -> runCatching { container.intake.copy(uri) } }
            }
            entries = results.map { result ->
                result.fold(
                    onSuccess = { Entry(it.file, it.key, null) },
                    onFailure = { Entry(null, "", FailureKind.UNREADABLE) },
                )
            }
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
            _state.value = ViewerUiState.Ready(
                pages = pages.toList(),
                current = 0,
                showBars = true,
                mode = mode,
                settings = settings,
                viewportWidth = viewportWidth,
            )
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
        val decoded = BitmapDecoder.decodeForAnalysis(file)
        // 分析期会把 ×4 二次重排要用的候选字缝一并算好（每条正文行的所有安全切点）：
        // 用户在阅读页随时可能点 ×4，而分析只做一次；这点开销是一次线性扫描，远小于二值化。
        val layout = LayoutAnalyzer().analyze(decoded.gray, decoded.srcWidth, decoded.srcHeight)
        val source = SourceImage(file)
        val analysisMs = (System.nanoTime() - started) / 1_000_000
        val opts = options()
        val plan = planner.plan(layout, opts)
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
                "lines=${layout.textLines().size} figures=${layout.blocks.count { it is app.pictext.core.model.Block.Figure }} " +
                "split=${layout.splitX} clean=${layout.cleanRatio} scale=${plan.scale} gain=${plan.gain} " +
                "mode=${plan.mode} fallback=${plan.fallback} notes=${plan.notes}",
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

    private fun updateReadySettings() {
        val ready = _state.value as? ViewerUiState.Ready ?: return
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
