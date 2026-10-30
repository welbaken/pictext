package app.pictext.core.layout

import app.pictext.core.model.LineRole
import app.pictext.core.model.RectI
import app.pictext.core.model.RegionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2-10：标签映射、NMS、坐标映射、预处理。 */
class LabelAndDetectionTest {

    private val labels = ModelLabels()
    private val contract = ModelContract()

    // ---- 标签映射 ----

    @Test
    fun `every official label id maps to a known kind`() {
        assertEquals(23, ModelLabels.LABELS.size)
        assertEquals(23, labels.size)
        for (id in 0 until labels.size) {
            val kind = labels.kindOfId(id)
            assertTrue("id=$id label=${labels.labelOf(id)}", kind != RegionKind.OTHER)
        }
        assertTrue(labels.unmappedLabels().isEmpty())
    }

    @Test
    fun `label ids follow the model config order`() {
        assertEquals("paragraph_title", labels.labelOf(0))
        assertEquals("image", labels.labelOf(1))
        assertEquals("text", labels.labelOf(2))
        assertEquals("number", labels.labelOf(3))
        assertEquals("table", labels.labelOf(8))
        assertEquals("reference", labels.labelOf(10))
        assertEquals("doc_title", labels.labelOf(11))
        assertEquals("header", labels.labelOf(13))
        assertEquals("aside_text", labels.labelOf(22))
        assertEquals(RegionKind.TITLE, labels.kindOfId(0))
        assertEquals(RegionKind.FIGURE, labels.kindOfId(1))
        assertEquals(RegionKind.TEXT, labels.kindOfId(2))
        assertEquals(RegionKind.CHROME, labels.kindOfId(3))
        assertEquals(RegionKind.TABLE, labels.kindOfId(8))
        assertEquals(RegionKind.CAPTION, labels.kindOfId(22))
    }

    @Test
    fun `unknown label ids degrade to OTHER without crashing`() {
        assertEquals(RegionKind.OTHER, labels.kindOfId(99))
        assertEquals("unknown_99", labels.labelOf(99))
        assertEquals(RegionKind.OTHER, ModelLabels.kindOf("no_such_label"))
        assertTrue(!labels.isKnown(-1))
    }

    @Test
    fun `line role priors follow the region kind`() {
        assertEquals(LineRole.BODY, ModelLabels.roleOf(RegionKind.TEXT))
        assertEquals(LineRole.HEADING, ModelLabels.roleOf(RegionKind.TITLE))
        assertEquals(LineRole.CAPTION, ModelLabels.roleOf(RegionKind.CAPTION))
        assertEquals(LineRole.UI, ModelLabels.roleOf(RegionKind.CHROME))
        assertEquals(null, ModelLabels.roleOf(RegionKind.FIGURE))
    }

    // ---- 检测后处理 ----

    private fun tile(src: RectI = RectI(0, 0, 1080, 1080)): Tile = Tile(
        index = 0,
        row = 0,
        col = 0,
        src = src,
        innerWidth = 480,
        innerHeight = 480,
        padX = 0,
        padY = 0,
        modelInput = 480,
    )

    @Test
    fun `low score detections are dropped`() {
        val post = DetectionPostprocessor()
        val out = post.postprocess(
            tile(),
            listOf(
                Detection(2, 0.39f, 0f, 0f, 100f, 100f),
                Detection(2, 0.41f, 200f, 0f, 300f, 100f),
            ),
            RectI.ofSize(1080, 1080),
        )
        assertEquals(1, out.size)
        // 模型坐标 200 映射回 S 是 200 × 2.25 = 450
        assertEquals(450, out[0].bounds.left)
    }

    @Test
    fun `same class overlap keeps the higher score`() {
        val post = DetectionPostprocessor()
        val out = post.postprocess(
            tile(),
            listOf(
                Detection(2, 0.5f, 0f, 0f, 100f, 100f),
                Detection(2, 0.9f, 2f, 2f, 102f, 102f),
            ),
            RectI.ofSize(1080, 1080),
        )
        assertEquals(1, out.size)
        assertEquals(0.9f, out[0].score, 0.0001f)
    }

