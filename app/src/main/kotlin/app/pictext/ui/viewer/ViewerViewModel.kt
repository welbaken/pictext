package app.pictext.ui.viewer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pictext.PicTextApp
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.model.Block
import app.pictext.core.model.PlanMode
import app.pictext.core.plan.PlanAuditor
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

    /** 首屏自动档位是否已定。只影响“默认选哪一档”，**不再当作**用户意图。 */
    private var autoLevelApplied = false

    /**
     * 用户是否明确选过渡（底栏三档或双指缩放）。
     *
     * 这是 `ReflowOptions.forceReflow` 的**唯一**来源：只有用户自己要求时，才跳过
     * “长行不足 / 收益不足 / 多栏”这些自动判断。首屏按计划结果自动挑档位
     * （[autoLevelApplied]）不算用户意图——否则第 2 页起的所有页都会变成强制重排。
     */
    private var userForced = false

    /** 最近一次打开的 URI：全局失败页的“重试”用它重走 [open]。 */
    private var lastUris: List<Uri> = emptyList()

    /** Activity 重建（旋转）时用来避免重复 intake；进程死亡后新 ViewModel 为 false。 */
    var hasOpened: Boolean = false
        private set

    /** 设置流是否已完成第一次发射（首次发射只是读盘，不算用户操作）。 */
    private var settingsLoaded: Boolean = false

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
        lastUris = uris.take(5)
        // 新的一张图重新回到“自动判断”：档位、用户强制标记都要归零，
        // 否则上一张图点过 ×2 会让这一张一上来就跳过回退判断。
        mode = ViewerMode.REFLOW
        level = ViewerLevel.X2
        autoLevelApplied = false
        userForced = false
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
            pages = entries.mapIndexed { _, entry ->
                PageState(
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
                level = level,
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
        // 重排倍率**只由这三个按钮决定**：双指缩放是纯视觉放大，不再参与规划。
        if (level == newLevel) {
            // 点当前档位 = 回到该档的标准倍率：倍率没变，但要把双指放大归位。
            // 归位靠 replanAll() 把 planVersion +1，重排页看到版本变化就会把 zoom/panX 复位。
            replanAll()
            publish()
            return
        }
        // 真正改变了档位才算“用户强制”：后面 replanAll 要跳过自动回退判断。
        userForced = true
        level = newLevel
        mode = if (newLevel == ViewerLevel.ORIGINAL) ViewerMode.ORIGINAL else ViewerMode.REFLOW
        val ready = _state.value as? ViewerUiState.Ready
        if (ready != null) {
            _state.value = ready.copy(level = newLevel, mode = mode, showBars = true)
        }
        replanAll()
        publish()
    }

    /**
     * 当前生效的重排倍率 = 档位基准（×2 = 1.0、×4 = 2.0）。
     *
     * 双指缩放（[ReflowPage]）只做整页视觉放大，**不改变这个值**，也不触发重新规划：
     * 规划一次要把整页行重新切分、重新算倍率，捏合时每帧跑一遍既卡又会把版面跳来跳去。
     */
    fun effectiveMultiplier(): Float = level.baseMultiplier

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

    fun setBarsVisible(visible: Boolean) {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        if (ready.showBars == visible) return
        _state.value = ready.copy(showBars = visible)
    }

    fun toggleBars() {
        val ready = _state.value as? ViewerUiState.Ready ?: return
        _state.value = ready.copy(showBars = !ready.showBars)
    }

    /**
     * 重试。
     *
     * 两种状态必须分开处理：
     * - **全局失败**（URI 打不开、全部 copy 失败）→ 用 [lastUris] 重走 [open]；
     *   早期实现直接读 `state as? Ready`，于是这个状态下的“重试”按钮点了没有任何反应；
     * - **单页失败** → 只关闭并重建这一页的会话。
     */
    fun retry() {
        val ready = _state.value as? ViewerUiState.Ready
        if (ready == null) {
            if (_state.value is ViewerUiState.Failed && lastUris.isNotEmpty()) open(lastUris)
            return
        }
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


    private fun ensureProcessed(index: Int) {
        val entry = entries.getOrNull(index) ?: return
        if (entry.failure != null || entry.file == null) return
        if (sessions.getOrNull(index) != null) return
        if (!processing.add(index)) return
        // 规划参数在主线程读一次再当快照传进后台：`level`/`viewportWidth` 都是
        // 「主线程写、后台读」的字段，后台直接读既有可见性问题，也让第 2 页看到
        // 第一页刚改过的档位（旧实现就是在这里把 autoLevel 误当成 forceReflow 的）。
        val opts = options()
        val job = viewModelScope.launch {
            val result: Result<DocumentSession> = try {
                Result.success(withContext(Dispatchers.Default) { createSession(index, entry, opts) })
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
                // 首屏档位由**第一页的真实计划**决定（原图 or ×2）；用户已经自己点过
                // 就不覆盖。这里必须留在主线程：档位是 UI 状态，不是后台线程的局部变量。
                if (index == 0 && !autoLevelApplied) {
                    autoLevelApplied = true
                    if (!userForced) {
                        level = if (plan.mode == PlanMode.ORIGINAL) {
                            ViewerLevel.ORIGINAL
                        } else {
                            ViewerLevel.X2
                        }
                        mode = if (level == ViewerLevel.ORIGINAL) ViewerMode.ORIGINAL else ViewerMode.REFLOW
                    }
                }
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

    /**
     * 建一页会话。**只在 `Dispatchers.Default` 上跑**，因此这里不读写任何共享可变状态：
     * 规划参数 [opts] 是主线程算好的快照，档位决策在 [ensureProcessed] 的主线程回调里做。
     */
    private fun createSession(index: Int, entry: Entry, opts: ReflowOptions): DocumentSession {
        val file = entry.file ?: throw DecodeException(FailureKind.UNREADABLE, "no file")
        val started = System.nanoTime()
        Log.d(TAG, "session[$index]: decode start")
        val decoded = BitmapDecoder.decodeForAnalysis(file)
        Log.d(TAG, "session[$index]: decode ok ${decoded.srcWidth}x${decoded.srcHeight}")
        val source = SourceImage(file)

        // 0.5 只发 v1：全页投影 + 原规划器。v2 仍留在 :core/:cli 做技术验证，不进 APK。
        val layout = LayoutAnalyzer().analyze(decoded.gray, decoded.srcWidth, decoded.srcHeight)
        val plan = ReflowPlanner().plan(layout, opts)
        val audit = PlanAuditor.audit(layout, plan)
        val analysisMs = (System.nanoTime() - started) / 1_000_000

        val strips = SourceStripCache(source, opts.outputWidth)
        val renderer = TileRenderer(layout, plan, source, strips)
        Log.d(
            TAG,
            "page=$index src=${decoded.srcWidth}x${decoded.srcHeight} analysis=${analysisMs}ms " +
                "strategy=${layout.strategy} lines=${layout.textLines().size} " +
                "figures=${layout.blocks.count { it is Block.Figure }} " +
                "split=${layout.splitX} clean=${layout.cleanRatio} scale=${plan.scale} gain=${plan.gain} " +
                "mode=${plan.mode} fallback=${plan.fallback} " +
                "audit=${audit.ok} notes=${plan.notes}",
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
     * - ×2：用户点过才算 `forceReflow`，跳过“长行不足/收益不足/多栏”等前置判断，照常两段；
     * - ×4：走**二次重排**（scaleMultiplier=2）——把 ×2 的结果当新图，字号翻倍、
     *   放不下的行按候选字缝再切到 2/4 段，而不是简单乘倍率再压回列宽。
     *
     * `forceReflow` 只看 [userForced]：首屏按计划结果自动挑档位不算用户意图，
     * 否则第 2 页起会全部跳过硬回退（详见 [userForced] 的注释）。
     *
     * 倍率只来自底栏按钮（×2 = 1.0、×4 = 2.0）；双指缩放是纯视觉放大，不进这里。
     */
    private fun options(): ReflowOptions {
        val app = getApplication<Application>()
        val density = app.resources.displayMetrics.scaledDensity
        val targetEm = (settings.targetSp * density).roundToInt().coerceAtLeast(14)
        val forced = userForced && level != ViewerLevel.ORIGINAL
        val multiplier = effectiveMultiplier()
        return ReflowOptions(
            outputWidth = viewportWidth.coerceAtLeast(1),
            targetEm = targetEm,
            splitEnabled = settings.splitMode != SplitMode.OFF,
            forceTwo = settings.splitMode == SplitMode.FORCE_TWO,
            forceReflow = forced,
            scaleMultiplier = multiplier,
            maxSegments = if (multiplier > 1.001f) 4 else 2,
            // 倍率上限必须 ≥ 目标字身，否则高密度屏会把用户选的字号压回去：
            // 640dpi 上 targetSp=16 → targetEm=64px、sEm≈4.1，而本图 sFit≈3.85；
            // 上限写死 3 时 1440×2560 上 bodyScale=3.0，正文只占屏宽 78%（实测 73% 墨迹）。
            // ×2 放到 4 → 由 sFit 定档，正文正好填满列宽；×4 仍收敛在 3，
            // 二次重排后最终上限 6。
            maxScale = if (multiplier > 1.001f) 3f else 4f,
        )
    }

    private fun replanAll() {
        var changed = false
        val opts = options()
        for (i in sessions.indices) {
            val session = sessions[i] ?: continue
            val newPlan = planner.plan(session.layout, opts)
            val newStrips = if (session.strips.outputWidth == opts.outputWidth) {
                session.strips
            } else {
                // 旧条带可能仍被正在渲染的瓦片使用，交给 GC 回收，立即 recycle 会造成竞态。
                SourceStripCache(session.sourceImage, opts.outputWidth)
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
}





