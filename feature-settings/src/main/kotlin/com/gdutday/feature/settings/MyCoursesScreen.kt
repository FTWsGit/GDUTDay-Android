package com.gdutday.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
 * （对教务课程的修改补丁）条目，按学期分组展示。补丁额外提供"还原"操作：
 * 删除补丁行并把被接管的周次还给教务课程。
 *
 * 数据库匹配不到目标的补丁（教务改了课、调了节次）显示"未生效"标记，
 * 用户可删除或自行重新编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCoursesScreen(
    coursesByTerm: Map<Term, List<Course>>,
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
    onDelete: () -> Unit,
    onRestore: (() -> Unit)?,
) {
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
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRestore != null) {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.settings_my_courses_restore))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_my_courses_delete))
                }
            }
        },
    )
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
