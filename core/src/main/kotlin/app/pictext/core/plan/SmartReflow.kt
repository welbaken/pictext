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
 * v2 → v1 → 原图的三级回退编排（docs/07 §4.9）。
 *
 * ```text
 * 模型可用 → LayoutPipeline（区域/顺序/细化/覆盖审计）
 *          → ReflowPlanner v2 → LayoutAuditor
 *          → 任一失败：重跑 v1（LayoutAnalyzer + ReflowPlanner v1）
 * 竖排 → ORIGINAL（v1 也救不了，整块看原图）
 * ```
 *
 * 这个类是**纯 Kotlin**：检测器/采样器都是接口，所以 core 的单测可以用假检测器跑完整链路，
 * 不需要 native 库（docs/07 §2.9g）。回退是正常路径，不是错误。
 */
class SmartReflow(
    private val analysisOptions: AnalysisOptions = AnalysisOptions(),
    private val layoutOptions: LayoutOptions = LayoutOptions(),
    private val contract: ModelContract = ModelContract(),
) {
    private val pipeline = LayoutPipeline(layoutOptions, analysisOptions, contract)

    /**
     * @param analysis v1 用的全页灰度分析图（长边 ≤1600）。v2 不用它，但回退需要。
     * @param detector v2 检测器；`null` 表示这次只用 v1（模型缺失/设置关闭）。
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
        val outcome = if (detector != null && sampler != null && regionSampler != null) {
            pipeline.analyze(sampler, detector, regionSampler, page, onTile)
        } else {
            LayoutOutcome.Fallback("layout_unavailable")
        }

        return when (outcome) {
            is LayoutOutcome.Fallback -> {
                if (outcome.useOriginal) {
                    originalResult(page, outcome.code).copy(v2Outcome = outcome)
                } else {
                    projectionPath(analysis, page, planOptions, outcome.code, v2Outcome = outcome)
                }
            }

            is LayoutOutcome.Success -> {
                val plan = ReflowPlanner().plan(outcome.layout, planOptions, outcome.summary)
                if (plan.fallback == FallbackReason.LAYOUT_FALLBACK) {
                    val code = plan.notes.lastOrNull { it.startsWith("layout_") } ?: "layout_plan_failed"
                    projectionPath(analysis, page, planOptions, code, plan.notes, outcome)
                } else {
                    val audit = LayoutAuditor.audit(outcome.layout, plan, planOptions)
                    if (audit.ok) {
                        SmartReflowResult(
                            layout = outcome.layout,
                            plan = plan,
                            projectionAudit = PlanAuditor.audit(outcome.layout, plan),
                            layoutAudit = audit,
                            layoutStats = outcome,
                            fallbackCode = null,
                            v2Outcome = outcome,
                        )
                    } else {
                        projectionPath(analysis, page, planOptions, "layout_audit_failed", audit.issues, outcome)
                    }
                }
            }
        }
    }

    /** 只用 v1 的入口（模型不可用 / 用户关掉 v2 / 单测）。 */
    fun runProjectionOnly(
        analysis: GrayImage,
        page: PageContext,
        planOptions: ReflowOptions,
    ): SmartReflowResult = projectionPath(analysis, page, planOptions, "layout_unavailable")

    private fun projectionPath(
        analysis: GrayImage,
        page: PageContext,
        planOptions: ReflowOptions,
        code: String,
        extra: List<String> = emptyList(),
        v2Outcome: LayoutOutcome? = null,
    ): SmartReflowResult {
        val layout = LayoutAnalyzer(analysisOptions).analyze(analysis, page.srcWidth, page.srcHeight)
        val plan = ReflowPlanner().plan(layout, planOptions)
        return SmartReflowResult(
            layout = layout,
            plan = plan.copy(notes = plan.notes + "layout_fallback=$code" + extra),
            projectionAudit = PlanAuditor.audit(layout, plan),
            layoutAudit = null,
            layoutStats = null,
            fallbackCode = code,
            v2Outcome = v2Outcome,
        )
    }

    private fun originalResult(page: PageContext, code: String): SmartReflowResult {
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
            notes = listOf("fallback_layout_fallback", code),
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
