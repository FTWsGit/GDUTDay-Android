package com.gdutday.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.common.CourseColors
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.ui.toComposeColor

/**
 * 课程详情 bottom sheet。
 *
 * 展示色块里放不下的全部信息：完整课程名、老师、教室、周次列表、
 * 教学内容、教学班、课程编号。并允许改色和删除。
 *
 * 考试条目（`course.id == 0`，由考试安排派生）没有可删除的数据库记录，
 * 所以不显示删除入口；改色对它也没有意义（没有 Course 行可写），一并隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailSheet(
    block: CourseBlock,
    settings: UserSettings,
    onDismiss: () -> Unit,
    onPickColor: (String) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
) {
    val course = block.course
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isExam = block.isExam
    val editable = !isExam && course.id != 0L

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (isExam) {
                    Text(
                        text = stringResource(R.string.schedule_badge_exam),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (block.isCustom) {
                    Text(
                        text = stringResource(R.string.schedule_badge_custom),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            val rows = buildList {
                if (course.teacher.isNotBlank()) {
                    add(stringResource(R.string.schedule_detail_teacher) to course.teacher)
                }
                if (course.classroom.isNotBlank()) {
                    add(stringResource(R.string.schedule_detail_classroom) to course.classroom)
                }
                add(
                    stringResource(R.string.schedule_detail_time) to
                        "${block.sectionLabel} · ${block.startClock}-${block.endClock}",
                )
                add(stringResource(R.string.schedule_detail_weeks) to course.weeksDisplay)
                if (course.teachingClass.isNotBlank()) {
                    add(stringResource(R.string.schedule_detail_teaching_class) to course.teachingClass)
                }
                if (course.courseCode.isNotBlank()) {
                    add(stringResource(R.string.schedule_detail_course_code) to course.courseCode)
                }
            }
            rows.forEach { (label, value) ->
                DetailRow(label = label, value = value)
            }

            if (course.description.isNotBlank()) {
                Text(
                    text = stringResource(R.string.schedule_detail_content),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = course.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (editable) {
                Text(
                    text = stringResource(R.string.schedule_detail_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                ColorPicker(
                    selectedKey = block.color.key,
                    onPickColor = onPickColor,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.schedule_detail_edit),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.schedule_detail_delete),
                            modifier = Modifier.padding(start = 6.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ColorPicker(selectedKey: String, onPickColor: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(CourseColors.palette, key = { it.key }) { color ->
            val selected = color.key == selectedKey
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
                    .clickable { onPickColor(color.key) },
            )
        }
    }
}
