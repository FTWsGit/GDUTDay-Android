package com.gdutday.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 横幅的语气，决定图标与配色。 */
public enum class BannerTone {
    /** 提示（校区未设置、需要校准）。 */
    INFO,

    /** 警告，比 INFO 更需要用户注意（如开学日期是推测的）。 */
    WARNING,

    /** 错误（同步失败）。 */
    ERROR,
}

/**
 * 顶部状态横幅。
 *
 * 用途之一是 **同步失败时**：错误必须以非阻塞的方式告知用户，
 * 同时下面的课表继续显示缓存数据（见 `ScheduleUiState.errorMessage` 的注释）。
 * 所以这里用的是内联横幅而不是全屏对话框。
 *
 * 另一个用途是开学日期校准提示 —— 它错了整个周次全错，必须足够显眼。
 * 通过 [tone] = [BannerTone.WARNING] 使用警示配色，并且 [onAction] 直接触发校准。
 */
@Composable
public fun StatusBanner(
    message: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        BannerTone.INFO -> scheme.surfaceVariant to scheme.onSurfaceVariant
        BannerTone.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BannerTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    val icon = when (tone) {
        BannerTone.INFO -> Icons.Filled.Info
        BannerTone.WARNING -> Icons.Filled.WarningAmber
        BannerTone.ERROR -> Icons.Filled.Error
    }

    Surface(color = container, contentColor = content, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(content.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
