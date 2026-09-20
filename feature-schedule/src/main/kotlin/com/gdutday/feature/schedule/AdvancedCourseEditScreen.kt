package com.gdutday.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdutday.core.common.CourseColors
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.ui.toComposeColor
import com.gdutday.data.repository.AppContainer
import java.time.LocalTime

/**
 * "高级编辑"：按课程名把这门课本学期出现过的每一行摆出来。三种改法都支持：
 * 整门课一起改名（顶部改名条）、点开某一行单独改（[OccurrenceCard] 展开态）、
 * 或者勾几行 + 填模板只同步某几个字段（[TemplatePanel]）。
 *
 * 用 `ScheduleViewModel.openAdvancedEdit` 打开，替换掉整个 [ScheduleScreen]
 * 而不是弹层——占位内容是一份可能有十几行的清单，不适合塞进 bottom sheet。
 * 写入逻辑、以及模板为什么按字段开关而不是整表单套用，见 [AdvancedCourseEditViewModel] 的 KDoc。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedCourseEditScreen(
    container: AppContainer,
    courseName: String,
    onBack: () -> Unit,
) {
    val viewModel: AdvancedCourseEditViewModel = viewModel(
        key = "advanced_edit_$courseName",
        factory = AdvancedCourseEditViewModel.factory(container, courseName),
    )
    val name by viewModel.courseName.collectAsStateWithLifecycle()
    val occurrences by viewModel.occurrences.collectAsStateWithLifecycle()
    val totalWeeks by viewModel.totalWeeks.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()

    var renameText by rememberSaveable(name) { mutableStateOf(name) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.schedule_advanced_add))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "rename_bar") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.schedule_advanced_rename_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            label = { Text(stringResource(R.string.schedule_edit_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = renameText.isNotBlank() && renameText.trim() != name,
                            onClick = { viewModel.renameAll(renameText) },
                        ) { Text(stringResource(R.string.schedule_confirm)) }
                    }
                }
            }

            item(key = "template_panel") {
                TemplatePanel(
                    selectedCount = selectedIds.size,
                    totalWeeks = totalWeeks,
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                    onApply = viewModel::applyTemplate,
                )
            }

            items(occurrences, key = { it.id }) { course ->
                OccurrenceCard(
                    course = course,
                    totalWeeks = totalWeeks,
                    expanded = expandedId == course.id,
                    selected = course.id in selectedIds,
                    onToggleExpand = { expandedId = if (expandedId == course.id) null else course.id },
                    onToggleSelect = { viewModel.toggleSelected(course.id) },
                    onSave = { edited ->
                        viewModel.editOccurrence(course.id, edited)
                        expandedId = null
                    },
                    onDelete = { viewModel.delete(course.id) },
                    onRestore = if (course.source == CourseSource.OVERRIDE) {
                        { viewModel.restore(course.id) }
                    } else {
                        null
                    },
                )
            }

            if (occurrences.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.schedule_advanced_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddOccurrenceSheet(
            totalWeeks = totalWeeks,
            onDismiss = { showAdd = false },
            onSave = { days, startSection, sectionCount, weeks, classroom, teacher, colorKey ->
                viewModel.addOccurrences(days, startSection, sectionCount, weeks, classroom, teacher, colorKey)
                showAdd = false
            },
        )
    }
}

@Composable
private fun OccurrenceCard(
    course: Course,
    totalWeeks: Int,
    expanded: Boolean,
    selected: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
    onSave: (Course) -> Unit,
    onDelete: () -> Unit,
    onRestore: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpand)
                        .padding(vertical = 8.dp),
                ) {
                    Text(occurrenceSummary(course), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when (course.source) {
                            CourseSource.SCHOOL -> stringResource(R.string.schedule_advanced_badge_school)
                            CourseSource.OVERRIDE -> stringResource(R.string.schedule_advanced_badge_override)
                            else -> stringResource(R.string.schedule_badge_custom)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (onRestore != null) {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.schedule_advanced_restore))
                    }
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.schedule_advanced_delete))
                    }
                }
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    OccurrenceEditor(course = course, totalWeeks = totalWeeks, onSave = onSave)
                }
            }
        }
    }
}

/**
 * 勾几行、填这个模板只同步某几个字段——每个字段自己一个复选框当开关，
 * 关着的字段完全不参与 [onApply]（对应 [OccurrenceTemplate] 里的 `null`）。
 * 这就是这份代码解决"改时间顺带把各不相同的老师都覆盖掉"那个问题的地方，
 * 详细道理见 [AdvancedCourseEditViewModel] 类 KDoc。
 */