    @Test
    fun `different classes do not suppress each other`() {
        val post = DetectionPostprocessor()
        val out = post.postprocess(
            tile(),
            listOf(
                Detection(1, 0.8f, 0f, 0f, 100f, 100f),   // image
                Detection(2, 0.7f, 0f, 0f, 100f, 100f),   // text（图注叠在图上是合法的）
            ),
            RectI.ofSize(1080, 1080),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `boxes are clipped to the page and mapped with the tile scale`() {
        val post = DetectionPostprocessor()
        val out = post.postprocess(
            tile(),
            listOf(Detection(2, 0.9f, 0f, 0f, 100f, 100f)),
            RectI.ofSize(1080, 1080),
        )
        assertEquals(1, out.size)
        // 1080/480 = 2.25
        assertEquals(0, out[0].bounds.left)
        assertEquals(225, out[0].bounds.right)
        assertEquals(225, out[0].bounds.bottom)
        assertEquals("text", out[0].label)
        assertEquals(RegionKind.TEXT, out[0].kind)
    }

    @Test
    fun `detections outside the valid area are dropped`() {
        val padded = Tile(0, 0, 0, RectI(0, 0, 400, 400), 400, 400, 40, 40, 480)
        val post = DetectionPostprocessor()
        // 全在左上角 padding 里
        val out = post.postprocess(padded, listOf(Detection(2, 0.9f, 0f, 0f, 20f, 20f)), RectI.ofSize(400, 400))
        assertTrue(out.isEmpty())
    }

    // ---- 几何工具 ----

    @Test
    fun `iou and containment helpers behave`() {
        val a = RectI(0, 0, 100, 100)
        assertEquals(1f, iou(a, a), 0.0001f)
        assertEquals(0f, iou(a, RectI(200, 200, 300, 300)), 0.0001f)
        assertEquals(0.5f, iou(a, RectI(0, 0, 50, 100)), 0.0001f)
        assertEquals(1f, overlapOfBInA(a, RectI(10, 10, 50, 50)), 0.0001f)
        // 横向只重叠 10px / 较窄者 100px = 0.1
        assertEquals(0.1f, overlapOfNarrower(a, RectI(90, 0, 190, 100)), 0.0001f)
    }

    // ---- 预处理 ----

    @Test
    fun `preprocess writes a full CHW tensor with contract normalisation`() {
        val t = tile()
        val argb = IntArray(480 * 480) { 0xFFFFFFFF.toInt() }
        val input = LayoutInput(480, 480, 3)
        LayoutPreprocessor(contract).process(argb, 480, 480, t, input)
        assertEquals(3 * 480 * 480, input.size)
        val expectedR = (1f - 0.485f) / 0.229f
        assertEquals(expectedR, input.data[0], 0.01f)
        val expectedG = (1f - 0.456f) / 0.224f
        assertEquals(expectedG, input.data[480 * 480], 0.01f)
    }

    @Test
    fun `preprocess is deterministic for the same tile`() {
        val t = tile()
        val argb = IntArray(480 * 480) { i -> if (i % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val a = LayoutInput(480, 480, 3)
        val b = LayoutInput(480, 480, 3)
        val pre = LayoutPreprocessor(contract)
        pre.process(argb, 480, 480, t, a)
        pre.process(argb, 480, 480, t, b)
        assertTrue(a.data.contentEquals(b.data))
    }

    @Test
    fun `preprocess fills padding with the contract pad value`() {
        val padded = Tile(0, 0, 0, RectI(0, 0, 400, 400), 400, 400, 40, 40, 480)
        val input = LayoutInput(480, 480, 3)
        // 深色 tile：有效区外必须还是 padValue（114）
        LayoutPreprocessor(contract).process(IntArray(400 * 400) { 0xFF000000.toInt() }, 400, 400, padded, input)
        val padNorm = contract.normalize(0, 114f)
        assertEquals(padNorm, input.data[0], 0.001f)
        // 有效区中心是黑色 → 归一化后是 (0 - mean)/std
        val center = 240 * 480 + 240
        assertEquals((0f - 0.485f) / 0.229f, input.data[center], 0.01f)
    }

    @Test
    fun `gray output matches the colour input for a flat tile`() {
        val t = tile()
        val argb = IntArray(480 * 480) { 0xFF808080.toInt() }
        val input = LayoutInput(480, 480, 3)
        val gray = ByteArray(480 * 480)
        LayoutPreprocessor(contract).process(argb, 480, 480, t, input, gray)
        assertEquals(0x80, gray[0].toInt() and 0xFF)
    }

    @Test
    fun `contract defaults match the official inference config`() {
        assertEquals(480, contract.modelInput)
        assertEquals(false, contract.keepRatio)
        assertEquals(listOf(0.485f, 0.456f, 0.406f), contract.mean)
        assertEquals(listOf(0.229f, 0.224f, 0.225f), contract.std)
        assertNotNull(contract.labels())
        assertEquals(23, contract.labels().size)
    }
}
