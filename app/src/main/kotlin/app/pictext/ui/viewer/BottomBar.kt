package app.pictext.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pictext.R
import kotlin.math.roundToInt

/**
 * 底栏：三档（原图 / ×2 / ×4）+ 阅读进度。
 *
 * ×2 = 按算法重排；×4 = 在 ×2 的倍率上再放大一倍（宽行允许再切一段）。
 * 两档都跳过“长行不足 / 收益不足 / 多栏”这些自动判断，用户点了就放大。
 */
@Composable
fun ViewerBottomBar(
    visible: Boolean,
    level: ViewerLevel,
    progress: Float,
    onLevelSelected: (ViewerLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow {
                    val levels = listOf(ViewerLevel.ORIGINAL, ViewerLevel.X2, ViewerLevel.X4)
                    levels.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = level == item,
                            onClick = { onLevelSelected(item) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                        ) {
                            Text(
                                text = stringResource(levelLabel(item)),
                                fontSize = 14.sp,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.progress_percent, (progress * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
    }
}

fun levelLabel(level: ViewerLevel): Int = when (level) {
    ViewerLevel.ORIGINAL -> R.string.level_original
    ViewerLevel.X2 -> R.string.level_x2
    ViewerLevel.X4 -> R.string.level_x4
}