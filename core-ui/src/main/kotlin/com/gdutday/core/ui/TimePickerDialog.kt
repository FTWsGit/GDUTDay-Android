package com.gdutday.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalTime

/**
 * 系统风格的时间选择弹窗，包一层 [Dialog]：Material3 没有现成的 `TimePickerDialog`，
 * `AlertDialog` 的内容区又放不下转盘式 [TimePicker]（会被压扁裁切），所以自己包一层。
 *
 * 用来替换"手动输入 HH:mm"的文本框——不管是自定义作息表还是课程编辑页的具体时间，
 * 用户选时间不应该自己敲文字、还要操心格式和冒号。
 *
 * @param confirmLabel/dismissLabel 按钮文案由调用方传入自己模块的字符串资源，
 *   这个模块本身没有 res，不内置文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun TimePickerDialog(
    initial: LocalTime,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(dismissLabel) }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}
