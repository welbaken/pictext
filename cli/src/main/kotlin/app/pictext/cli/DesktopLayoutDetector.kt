package app.pictext.cli

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.pictext.core.layout.Detection
import app.pictext.core.layout.LayoutDetector
import app.pictext.core.layout.LayoutInput
import app.pictext.core.layout.Tile
import java.io.File
import java.nio.FloatBuffer

/**
 * 桌面 ORT 适配器：`PP-DocLayout-S.onnx`（opset 13）。
 *
 * 契约来自 `models/doclayout/CONVERSION.md` 的实测（不是猜的）：
 *
 * ```text
 * inputs : image [1,3,480,480] float32 (NCHW)
 *          scale_factor [1,2] float32        // 实测 out = box_480 / scale_factor
 * outputs: fetch_name_0 [N,6] float32       // class_id, score, x1, y1, x2, y2（已含图内 NMS）
 *          fetch_name_1 [1] int32            // = N
 * 坐标   : 0–480 像素，非归一化，且**不裁剪**（会出现 x2 > 480）
 * ```
 *
 * 三个必须遵守的点：
 * 1. `scale_factor` 恒喂 `[1,1]`：官方语义没逐行核对，喂 1 就不会有歧义；
 * 2. `N` 是动态的（白图 7，截图可顶到 keep_top_k=100），**按第 0 维实际长度解析**；
 * 3. 图内 NMS 阈值只有 0.025/0.6，产品过滤（0.4/0.5）由 `DetectionPostprocessor` 做。
 */
class DesktopLayoutDetector(
    modelPath: File,
    override val modelInputSize: Int = 480,
) : LayoutDetector {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    override val modelName: String get() = "PP-DocLayout-S(ort)"

    init {
        require(modelPath.isFile) { "model not found: ${modelPath.path}" }
        val options = OrtSession.SessionOptions()
        // 手机预算按 2 线程估；桌面默认给 4，接近中端机的相对量级。
        options.setIntraOpNumThreads(4)
        session = env.createSession(modelPath.absolutePath, options)
    }

    /** 输出名/形状，CLI 启动时打印一次，便于确认契约没变。 */
    fun describe(): String {
        val ins = session.inputNames.joinToString(", ")
        val outs = session.outputInfo.entries.joinToString(", ") { "${it.key}: ${it.value.info}" }
        return "inputs=[$ins] outputs=[$outs]"
    }

    override fun detect(input: LayoutInput, tile: Tile): List<Detection> {
        val dims = longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong())
        val image = OnnxTensor.createTensor(env, FloatBuffer.wrap(input.data), dims)
        val scale = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f, 1f)), longArrayOf(1, 2))
        try {
            session.run(mapOf("image" to image, "scale_factor" to scale)).use { result ->
                val boxTensor = pickBoxTensor(result)
                return parseBoxes(boxTensor)
            }
        } finally {
            image.close()
            scale.close()
        }
    }

    /** 选 [N,6] 那个输出；不按名字硬编码，避免将来换导出名就崩。 */
    private fun pickBoxTensor(result: OrtSession.Result): OnnxTensor {
        var fallback: OnnxTensor? = null
        for ((_, value) in result) {
            if (value !is OnnxTensor) continue
            val shape = value.info.shape
            if (shape.size == 2 && shape[1] == 6L) return value
            if (fallback == null) fallback = value
        }
        return fallback ?: error("ONNX produced no tensor output")
    }

    private fun parseBoxes(tensor: OnnxTensor): List<Detection> {
        val shape = tensor.info.shape
        if (shape.size != 2 || shape[1] != 6L) {
            error("unexpected box tensor shape: ${shape.contentToString()}")
        }
        val n = shape[0].toInt()
        if (n <= 0) return emptyList()
        val buf: FloatBuffer = tensor.floatBuffer
        val out = ArrayList<Detection>(n)
        for (i in 0 until n) {
            val base = i * 6
            val labelId = buf.get(base).toInt()
            val score = buf.get(base + 1)
            val x1 = buf.get(base + 2)
            val y1 = buf.get(base + 3)
            val x2 = buf.get(base + 4)
            val y2 = buf.get(base + 5)
            // 越界框是正常现象（模型不裁剪），交给 DetectionPostprocessor 按有效区裁。
            out.add(Detection(labelId, score, x1, y1, x2, y2))
        }
        return out
    }

    override fun close() {
        session.close()
    }
}
