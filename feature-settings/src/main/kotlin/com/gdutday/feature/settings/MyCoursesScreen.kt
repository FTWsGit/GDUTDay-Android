package com.gdutday.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Term

/**
 * "我添加/修改的课程"列表页。
 *
 * 汇总全部 [CourseSource.CUSTOM]（用户手动添加）与 [CourseSource.OVERRIDE]
 * （对教务课程的修改补丁）条目，按学期分组展示。
 *
 * 两种来源的收尾操作**不对称**，故意不给同一套按钮：
 * - CUSTOM 只有"删除"——它是用户凭空加的，没有"原始版本"可还原。
 * - OVERRIDE 只有"还原"——直接删补丁行会把它接管的周次也一并从本地抹掉
 *   （那是教务原始数据的一部分，不是这条补丁"拥有"的东西），用户想要的
 *   其实永远是"还原"，给一个额外的"删除"入口只会有人点错、误删了课。
 *
 * [effectiveness] 标注每条 OVERRIDE 补丁当前是否还"打得上"（`Repository.isOverrideEffective`）：
 * 教务下次同步把对应课程整个撤了（调课、退课），补丁就成了孤儿，静静地待在数据库里却
 * 什么都不影响——不主动标出来的话，用户会一直以为自己那条修改还生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCoursesScreen(
    coursesByTerm: Map<Term, List<Course>>,
    effectiveness: Map<Long, Boolean> = emptyMap(),
    onBack: () -> Unit = {},
    onDelete: (Long) -> Unit = {},
    onRestore: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sortedTerms = remember(coursesByTerm) { coursesByTerm.keys.sortedDescending() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_my_courses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (coursesByTerm.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.settings_my_courses_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            sortedTerms.forEach { term ->
                item(key = "term_${term.shortCode}") {
                    Text(
                        text = term.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(
                    count = coursesByTerm[term].orEmpty().size,
                    key = { i -> coursesByTerm[term].orEmpty()[i].id },
                ) { i ->
                    val course = coursesByTerm[term].orEmpty()[i]
                    MyCourseRow(
                        course = course,
                        ineffective = course.source == CourseSource.OVERRIDE && effectiveness[course.id] == false,
                        onDelete = { onDelete(course.id) },
                        onRestore = if (course.source == CourseSource.OVERRIDE) {
                            { onRestore(course.id) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MyCourseRow(
    course: Course,
    ineffective: Boolean,
    onDelete: () -> Unit,
    onRestore: (() -> Unit)?,
) {
    val content = @Composable {
        ListItem(
            headlineContent = {
                Text(course.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    Text(
                        text = courseSummary(course),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when (course.source) {
                        CourseSource.OVERRIDE -> Text(
                            text = stringResource(R.string.settings_my_courses_badge_override),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        else -> Text(
                            text = stringResource(R.string.settings_my_courses_badge_custom),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (ineffective) {
                        Text(
                            text = stringResource(R.string.settings_my_courses_ineffective),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onRestore != null) {
                        // 补丁只提供"还原"，不给单独的"删除"——直接删补丁行会把它接管的周次
                        // 一并从本地抹掉（那是教务原始数据的一部分，不是这条补丁"拥有"的东西），
                        // 用户想要的其实永远是"还原"，给两个入口只会有人点错。
                        IconButton(onClick = onRestore) {
                            Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.settings_my_courses_restore))
                        }
                    } else {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_my_courses_delete))
                        }
                    }
                }
            },
        )
    }

    if (ineffective) {
        // 未生效的补丁用明显的红框圈出来，而不是只在字里行间提一句——
        // 这是"教务已经找不到这门课了"的警告，容易被当成普通说明文字划过去。
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)),
        ) { content() }
    } else {
        content()
    }
}

/** 一条课程的紧凑摘要：星期 · 时间 · 周次 · 教室。 */
internal fun courseSummary(course: Course): String = buildList {
    val day = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        .getOrElse(course.dayOfWeek - 1) { "" }
    val time = if (course.startMinute >= 0 && course.endMinute >= 0) {
        "%02d:%02d-%02d:%02d".format(
            course.startMinute / 60,
            course.startMinute % 60,
            course.endMinute / 60,
            course.endMinute % 60,
        )
    } else {
        if (course.sectionCount == 1) "${course.startSection}节" else "${course.startSection}-${course.endSection}节"
    }
    add("$day · $time")
    add(course.weeksDisplay)
    if (course.classroom.isNotBlank()) add(course.classroom)
}.joinToString(" · ")
