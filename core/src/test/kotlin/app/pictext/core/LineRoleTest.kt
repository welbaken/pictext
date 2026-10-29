package app.pictext.core

import app.pictext.core.analyze.BlockClassifier
import app.pictext.core.analyze.RowClassifier
import app.pictext.core.model.LineRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-2 的行级角色：BODY / HEADING / CAPTION / UI。
 *
 * 判定顺序是“UI 优先”——把头像/按钮行误判成正文并切开，会把图标裁成两半，
 * 比“少切一行”严重得多。
 */
class LineRoleTest {

    private val classifier = RowClassifier()

    private fun roleOf(
        mask: BooleanArray,
        w: Int,
        y0: Int,
        y1: Int,
        x0: Int,
        x1: Int,
        medH: Float,
    ): LineRole {
        val band = bandOf(mask, w, y0, y1, x0, x1)
        val vrun = BlockClassifier().verticalRuns(mask, w, mask.size / w)
        val f = classifier.features(mask, w, band, medH, vrun, w)
        return classifier.role(f, medH)
    }

    @Test
    fun `normal body line is BODY`() {
        val w = 400
        val h = 60
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 22, 20, 380)
        assertEquals(LineRole.BODY, roleOf(mask, w, 10, 32, 20, 380, 22f))
    }

    @Test
    fun `large title is HEADING`() {
        val w = 400
        val h = 80
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 34, 20, 380)
        assertEquals(LineRole.HEADING, roleOf(mask, w, 10, 44, 20, 380, 22f))
    }

    @Test
    fun `small caption is CAPTION`() {
        val w = 400
        val h = 60
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 13, 20, 140)
        assertEquals(LineRole.CAPTION, roleOf(mask, w, 10, 23, 20, 140, 22f))
    }

    @Test
    fun `action row with wide gaps is UI`() {
        // 评论 / 转发 / 点赞 / 收藏：4 组图标+数字，组间距 60px。
        val w = 400
        val h = 60
        val mask = BooleanArray(w * h)
        var x = 10
        while (x + 30 <= 390) {
            maskFill(mask, w, 14, 32, x, x + 10)
            maskFill(mask, w, 16, 26, x + 16, x + 30)
            x += 90
        }
        assertEquals(LineRole.UI, roleOf(mask, w, 14, 32, 10, 360, 22f))
    }

    @Test
    fun `avatar header is UI`() {
        val w = 400
        val h = 80
        val mask = BooleanArray(w * h)
        // 36×36 头像
        maskFill(mask, w, 6, 42, 10, 46)
        // 头像右侧两行小字
        drawGlyphRow(mask, w, 8, 12, 56, 300)
        drawGlyphRow(mask, w, 24, 12, 56, 240)
        assertEquals(LineRole.UI, roleOf(mask, w, 6, 42, 10, 300, 22f))
    }

    @Test
    fun `bold wide glyphs are not UI`() {
        // 粗体大标题的列墨很高，容易被“实心列”误判成头像；用高度比与宽度比区分。
        val w = 400
        val h = 80
        val mask = BooleanArray(w * h)
        drawGlyphRow(mask, w, 10, 30, 20, 380)
        val role = roleOf(mask, w, 10, 40, 20, 380, 22f)
        assertEquals(LineRole.HEADING, role)
    }
}
