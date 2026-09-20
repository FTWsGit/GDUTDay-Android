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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.CourseColors
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.ui.TimePickerDialog
import com.gdutday.core.ui.toComposeColor
import java.time.LocalTime

/**
 * 课程编辑 bottom sheet。
 *
 * **只编辑用户点开的这一个色块**，不做任何跨行/跨周的批量操作：
 * - 对 [CourseSource.CUSTOM] / [CourseSource.OVERRIDE] 行：保存即直接 upsert
 *   （[ScheduleViewModel.saveEdit] 内部分流）。
 * - 对 [CourseSource.SCHOOL] 行：保存会生成一条只接管**当前这一周**的 [CourseSource.OVERRIDE] 补丁
 *   （固定 `OverrideScope.THIS_WEEK`，不再让用户选作用范围）。
 *
 * 课程名称锁死不可编辑：名字是"同一门课"跨天/跨周聚合的身份标识（见
 * `AdvancedCourseEditScreen`），只有在那边一次性把所有同名行改掉才不会让身份错乱。
 * 想改名字或者批量改同一门课的多次上课时间，点名称下方的入口跳转过去。
 *
 * 时间有两种表达：
 * - 按节次（默认）：起止节次，沿用作息表换算；
 * - 按具体时间：真实起止 `HH:mm`，写入 `startMinute`/`endMinute`，
 *   网格会直接按分钟定位（见 `ScheduleGridBuilder.buildWeek`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseEditSheet(
    course: Course,
    currentWeek: Int,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit,
    onOpenAdvancedEdit: (String) -> Unit = {},
) {
    val isSchool = course.source == CourseSource.SCHOOL
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var teacher by rememberSaveable { mutableStateOf(course.teacher) }
    var classroom by rememberSaveable { mutableStateOf(course.classroom) }
    var dayOfWeek by rememberSaveable { mutableIntStateOf(course.dayOfWeek) }
    var useRealTime by rememberSaveable { mutableStateOf(course.startMinute >= 0 && course.endMinute >= 0) }
    var startSection by rememberSaveable { mutableIntStateOf(course.startSection) }
    var sectionCount by rememberSaveable { mutableIntStateOf(course.sectionCount) }
    var startTime by rememberSaveable {
        mutableStateOf(if (course.startMinute >= 0) minuteToClock(course.startMinute) else "08:00")
    }
    var endTime by rememberSaveable {
        mutableStateOf(if (course.endMinute >= 0) minuteToClock(course.endMinute) else "09:30")
    }
    var colorKey by rememberSaveable { mutableStateOf(course.colorKey ?: CourseColors.DEFAULT.key) }

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
                text = stringResource(R.string.schedule_edit_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Column {
                OutlinedTextField(
                    value = course.name,
                    onValueChange = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.schedule_edit_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenAdvancedEdit(course.name)
                    },
                ) { Text(stringResource(R.string.schedule_edit_rename_hint)) }
            }
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text(stringResource(R.string.schedule_edit_teacher)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = classroom,
                onValueChange = { classroom = it },
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
                            selected = d == dayOfWeek,
                            onClick = { dayOfWeek = d },
                            label = { Text(dayName(d)) },
                        )
                    }
                }
            }

            // 时间模式
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useRealTime,
                        onClick = { useRealTime = false },
                        label = { Text(stringResource(R.string.schedule_edit_time_section)) },
                    )
                    FilterChip(
                        selected = useRealTime,
                        onClick = { useRealTime = true },
                        label = { Text(stringResource(R.string.schedule_edit_time_real)) },
                    )
                }
                if (useRealTime) {
                    // picking：null=没开着；true=在选开始时间；false=在选结束时间。
                    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimeField(
                            label = stringResource(R.string.schedule_edit_start_time),
                            value = startTime,
                            onClick = { picking = true },
                            modifier = Modifier.weight(1f),
                        )
                        TimeField(
                            label = stringResource(R.string.schedule_edit_end_time),
                            value = endTime,
                            onClick = { picking = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.schedule_edit_time_real_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 系统时间选择器，不用再手敲 HH:mm、操心冒号和补零。
                    picking?.let { isStart ->
                        val currentText = if (isStart) startTime else endTime
                        val initial = runCatching { LocalTime.parse(currentText) }.getOrDefault(LocalTime.of(8, 0))
                        TimePickerDialog(
                            initial = initial,
                            confirmLabel = stringResource(R.string.schedule_confirm),
                            dismissLabel = stringResource(R.string.schedule_cancel),
                            onConfirm = { time ->
                                val formatted = "%02d:%02d".format(time.hour, time.minute)
                                if (isStart) startTime = formatted else endTime = formatted
                                picking = null
                            },
                            onDismiss = { picking = null },
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionPicker(
                            label = stringResource(R.string.schedule_edit_start_section),
                            value = startSection,
                            onPick = { startSection = it },
                            modifier = Modifier.weight(1f),
                        )
                        SectionPicker(
                            label = stringResource(R.string.schedule_edit_section_count),
                            value = sectionCount,
                            onPick = { sectionCount = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 教务课程：提示这条编辑只会覆盖当前这一周，不影响其它周的同一门课。
            if (isSchool) {
                Text(
                    text = stringResource(R.string.schedule_edit_this_week_only, currentWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        val selected = color.key == colorKey
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
                                .clickable { colorKey = color.key },
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
                    onClick = {
                        val edited = buildEditedCourse(
                            original = course,
                            teacher = teacher.trim(),
                            classroom = classroom.trim(),
                            dayOfWeek = dayOfWeek,
                            useRealTime = useRealTime,
                            startSection = startSection,
                            sectionCount = sectionCount,
                            startTime = startTime,
                            endTime = endTime,
                            colorKey = colorKey,
                            isSchool = isSchool,
                            currentWeek = currentWeek,
                        )
                        onSave(edited)
                    },
                ) { Text(stringResource(R.string.schedule_confirm)) }
            }
        }
    }
}

/** 起止时间选一个只读展示框，点开系统 [TimePickerDialog]。和作息表编辑页同一套交互。 */
@Composable
private fun TimeField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(value)
        }
    }
}

