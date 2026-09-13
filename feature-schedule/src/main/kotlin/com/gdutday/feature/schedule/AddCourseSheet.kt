package com.gdutday.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.CourseColors
import com.gdutday.core.ui.toComposeColor

/**
 * "新增课程" bottom sheet。
 *
 * 与 [CourseEditSheet] 的差异：没有作用范围（自定义课程整体就是用户的），
 * 但多了周次模式的四种填法（本周 / 单周 / 连续范围 / 全学期）。
 * 保存走 [ScheduleViewModel.addCourse]，始终 `force = true` ——
 * 自定义课程允许与已有课程冲突，冲突在网格上并排 / 堆叠呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddCourseSheet(
    form: AddCourseForm,
    currentWeek: Int,
    totalWeeks: Int,
    onFormChange: (AddCourseForm) -> Unit,
    onDismiss: () -> Unit,
    onSave: (AddCourseForm) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val valid = form.isValid(currentWeek, totalWeeks) &&
        (!form.useRealTime || (isValidClock(form.startTime) && isValidClock(form.endTime)))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.schedule_add_title),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text(stringResource(R.string.schedule_edit_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.teacher,
                onValueChange = { onFormChange(form.copy(teacher = it)) },
                label = { Text(stringResource(R.string.schedule_edit_teacher)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.classroom,
                onValueChange = { onFormChange(form.copy(classroom = it)) },
                label = { Text(stringResource(R.string.schedule_edit_classroom)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 星期几
            Column {
                Text(
                    text = stringResource(R.string.schedule_edit_day),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = d == form.dayOfWeek,
                            onClick = { onFormChange(form.copy(dayOfWeek = d)) },
                            label = { Text(dayName(d)) },
                        )
                    }
                }
            }

            // 时间模式：节次 / 具体时间
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !form.useRealTime,
                        onClick = { onFormChange(form.copy(useRealTime = false)) },
                        label = { Text(stringResource(R.string.schedule_edit_time_section)) },
                    )
                    FilterChip(
                        selected = form.useRealTime,
                        onClick = { onFormChange(form.copy(useRealTime = true)) },
                        label = { Text(stringResource(R.string.schedule_edit_time_real)) },
                    )
                }
                if (form.useRealTime) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = form.startTime,
                            onValueChange = { onFormChange(form.copy(startTime = it)) },
                            label = { Text(stringResource(R.string.schedule_edit_start_time)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = form.endTime,
                            onValueChange = { onFormChange(form.copy(endTime = it)) },
                            label = { Text(stringResource(R.string.schedule_edit_end_time)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.schedule_edit_time_real_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionPicker(
                            label = stringResource(R.string.schedule_edit_start_section),
                            value = form.startSection,
                            onPick = { onFormChange(form.copy(startSection = it)) },
                            modifier = Modifier.weight(1f),
                        )
                        SectionPicker(
                            label = stringResource(R.string.schedule_edit_section_count),
                            value = form.sectionCount,
                            onPick = { onFormChange(form.copy(sectionCount = it)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 生效周次
            Column {
                Text(
                    text = stringResource(R.string.schedule_add_week_mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WeekMode.entries.forEach { mode ->
                    AddWeekModeOption(
                        label = weekModeLabel(mode, currentWeek),
                        selected = form.weekMode == mode,
                        onClick = { onFormChange(form.copy(weekMode = mode)) },
                    )
                }
                when (form.weekMode) {
                    WeekMode.SINGLE -> OutlinedTextField(
                        value = form.singleWeek.toString(),
                        onValueChange = { raw ->
                            raw.toIntOrNull()?.let { onFormChange(form.copy(singleWeek = it.coerceIn(1, totalWeeks))) }
                        },
                        label = { Text(stringResource(R.string.schedule_add_single_week, totalWeeks)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    WeekMode.RANGE -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = form.rangeStart.toString(),
                            onValueChange = { raw ->
                                raw.toIntOrNull()?.let { onFormChange(form.copy(rangeStart = it.coerceIn(1, totalWeeks))) }
                            },
                            label = { Text(stringResource(R.string.schedule_add_range_start)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = form.rangeEnd.toString(),
                            onValueChange = { raw ->
                                raw.toIntOrNull()?.let { onFormChange(form.copy(rangeEnd = it.coerceIn(1, totalWeeks))) }
                            },
                            label = { Text(stringResource(R.string.schedule_add_range_end)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    else -> Unit
                }
            }

            // 颜色
            Column {
                Text(
                    text = stringResource(R.string.schedule_edit_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CourseColors.palette.forEach { color ->
                        val selected = color.key == form.colorKey
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color.toComposeColor())
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { onFormChange(form.copy(colorKey = color.key)) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_cancel)) }
                TextButton(
                    enabled = valid,
                    onClick = { onSave(form) },
                ) { Text(stringResource(R.string.schedule_confirm)) }
            }
        }
    }
}

@Composable
private fun AddWeekModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 起始节次 / 节数的滚动选择，与 [CourseEditSheet] 的同名组件保持一致。 */
@Composable
private fun SectionPicker(label: String, value: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(
            selected = false,
            onClick = { expanded = !expanded },
            label = { Text("$value ▾") },
        )
        if (expanded) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (1..com.gdutday.core.model.Course.MAX_SECTION).forEach { n ->
                    Text(
                        text = "$n",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (n == value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                onPick(n)
                                expanded = false
                            }
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun weekModeLabel(mode: WeekMode, currentWeek: Int): String = when (mode) {
    WeekMode.CURRENT -> stringResource(R.string.schedule_add_week_current, currentWeek)
    WeekMode.SINGLE -> stringResource(R.string.schedule_add_week_single)
    WeekMode.RANGE -> stringResource(R.string.schedule_add_week_range)
    WeekMode.ALL -> stringResource(R.string.schedule_add_week_all)
}
