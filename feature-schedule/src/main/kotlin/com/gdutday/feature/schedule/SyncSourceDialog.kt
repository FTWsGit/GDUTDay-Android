package com.gdutday.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.SyncSourceType

/**
 * "课表同步源"对话框。
 *
 * MVP 实现（见 plan-001 §3.5）：单选个人/班级 + 手动输入 `bjdm` 与显示名。
 * 后续可升级为解析 `xsbjkbMain.action` 页面里的 `select#bjdm` option 做下拉选择。
 *
 * 选择班级并确认后：保存 bjdm/显示名并把同步源切到班级课表，由 ViewModel 触发同步；
 * 选"个人课表"则把同步源切回个人（已保存的班级信息保留，便于再切回来）。
 */
@Composable
internal fun SyncSourceDialog(
    settings: UserSettings,
    onDismiss: () -> Unit,
    onSelectPersonal: () -> Unit,
    onSelectClass: (bjdm: String, className: String) -> Unit,
) {
    // 对话框内的临时选择：确认前不动真实设置，取消不留痕迹。
    var pendingType by remember { mutableStateOf(settings.syncSourceType) }
    var bjdm by remember { mutableStateOf(settings.classScheduleBjdm) }
    var className by remember { mutableStateOf(settings.classScheduleClassName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_sync_source_title)) },
        text = {
            Column {
                SourceOption(
                    label = stringResource(R.string.schedule_sync_source_personal),
                    selected = pendingType == SyncSourceType.PERSONAL,
                    onClick = { pendingType = SyncSourceType.PERSONAL },
                )
                SourceOption(
                    label = stringResource(R.string.schedule_sync_source_class),
                    selected = pendingType == SyncSourceType.CLASS_SCHEDULE,
                    onClick = { pendingType = SyncSourceType.CLASS_SCHEDULE },
                )
                if (pendingType == SyncSourceType.CLASS_SCHEDULE) {
                    OutlinedTextField(
                        value = bjdm,
                        onValueChange = { bjdm = it.trim() },
                        label = { Text(stringResource(R.string.schedule_sync_source_class_bjdm)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = className,
                        onValueChange = { className = it },
                        label = { Text(stringResource(R.string.schedule_sync_source_class_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.schedule_sync_source_class_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // 选班级但没有 bjdm 时禁止确认：空 bjdm 会被同步流程静默回退成个人课表，
                // 用户会以为自己在同步班级课表，这是危险的静默行为。
                enabled = pendingType == SyncSourceType.PERSONAL || bjdm.isNotBlank(),
                onClick = {
                    if (pendingType == SyncSourceType.CLASS_SCHEDULE) {
                        onSelectClass(bjdm, className)
                    } else {
                        onSelectPersonal()
                    }
                },
            ) { Text(stringResource(R.string.schedule_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_cancel)) }
        },
    )
}

@Composable
private fun SourceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
