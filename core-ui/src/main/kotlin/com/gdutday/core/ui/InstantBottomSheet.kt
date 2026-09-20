package com.gdutday.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 无动画的底部弹层，替代 [androidx.compose.material3.ModalBottomSheet]。
 *
 * `ModalBottomSheet` 的滑入/滑出动画会短暂保留一层遮罩：用户点空白收起后，
 * 在动画结束前再点其它课程会被那层遮罩吃掉，表现为"收起了却点不动下一个"。
 * 这里直接条件渲染，出现和消失都是同一帧完成，收起后立即可再次点击。
 *
 * 交互等价：点遮罩关闭、系统返回键关闭、面板空白处点击不穿透到遮罩。
 */
@Composable
public fun InstantBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 内容多高就多高，但最多占屏幕 90%，避免内部 LazyColumn 把面板撑满整屏。
        val maxSheetHeight = maxHeight * 0.9f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                // 吃掉落在面板空白处的点击，否则会穿透到遮罩把弹层关掉。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 3.dp,
        ) {
            // 底部安全区/输入法：内容整体抬到键盘之上，面板底色延伸到屏幕底。
            Column(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                ),
            ) {
                content()
            }
        }
    }
}
