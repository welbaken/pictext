package app.pictext.layout

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import app.pictext.core.layout.Detection
import app.pictext.core.layout.LayoutDetector
import app.pictext.core.layout.LayoutInput
import app.pictext.core.layout.Tile
import java.io.File
import java.nio.FloatBuffer

/**
 * Android 侧的 ORT 适配器（`:core` 永远看不到这个类）。
 *
 * 契约来自 `models/doclayout/CONVERSION.md` 的实测：
 *
 * ```text
 * inputs : image[1,3,480,480] float32 + scale_factor[1,2] float32（恒喂 [1,1]）
 * outputs: fetch_name_0 [N,6] float32（class_id, score, x1, y1, x2, y2，N 动态）
 *          fetch_name_1 [1] int32（= N，可交叉校验）
 * 坐标   : 0–480 像素、不裁剪，越界由 DetectionPostprocessor 处理
 * ```
 *
 * 线程：`OrtSession.run` 是线程安全的；App 会同时分析当前页与预取页，
 * 每次调用各自持有张量，不共享可变状态。
 */
class OrtLayoutDetector(
    modelFile: File,
    override val modelInputSize: Int = 480,
    threads: Int = 2,
) : LayoutDetector {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    override val modelName: String get() = "PP-DocLayout-S(ort)"

    init {
        require(modelFile.isFile) { "model not found: ${modelFile.path}" }
        val options = OrtSession.SessionOptions().apply {
            // 手机端 2 线程是预算里的假设（docs/07 §2.5）；再多线程收益有限、发热明显。
            setIntraOpNumThreads(threads.coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, options)
        Log.d(
            "PicText",
            "layout session ready: inputs=${session.inputNames} outputs=${session.outputNames} " +
                "threads=$threads",
        )
    }

    override fun detect(input: LayoutInput, tile: Tile): List<Detection> {
        val dims = longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong())
        val image = OnnxTensor.createTensor(env, FloatBuffer.wrap(input.data), dims)
        val scale = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatArrayOf(1f, 1f)),
            longArrayOf(1, 2),
        )
        try {
            session.run(mapOf("image" to image, "scale_factor" to scale)).use { result ->
                return parseBoxes(pickBoxTensor(result))
            }
        } finally {
            image.close()
            scale.close()
        }
    }

    /** 选 `[N,6]` 那个输出；不按名字硬编码，避免换导出名就崩。 */
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
            out.add(
                Detection(
                    labelId = buf.get(base).toInt(),
                    score = buf.get(base + 1),
                    left = buf.get(base + 2),
                    top = buf.get(base + 3),
                    right = buf.get(base + 4),
                    bottom = buf.get(base + 5),
                ),
            )
        }
        return out
    }

    override fun close() {
        runCatching { session.close() }
    }
}
