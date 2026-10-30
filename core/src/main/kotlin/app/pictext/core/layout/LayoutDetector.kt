package app.pictext.core.layout

/**
 * 复用的模型输入缓冲：`channels × height × width` 的 float 张量（NCHW 的“CHW”部分）。
 *
 * 480×480×3 的 float 是 2.7MB；整页 20 个 tile 每次都新分配会制造 GC 压力，
 * 所以分析期只建一个，逐 tile 覆盖写入。
 */
class LayoutInput(
    val width: Int,
    val height: Int,
    val channels: Int = 3,
) {
    val data: FloatArray = FloatArray(channels * width * height)

    val size: Int get() = data.size

    fun plane(c: Int): Int = c * width * height

    fun clear() {
        data.fill(0f)
    }

    /** 该输入是否与给定的模型尺寸匹配。 */
    fun matches(w: Int, h: Int): Boolean = width == w && height == h
}

/**
 * 版面检测器：一个 tile 进，一批 [Detection]（模型输入坐标）出。
 *
 * **实现只允许放在 `:app` / `:cli`**（ORT/native），`:core` 只依赖这个接口，
 * 这样 `:core:test` 永远不需要加载 native 库（docs/07 §2.9g）。
 */
interface LayoutDetector : AutoCloseable {

    /** 模型固定输入边长（官方 PP-DocLayout-S 为 480）。 */
    val modelInputSize: Int

    /** 模型名/版本，用于 `plan.json` 与 `LayoutSummary.model`。 */
    val modelName: String get() = "unknown"

    /**
     * 对一张已经预处理好的 tile 推理；返回模型输入的原始坐标（0..[modelInputSize]）。
     *
     * [tile] 只用于“脚本检测器/调试”把已知的 S 坐标框回填到正确 tile；
     * ORT 实现忽略它即可（推理只依赖 [input]）。
     */
    fun detect(input: LayoutInput, tile: Tile): List<Detection>

    override fun close() {}
}

/** 只用于单测/回归的假检测器：每次返回同一批固定检测框。不参与产品路径。 */
class FakeLayoutDetector(
    override val modelInputSize: Int = 480,
    private val detections: List<Detection> = emptyList(),
    override val modelName: String = "fake",
) : LayoutDetector {
    var calls: Int = 0
        private set

    override fun detect(input: LayoutInput, tile: Tile): List<Detection> {
        calls++
        return detections
    }
}
