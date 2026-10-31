package app.pictext.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pictext.R

/**
 * 阅读页顶栏：只有一个返回箭头和标题。
 *
 * 右上角不再放“原图/重排”和“⋮”：切换视图由底栏的三档负责（原图 / ×2 / ×4），
 * 关于/版本信息移到了首页。顶栏会随滚动收起，点正文再唤回来。
 */
@Composable
fun ViewerTopBar(
    title: String,
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 强化重排（v2）是否开启；`null` 表示这一版不显示这个开关。 */
    enhancedReflow: Boolean? = null,
    onToggleEnhancedReflow: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                .statusBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val backDescription = stringResource(R.string.back)
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = backDescription },
            ) {
                Text("←", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 强化重排：默认关，所有图片走 v1；打开后这一页/后续页才用 v2（失败显示原图）。
            if (enhancedReflow != null && onToggleEnhancedReflow != null) {
                val stateText = stringResource(
                    if (enhancedReflow) R.string.enhanced_reflow_on else R.string.enhanced_reflow_off,
                )
                TextButton(
                    onClick = onToggleEnhancedReflow,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier.semantics { contentDescription = stateText },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            // 固定 4 字标签 + 第二行开/关，窄屏也不会被挤断。
                            text = stringResource(R.string.enhanced_reflow),
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (enhancedReflow) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (enhancedReflow) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            text = stringResource(
                                if (enhancedReflow) R.string.enhanced_reflow_state_on
                                else R.string.enhanced_reflow_state_off,
                            ),
                            fontSize = 9.sp,
                            maxLines = 1,
                            color = if (enhancedReflow) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