/** 起始节次 / 节数的选择，标准下拉框——点开菜单选一个数字，不用再自己滑一排数字找。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionPicker(label: String, value: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..Course.MAX_SECTION).forEach { n ->
                DropdownMenuItem(
                    text = { Text("$n") },
                    onClick = {
                        onPick(n)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ============================================================================
// 纯函数（单元测试直接覆盖）
// ============================================================================

internal fun minuteToClock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

internal fun dayName(day: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(day - 1) { "" }

/**
 * 把编辑页的各输入拼成待保存的 [Course]。名称不在参数里——这个 sheet 从不改名字
 * （见文件顶部 KDoc），产物的 `name` 恒为 `original.name`。
 *
 * `weeks` 的语义由调用方决定：教务课程 = 固定只覆盖 [currentWeek] 这一周
 * （对应 `saveSchoolOverride` 的 `OverrideScope.THIS_WEEK`）；
 * 自定义课程 = 全部保留原有周次（这个 sheet 不拆分自定义课程的周次）。
 */
internal fun buildEditedCourse(
    original: Course,
    teacher: String,
    classroom: String,
    dayOfWeek: Int,
    useRealTime: Boolean,
    startSection: Int,
    sectionCount: Int,
    startTime: String,
    endTime: String,
    colorKey: String,
    isSchool: Boolean,
    currentWeek: Int,
): Course {
    val parseTime: (String) -> LocalTime? = { raw ->
        runCatching { LocalTime.parse(if (raw.contains(':') && raw.split(':').size == 2) raw.padStart(5, '0') else raw) }
            .getOrNull()
    }
    val startMin = if (useRealTime) parseTime(startTime)?.let { it.hour * 60 + it.minute } ?: -1 else -1
    val endMin = if (useRealTime) parseTime(endTime)?.let { it.hour * 60 + it.minute } ?: -1 else -1

    val weeks: Set<Int> = if (isSchool) setOf(currentWeek) else original.weeks

    return original.copy(
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        sectionCount = sectionCount,
        weeks = weeks,
        colorKey = colorKey,
        startMinute = startMin,
        endMinute = endMin,
        // 补丁字段只在生成 OVERRIDE 行时由 saveSchoolOverride 填写，这里清空避免脏值
        overrideScope = null,
        overrideTargetNaturalKey = null,
        overrideWeeks = emptySet(),
    )
}
