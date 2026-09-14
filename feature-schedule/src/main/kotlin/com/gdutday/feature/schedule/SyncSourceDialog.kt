package com.gdutday.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.gdutday.data.repository.ClassCascadeOption

/**
 * "课表同步源"对话框。
 *
 * 班级通过级联筛选选择（学院 → 年级 → 专业 → 班级），数据来自教务系统
 * `xsbjkbMain` 主页下拉与 `getFind` 级联查询，见 [ScheduleViewModel.loadClassCascade]。
 *
 * 选择班级并确认后：保存 bjdm/显示名并把同步源切到班级课表，由 ViewModel 触发同步；
 * 选"个人课表"则把同步源切回个人（已保存的班级信息保留，便于再切回来）。
 */
@Composable
internal fun SyncSourceDialog(
    settings: UserSettings,
    cascade: ClassCascadeUiState,
    onDismiss: () -> Unit,
    onSelectPersonal: () -> Unit,
    onSelectClass: (bjdm: String, className: String) -> Unit,
    onSelectCollege: (String) -> Unit,
    onSelectGrade: (String) -> Unit,
    onSelectMajor: (String) -> Unit,
    onSelectClassOption: (ClassCascadeOption) -> Unit,
) {
    // 对话框内的临时选择：确认前不动真实设置，取消不留痕迹。
    // 级联的选择状态在 ViewModel（对话框关闭时整体重置），
    // 这里只读；pendingType 属于纯 UI 状态留在本地。
    var pendingType by remember { mutableStateOf(settings.syncSourceType) }
    // 已保存的班级（切回班级源时直接用它确认，不必重新级联选一遍）
    val savedBjdm = settings.classScheduleBjdm
    val savedClassName = settings.classScheduleClassName
    val picked = cascade.selectedClass

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
                    CascadePicker(
                        cascade = cascade,
                        savedClassLabel = savedClassName.ifBlank { savedBjdm },
                        onSelectCollege = onSelectCollege,
                        onSelectGrade = onSelectGrade,
                        onSelectMajor = onSelectMajor,
                        onSelectClassOption = onSelectClassOption,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // 选班级时：要么刚在级联里点了班级，要么之前保存过班级（直接沿用）。
                // 两者皆无时禁止确认：空 bjdm 会被同步流程静默回退成个人课表，
                // 用户会以为自己在同步班级课表，这是危险的静默行为。
                enabled = pendingType == SyncSourceType.PERSONAL ||
                    picked != null || savedBjdm.isNotBlank(),
                onClick = {
                    if (pendingType == SyncSourceType.CLASS_SCHEDULE) {
                        val bjdm = picked?.code ?: savedBjdm
                        val name = picked?.name ?: savedClassName
                        onSelectClass(bjdm, name)
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

/** 级联筛选区：三个过滤下拉（学院/年级/专业）+ 班级结果列表。 */
@Composable
private fun CascadePicker(
    cascade: ClassCascadeUiState,
    savedClassLabel: String,
    onSelectCollege: (String) -> Unit,
    onSelectGrade: (String) -> Unit,
    onSelectMajor: (String) -> Unit,
    onSelectClassOption: (ClassCascadeOption) -> Unit,
) {
    val meta = cascade.meta
    when {
        cascade.metaLoading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text(
                stringResource(R.string.schedule_sync_source_cascade_loading),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        meta == null -> Text(
            text = cascade.error ?: stringResource(R.string.schedule_sync_source_cascade_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        else -> Column {
            CascadeDropdown(
                label = stringResource(R.string.schedule_sync_source_cascade_college),
                options = meta.colleges,
                selected = cascade.selectedCollege,
                onSelect = onSelectCollege,
            )
            CascadeDropdown(
                label = stringResource(R.string.schedule_sync_source_cascade_grade),
                options = meta.grades,
                selected = cascade.selectedGrade,
                onSelect = onSelectGrade,
            )
            CascadeDropdown(
                label = stringResource(R.string.schedule_sync_source_cascade_major),
                options = cascade.majors,
                selected = cascade.selectedMajor,
                onSelect = onSelectMajor,
            )

            ClassResultList(
                cascade = cascade,
                savedClassLabel = savedClassLabel,
                onSelectClassOption = onSelectClassOption,
            )
        }
    }
}

/** 级联班级结果列表：加载态 / 错误态 / 结果列表（含"沿用已保存班级"入口）。 */
@Composable
private fun ClassResultList(
    cascade: ClassCascadeUiState,
    savedClassLabel: String,
    onSelectClassOption: (ClassCascadeOption) -> Unit,
) {
    when {
        cascade.classesLoading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text(
                stringResource(R.string.schedule_sync_source_cascade_loading),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        cascade.classes.isEmpty() -> Text(
            text = cascade.error ?: stringResource(R.string.schedule_sync_source_cascade_empty),
            style = MaterialTheme.typography.bodySmall,
            color = if (cascade.error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        else -> LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .padding(top = 8.dp),
        ) {
            if (savedClassLabel.isNotBlank() && cascade.selectedClass == null) {
                item(key = "__saved__") {
                    Text(
                        text = stringResource(R.string.schedule_sync_source_keep_saved, savedClassLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            items(cascade.classes, key = { it.code }) { option ->
                ClassRow(
                    label = option.name,
                    selected = cascade.selectedClass?.code == option.code,
                    onClick = { onSelectClassOption(option) },
                )
            }
        }
    }
}

/** 单个过滤下拉：展开后是选项列表。 */
@Composable
private fun CascadeDropdown(
    label: String,
    options: List<ClassCascadeOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val expanded = androidx.compose.runtime.remember(label) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val selectedName = options.firstOrNull { it.code == selected }?.name
        ?: stringResource(R.string.schedule_sync_source_cascade_all)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value },
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = selectedName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Text(
                text = if (expanded.value) "▾" else "▸",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (expanded.value) {
            // "全部"选项：code 为空串表示该维度不过滤
            ClassRow(
                label = stringResource(R.string.schedule_sync_source_cascade_all),
                selected = selected.isBlank(),
                onClick = {
                    onSelect("")
                    expanded.value = false
                },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(options, key = { it.code }) { option ->
                    ClassRow(
                        label = option.name,
                        selected = selected == option.code,
                        onClick = {
                            onSelect(option.code)
                            expanded.value = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
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
