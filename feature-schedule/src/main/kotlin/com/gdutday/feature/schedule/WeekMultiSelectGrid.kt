package com.gdutday.feature.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.model.Course

/**
 * `1..totalWeeks` 周次的可视化多选：点哪周选哪周，天然支持任意离散周组合
 * （奇数周、偶数周、随便挑），不再靠"文本框 + `3-6,9,11-13` 区间语法"让用户自己拼。
 *
 * 顶上的快捷按钮直接解决"我每个奇数周才有这门课，要怎么选？"这类问题——
 * 点一下"奇数周"就够了，不用一个个点 13 次。
 *
 * 周次条做成单行横向滚动而不是自动换行网格：这个组件总是被塞进一个已经
 * `verticalScroll` 的外层 [Column]（[AddCourseSheet]、`AdvancedCourseEditScreen`），
 * 同方向嵌套两层可滚动容器容易出问题；单行横向滚动是这份代码库里星期选择器
 * （见 [CourseEditSheet] 的星期几 `Row`）已经在用、验证过安全的写法。
 */
@Composable
internal fun WeekMultiSelectGrid(
    selectedWeeks: Set<Int>,
    totalWeeks: Int,
    onWeeksChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weeks = (1..totalWeeks.coerceIn(1, Course.MAX_WEEK)).toList()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onWeeksChange(weeks.toSet()) }) {
                Text(stringResource(R.string.schedule_weeks_pick_all))
            }
            OutlinedButton(onClick = { onWeeksChange(weeks.filter { it % 2 == 1 }.toSet()) }) {
                Text(stringResource(R.string.schedule_weeks_pick_odd))
            }
            OutlinedButton(onClick = { onWeeksChange(weeks.filter { it % 2 == 0 }.toSet()) }) {
                Text(stringResource(R.string.schedule_weeks_pick_even))
            }
            OutlinedButton(onClick = { onWeeksChange(emptySet()) }) {
                Text(stringResource(R.string.schedule_weeks_pick_clear))
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            weeks.forEach { week ->
                val selected = week in selectedWeeks
                FilterChip(
                    selected = selected,
                    onClick = {
                        onWeeksChange(if (selected) selectedWeeks - week else selectedWeeks + week)
                    },
                    label = { Text("$week", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(
            text = weekSelectionSummary(selectedWeeks),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 选中周次的紧凑摘要，直接复用课程详情页的区间压缩格式（如 "1-8,11,13-16周"）。 */
internal fun weekSelectionSummary(selectedWeeks: Set<Int>): String = Course.formatWeekRanges(selectedWeeks)
