package app.pictext.core.plan

import app.pictext.core.analyze.AnalysisOptions
import app.pictext.core.analyze.LayoutAnalyzer
import app.pictext.core.layout.LayoutDetector
import app.pictext.core.layout.LayoutOptions
import app.pictext.core.layout.LayoutOutcome
import app.pictext.core.layout.LayoutPipeline
import app.pictext.core.layout.ModelContract
import app.pictext.core.layout.PageContext
import app.pictext.core.layout.RegionSampler
import app.pictext.core.layout.TileSampler
import app.pictext.core.model.Diagnostics
import app.pictext.core.model.FallbackReason
import app.pictext.core.model.GrayImage
import app.pictext.core.model.LayoutStrategy
import app.pictext.core.model.PageLayout
import app.pictext.core.model.PlanMode
import app.pictext.core.model.ReflowPlan

/** 一次“智能重排”的完整结果：用了哪条链路、计划、以及两套审计。 */
data class SmartReflowResult(
    val layout: PageLayout,
    val plan: ReflowPlan,
    /** v1 审计（两套链路都跑：v2 下它负责“块一个都不能丢”的兜底）。 */
    val projectionAudit: PlanAuditReport,
    /** v2 审计；回退到 v1 时为 `null`。 */
    val layoutAudit: LayoutAuditReport? = null,
    /** v2 管线的统计；回退时为 `null`。 */
    val layoutStats: LayoutOutcome.Success? = null,
    /** 回退到 v1 / 原图的原因（如 `layout_low_coverage`）；没回退为 `null`。 */
    val fallbackCode: String? = null,
    /** v2 管线的原始结论（回退时也能看到 tile 数/检出数/覆盖率，用于调参）。 */
    val v2Outcome: LayoutOutcome? = null,
)

/**
 * 两档**手动**选择：用户不开「强化重排」→ 只跑 v1；开了 → 只跑 v2（失败显示原图）。
 *
 * ```text
 * 强化重排关 → LayoutAnalyzer + ReflowPlanner v1
 * 强化重排开 → LayoutPipeline（区域/顺序/细化/覆盖审计）→ ReflowPlanner v2 → LayoutAuditor
 *             任一失败 → ORIGINAL（不回退 v1，避免“我明明开着强化重排，结果拿到 v1”）
 * ```
 *
 * **不再做 v2→v1 的自动回退**，也不再根据页面内容自动判断用哪一版（V2-51）：
 * 版本由用户显式选择；v2 失败时给原图 + 诊断码，比静默换成另一套排版可预期。
 *
 * 这个类是**纯 Kotlin**：检测器/采样器都是接口，所以 core 的单测可以用假检测器跑完整链路，
 * 不需要 native 库（docs/07 §2.9g）。
 */
class SmartReflow(
    private val analysisOptions: AnalysisOptions = AnalysisOptions(),
    private val layoutOptions: LayoutOptions = LayoutOptions(),
    private val contract: ModelContract = ModelContract(),
) {
    private val pipeline = LayoutPipeline(layoutOptions, analysisOptions, contract)

    /**
     * @param analysis v1 用的全页灰度分析图（长边 ≤1600）；v2 不用它。
     * @param detector v2 检测器；`null` 表示用户没开强化重排，这次只跑 v1。
     */
    fun run(
        analysis: GrayImage,
        page: PageContext,
        planOptions: ReflowOptions,
        sampler: TileSampler? = null,
        regionSampler: RegionSampler? = null,
        detector: LayoutDetector? = null,
        onTile: ((Int, Int) -> Unit)? = null,
    ): SmartReflowResult {
        // 用户没选强化重排：这是一条正常路径，不是回退，plan 里也不该出现 fallback 诊断。
        if (detector == null) return projectionPath(analysis, page, planOptions)

        val outcome = if (sampler != null && regionSampler != null) {
            pipeline.analyze(sampler, detector, regionSampler, page, onTile)
        } else {
            LayoutOutcome.Fallback("layout_unavailable")
        }

        return when (outcome) {
            is LayoutOutcome.Fallback ->
                originalResult(page, outcome.code).copy(v2Outcome = outcome)

            is LayoutOutcome.Success -> {
                val plan = ReflowPlanner().plan(outcome.layout, planOptions, outcome.summary)
                if (plan.fallback == FallbackReason.LAYOUT_FALLBACK) {
                    val code = plan.notes.lastOrNull { it.startsWith("layout_") } ?: "layout_plan_failed"
                    originalResult(page, code, plan.notes).copy(v2Outcome = outcome)
                } else {
                    val audit = LayoutAuditor.audit(outcome.layout, plan, planOptions)
                    if (!audit.ok) {
                        originalResult(page, "layout_audit_failed", audit.issues).copy(v2Outcome = outcome)
                    } else {
                        SmartReflowResult(
                            layout = outcome.layout,
                            plan = plan,
                            projectionAudit = PlanAuditor.audit(outcome.layout, plan),
                            layoutAudit = audit,
                            layoutStats = outcome,
                            fallbackCode = null,
                            v2Outcome = outcome,
                        )
                    }
                }
            }
        }
    }

    /** 只用 v1 的入口（用户没开强化重排 / 单测）。 */
    fun runProjectionOnly(
        analysis: GrayImage,
        page: PageContext,
        planOptions: ReflowOptions,
    ): SmartReflowResult = projectionPath(analysis, page, planOptions)

    private fun projectionPath(
        analysis: GrayImage,
        page: PageContext,
        planOptions: ReflowOptions,
    ): SmartReflowResult {
        val layout = LayoutAnalyzer(analysisOptions).analyze(analysis, page.srcWidth, page.srcHeight)
        val plan = ReflowPlanner().plan(layout, planOptions)
        return SmartReflowResult(
            layout = layout,
            plan = plan,
            projectionAudit = PlanAuditor.audit(layout, plan),
            layoutAudit = null,
            layoutStats = null,
            fallbackCode = null,
            v2Outcome = null,
        )
    }

    /**
     * 强化重排失败：整页显示原图。
     *
     * @param code 诊断码，写进 plan.notes 与 [SmartReflowResult.fallbackCode]。
     * @param extra v2 规划/审计给出的具体原因，原样带进 notes 方便 CLI 排查。
     */
    private fun originalResult(
        page: PageContext,
        code: String,
        extra: List<String> = emptyList(),
    ): SmartReflowResult {
        val layout = PageLayout(
            srcWidth = page.srcWidth,
            srcHeight = page.srcHeight,
            analysisScale = 1f,
            blocks = emptyList(),
            textLeft = 0,
            textRight = 0,
            splitX = -1,
            cleanRatio = 0f,
            medianLineHeight = 0f,
            medianGap = 0f,
            medianPitch = 0f,
            isDark = page.isDark,
            backgroundColor = page.backgroundColor,
            diagnostics = Diagnostics(notes = listOf(code)),
            strategy = LayoutStrategy.DOC_LAYOUT,
        )
        val plan = ReflowPlan(
            outputWidth = 1080,
            height = 0,
            scale = 1f,
            gain = 0f,
            mode = PlanMode.ORIGINAL,
            fallback = FallbackReason.LAYOUT_FALLBACK,
            items = emptyList(),
            backgroundColor = page.backgroundColor,
            isDark = page.isDark,
            notes = listOf("fallback_layout_fallback", code) + extra,
            bodyScale = 1f,
            strategy = LayoutStrategy.DOC_LAYOUT,
        )
        return SmartReflowResult(
            layout = layout,
            plan = plan,
            projectionAudit = PlanAuditor.audit(layout, plan),
            fallbackCode = code,
        )
    }
}