@Composable
private fun TemplatePanel(
    selectedCount: Int,
    totalWeeks: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onApply: (OccurrenceTemplate) -> Unit,
) {
    var teacherOn by rememberSaveable { mutableStateOf(false) }
    var teacher by rememberSaveable { mutableStateOf("") }
    var classroomOn by rememberSaveable { mutableStateOf(false) }
    var classroom by rememberSaveable { mutableStateOf("") }
    var dayOn by rememberSaveable { mutableStateOf(false) }
    var dayOfWeek by rememberSaveable { mutableIntStateOf(1) }
    var timeOn by rememberSaveable { mutableStateOf(false) }
    var useRealTime by rememberSaveable { mutableStateOf(false) }
    var startSection by rememberSaveable { mutableIntStateOf(1) }
    var sectionCount by rememberSaveable { mutableIntStateOf(2) }
    var startTime by rememberSaveable { mutableStateOf("08:00") }
    var endTime by rememberSaveable { mutableStateOf("09:30") }
    var colorOn by rememberSaveable { mutableStateOf(false) }
    var colorKey by rememberSaveable { mutableStateOf(CourseColors.DEFAULT.key) }

    val anyFieldOn = teacherOn || classroomOn || dayOn || timeOn || colorOn
    val timeValid = !timeOn || !useRealTime || (isValidClock(startTime) && isValidClock(endTime))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.schedule_advanced_template_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.schedule_advanced_template_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.schedule_advanced_select_all)) }
                TextButton(onClick = onClearSelection) { Text(stringResource(R.string.schedule_advanced_select_clear)) }
                Text(
                    text = stringResource(R.string.schedule_advanced_selected_count, selectedCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TemplateFieldRow(checked = teacherOn, onCheckedChange = { teacherOn = it }, label = stringResource(R.string.schedule_edit_teacher)) {
                OutlinedTextField(
                    value = teacher, onValueChange = { teacher = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TemplateFieldRow(checked = classroomOn, onCheckedChange = { classroomOn = it }, label = stringResource(R.string.schedule_edit_classroom)) {
                OutlinedTextField(
                    value = classroom, onValueChange = { classroom = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TemplateFieldRow(checked = dayOn, onCheckedChange = { dayOn = it }, label = stringResource(R.string.schedule_advanced_field_day)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..7).forEach { d ->
                        FilterChip(selected = d == dayOfWeek, onClick = { dayOfWeek = d }, label = { Text(dayName(d)) })
                    }
                }
            }
            TemplateFieldRow(checked = timeOn, onCheckedChange = { timeOn = it }, label = stringResource(R.string.schedule_advanced_field_time)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = startTime, onValueChange = { startTime = it }, singleLine = true,
                                label = { Text(stringResource(R.string.schedule_edit_start_time)) },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = endTime, onValueChange = { endTime = it }, singleLine = true,
                                label = { Text(stringResource(R.string.schedule_edit_end_time)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = startSection.toString(),
                                onValueChange = { raw -> raw.toIntOrNull()?.let { startSection = it.coerceIn(1, Course.MAX_SECTION) } },
                                singleLine = true,
                                label = { Text(stringResource(R.string.schedule_edit_start_section)) },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = sectionCount.toString(),
                                onValueChange = { raw -> raw.toIntOrNull()?.let { sectionCount = it.coerceIn(1, Course.MAX_SECTION) } },
                                singleLine = true,
                                label = { Text(stringResource(R.string.schedule_edit_section_count)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            TemplateFieldRow(checked = colorOn, onCheckedChange = { colorOn = it }, label = stringResource(R.string.schedule_edit_color)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CourseColors.palette.forEach { color ->
                        val colorSelected = color.key == colorKey
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color.toComposeColor())
                                .border(
                                    width = if (colorSelected) 3.dp else 1.dp,
                                    color = if (colorSelected) {
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

            TextButton(
                enabled = selectedCount > 0 && anyFieldOn && timeValid,
                onClick = {
                    onApply(
                        OccurrenceTemplate(
                            teacher = if (teacherOn) teacher.trim() else null,
                            classroom = if (classroomOn) classroom.trim() else null,
                            colorKey = if (colorOn) colorKey else null,
                            dayOfWeek = if (dayOn) dayOfWeek else null,
                            startSection = if (timeOn && !useRealTime) startSection else null,
                            sectionCount = if (timeOn && !useRealTime) sectionCount else null,
                            startMinute = if (timeOn && useRealTime) clockToMinutes(startTime) else null,
                            endMinute = if (timeOn && useRealTime) clockToMinutes(endTime) else null,
                            useRealTime = if (timeOn) useRealTime else null,
                        ),
                    )
                    teacherOn = false
                    classroomOn = false
                    dayOn = false
                    timeOn = false
                    colorOn = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.schedule_advanced_apply_template, selectedCount)) }
        }
    }
}

/** 一个"复选框开关 + 开着才显示的字段控件"行，[TemplatePanel] 里每个字段共用这个外壳。 */
@Composable
private fun TemplateFieldRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (checked) {
            Column(modifier = Modifier.padding(start = 40.dp, bottom = 4.dp), content = content)
        }
    }
}

/** 展开态的单行编辑器：字段和 [CourseEditSheet] 基本一样，但周次是可视化网格，不锁死"只改当前周"。 */
@Composable
private fun OccurrenceEditor(course: Course, totalWeeks: Int, onSave: (Course) -> Unit) {
    var teacher by rememberSaveable(course.id) { mutableStateOf(course.teacher) }
    var classroom by rememberSaveable(course.id) { mutableStateOf(course.classroom) }
    var dayOfWeek by rememberSaveable(course.id) { mutableIntStateOf(course.dayOfWeek) }
    var useRealTime by rememberSaveable(course.id) { mutableStateOf(course.startMinute >= 0 && course.endMinute >= 0) }
    var startSection by rememberSaveable(course.id) { mutableIntStateOf(course.startSection) }
    var sectionCount by rememberSaveable(course.id) { mutableIntStateOf(course.sectionCount) }
    var startTime by rememberSaveable(course.id) {
        mutableStateOf(if (course.startMinute >= 0) minuteToClock(course.startMinute) else "08:00")
    }
    var endTime by rememberSaveable(course.id) {
        mutableStateOf(if (course.endMinute >= 0) minuteToClock(course.endMinute) else "09:30")
    }
    var colorKey by rememberSaveable(course.id) { mutableStateOf(course.colorKey ?: CourseColors.DEFAULT.key) }
    var weeks by rememberSaveable(course.id) { mutableStateOf(course.weeks) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text(stringResource(R.string.schedule_edit_teacher)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = classroom,
                onValueChange = { classroom = it },
                label = { Text(stringResource(R.string.schedule_edit_classroom)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (1..7).forEach { d ->
                FilterChip(selected = d == dayOfWeek, onClick = { dayOfWeek = d }, label = { Text(dayName(d)) })
            }
        }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startTime,
                    onValueChange = { startTime = it },
                    label = { Text(stringResource(R.string.schedule_edit_start_time)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endTime,
                    onValueChange = { endTime = it },
                    label = { Text(stringResource(R.string.schedule_edit_end_time)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startSection.toString(),
                    onValueChange = { raw -> raw.toIntOrNull()?.let { startSection = it.coerceIn(1, Course.MAX_SECTION) } },
                    label = { Text(stringResource(R.string.schedule_edit_start_section)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = sectionCount.toString(),
                    onValueChange = { raw -> raw.toIntOrNull()?.let { sectionCount = it.coerceIn(1, Course.MAX_SECTION) } },
                    label = { Text(stringResource(R.string.schedule_edit_section_count)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        WeekMultiSelectGrid(
            selectedWeeks = weeks,
            totalWeeks = totalWeeks,
            onWeeksChange = { weeks = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CourseColors.palette.forEach { color ->
                val selected = color.key == colorKey
                Box(
                    modifier = Modifier
                        .size(32.dp)
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                enabled = weeks.isNotEmpty() && (!useRealTime || (isValidClock(startTime) && isValidClock(endTime))),
                onClick = {
                    onSave(
                        course.copy(
                            teacher = teacher.trim(),
                            classroom = classroom.trim(),
                            dayOfWeek = dayOfWeek,
                            startSection = startSection,
                            sectionCount = sectionCount,
                            weeks = weeks,
                            colorKey = colorKey,
                            startMinute = if (useRealTime) clockToMinutes(startTime) else -1,
                            endMinute = if (useRealTime) clockToMinutes(endTime) else -1,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.schedule_confirm)) }
        }
    }
}

/**
 * 新增一次上课时间。星期允许多选——"新建课程可以批量"（调查结论）：
 * 先按同样的教室/老师/周次一次建出好几个星期的行，后面有差异再逐行改（[OccurrenceEditor]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddOccurrenceSheet(
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onSave: (
        days: Set<Int>,
        startSection: Int,
        sectionCount: Int,
        weeks: Set<Int>,
        classroom: String,
        teacher: String,
        colorKey: String,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var days by rememberSaveable { mutableStateOf(setOf(1)) }
    var teacher by rememberSaveable { mutableStateOf("") }
    var classroom by rememberSaveable { mutableStateOf("") }
    var startSection by rememberSaveable { mutableIntStateOf(1) }
    var sectionCount by rememberSaveable { mutableIntStateOf(2) }
    var weeks by rememberSaveable { mutableStateOf((1..totalWeeks.coerceAtLeast(1)).toSet()) }
    var colorKey by rememberSaveable { mutableStateOf(CourseColors.DEFAULT.key) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.schedule_advanced_add_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.schedule_advanced_add_days_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..7).forEach { d ->
                    FilterChip(
                        selected = d in days,
                        onClick = { days = if (d in days) days - d else days + d },
                        label = { Text(dayName(d)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text(stringResource(R.string.schedule_edit_teacher)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = classroom,
                    onValueChange = { classroom = it },
                    label = { Text(stringResource(R.string.schedule_edit_classroom)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startSection.toString(),
                    onValueChange = { raw -> raw.toIntOrNull()?.let { startSection = it.coerceIn(1, Course.MAX_SECTION) } },
                    label = { Text(stringResource(R.string.schedule_edit_start_section)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = sectionCount.toString(),
                    onValueChange = { raw -> raw.toIntOrNull()?.let { sectionCount = it.coerceIn(1, Course.MAX_SECTION) } },
                    label = { Text(stringResource(R.string.schedule_edit_section_count)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            WeekMultiSelectGrid(
                selectedWeeks = weeks,
                totalWeeks = totalWeeks,
                onWeeksChange = { weeks = it },
                modifier = Modifier.fillMaxWidth(),
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_cancel)) }
                TextButton(
                    enabled = days.isNotEmpty() && weeks.isNotEmpty(),
                    onClick = { onSave(days, startSection, sectionCount, weeks, classroom.trim(), teacher.trim(), colorKey) },
                ) { Text(stringResource(R.string.schedule_confirm)) }
            }
        }
    }
}

/**
 * 一行的紧凑摘要：星期 · 时间 · 周次 · 教室 · 老师。
 * 和 feature-settings 的 `MyCoursesScreen.courseSummary` 是同款文案的独立拷贝——
 * 两个模块不共享内部函数，这份代码库里 `SectionPicker` 也是这么各自维护一份的。
 */
private fun occurrenceSummary(course: Course): String = buildList {
    add(dayName(course.dayOfWeek))
    add(
        if (course.startMinute >= 0 && course.endMinute >= 0) {
            "${minuteToClock(course.startMinute)}-${minuteToClock(course.endMinute)}"
        } else if (course.sectionCount == 1) {
            "${course.startSection}节"
        } else {
            "${course.startSection}-${course.startSection + course.sectionCount - 1}节"
        },
    )
    add(course.weeksDisplay)
    if (course.classroom.isNotBlank()) add(course.classroom)
    if (course.teacher.isNotBlank()) add(course.teacher)
}.joinToString(" · ")

/** "08:30" → 510。解析失败返回 -1，和 [Course.startMinute]/[Course.endMinute] 的"未设置"语义一致。 */
private fun clockToMinutes(raw: String): Int {
    val padded = if (raw.contains(':') && raw.split(':').size == 2) raw.padStart(5, '0') else raw
    val time = runCatching { LocalTime.parse(padded) }.getOrNull() ?: return -1
    return time.hour * 60 + time.minute
}
