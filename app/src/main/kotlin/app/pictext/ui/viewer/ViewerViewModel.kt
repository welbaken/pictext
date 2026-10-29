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
        if (level == newLevel) return
        level = newLevel
        mode = if (newLevel == ViewerLevel.ORIGINAL) ViewerMode.ORIGINAL else ViewerMode.REFLOW
        val ready = _state.value as? ViewerUiState.Ready
        if (ready != null) {
            _state.value = ready.copy(level = newLevel, mode = mode, showBars = true)
        }
        replanAll()
        publish()
    }

    fun setMode(newMode: ViewerMode) {
        mode = newMode
        val ready = _state.value as? ViewerUiState.Ready ?: return
        _state.value = ready.copy(mode = newMode, showBars = true)
        publish()
    }

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
        val layout = LayoutAnalyzer().analyze(decoded.gray, decoded.srcWidth, decoded.srcHeight)
        val source = SourceImage(file)
        val analysisMs = (System.nanoTime() - started) / 1_000_000
        val plan = planner.plan(layout, options())
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
        )
    }

    /**
     * 计划参数。`level` 是用户当前选的三档：
     * - 原图：不进规划；
     * - ×2：forceReflow=true，跳过“长行不足/收益不足/多栏”等前置判断，照常两段；
     * - ×4：在 ×2 的倍率上再 ×2（scaleMultiplier），宽行允许切 4 段。
     */
    private fun options(): ReflowOptions {
        val app = getApplication<Application>()
        val density = app.resources.displayMetrics.scaledDensity
        val targetEm = (settings.targetSp * density).roundToInt().coerceAtLeast(14)
        val forced = !autoLevel && level != ViewerLevel.ORIGINAL
        return ReflowOptions(
            outputWidth = viewportWidth.coerceAtLeast(1),
            targetEm = targetEm,
            splitEnabled = settings.splitMode != SplitMode.OFF,
            forceTwo = settings.splitMode == SplitMode.FORCE_TWO,
            forceReflow = forced,
            scaleMultiplier = if (level == ViewerLevel.X4) 2f else 1f,
            maxSegments = if (level == ViewerLevel.X4) 4 else 2,
            maxScale = if (level == ViewerLevel.X4) 6f else 3f,
        )
    }

    private fun replanAll() {
        var changed = false
        for (i in sessions.indices) {
            val session = sessions[i] ?: continue
            val newPlan = planner.plan(session.layout, options())
            val newStrips = if (session.strips.outputWidth == viewportWidth.coerceAtLeast(1)) {
                session.strips
            } else {
                // 旧条带可能仍被正在渲染的瓦片使用，交给 GC 回收，立即 recycle 会造成竞态。
                SourceStripCache(session.sourceImage, viewportWidth.coerceAtLeast(1))
            }
            session.strips = newStrips
            session.plan = newPlan
            session.planVersion++
            session.renderer = TileRenderer(session.layout, newPlan, session.sourceImage, newStrips)
            if (i in pages.indices) {
                pages[i] = pages[i].copy(
                    mode = newPlan.mode,
                    plan = newPlan,
                    fallback = newPlan.fallback,
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
