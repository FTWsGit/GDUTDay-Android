package com.gdutday.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.BlockStatus
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.ui.EmptyState
import com.gdutday.core.ui.LocalGdutDayColors
import com.gdutday.core.ui.toComposeColor
import java.time.LocalDate

/**
 * 日视图：单天日程列表（时间轴 + 卡片）。
 *
 * 与周视图共用同一份 [CourseBlock] 数据，只是换一种排布。
 * 之所以不做成"只显示一天的网格"，是因为日视图的价值恰恰在于
 * 让每门课有横向空间展示完整信息，网格反而更挤。
 *
 * 用 [LazyColumn] 而不是 Column：一天可能有十几条，懒加载能省下
 * 不可见条目的测量开销。
 */
@Composable
public fun DayScheduleView(
    blocks: List<CourseBlock>,
    settings: UserSettings,
    date: LocalDate,
    onBlockClick: (CourseBlock) -> Unit,
    modifier: Modifier = Modifier,
    onSwipeDay: ((Int) -> Unit)? = null,
) {
    // 左右滑动切天：页面随手势平移，不足阈值弹回原位，超过阈值触发切天。
    // 空的那天也要能滑走，所以容器修饰符在空状态分支之前算好。
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val containerModifier = if (onSwipeDay != null) {
        val touchSlop = LocalViewConfiguration.current.touchSlop
        modifier
            .graphicsLayer { translationX = drag.value }
            .pointerInput(onSwipeDay) {
                detectHorizontalSwipe(
                    drag = drag,
                    scope = scope,
                    touchSlop = touchSlop,
                    threshold = 48.dp.toPx(),
                    onSwipe = onSwipeDay,
                )
            }
    } else {
        modifier
    }

    if (blocks.isEmpty()) {
        Box(containerModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.schedule_today_empty_title),
                subtitle = stringResource(R.string.schedule_today_empty_subtitle),
            )
        }
        return
    }

    LazyColumn(
        modifier = containerModifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "${date.monthValue} 月 ${date.dayOfMonth} 日 · 共 ${blocks.size} 节",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        // 不显式指定 key：默认用列表下标，天然唯一。
        // 原来的 `course.id + startMinute` 在"同一天两场同时开始的考试"下会重复
        // （考试块 course.id == 0），Compose 抛 "Multiple instances...same key" 直接崩溃。
        // 考试块的 naturalKey 也不含课程名，无法区分并行考试，所以这里交给下标。
        itemsIndexed(blocks) { _, block ->
            DayBlockRow(block = block, settings = settings, onClick = { onBlockClick(block) })
        }
    }
}

@Composable
private fun DayBlockRow(
    block: CourseBlock,
    settings: UserSettings,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val courseBlockColors = LocalGdutDayColors.current.courseBlock
    val shape = RoundedCornerShape(10.dp)

    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.width(48.dp).padding(top = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(block.startClock, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Text(block.endClock, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }

        // 时间轴：一条竖线 + 圆点
        Box(
            modifier = Modifier
                .width(20.dp)
                .padding(horizontal = 6.dp)
                .background(scheme.outlineVariant, RoundedCornerShape(1.dp)),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .background(block.color.toComposeColor(), RoundedCornerShape(1.dp)),
            )
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            shape = shape,
            color = block.color.toComposeColor().copy(alpha = settings.courseBlockAlpha),
            tonalElevation = 0.dp,
            border = if (block.status == BlockStatus.ONGOING) {
                androidx.compose.foundation.BorderStroke(2.dp, courseBlockColors.ongoingBorder)
            } else {
                null
            },
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (block.isExam) Badge(text = stringResource(R.string.schedule_badge_exam))
                    if (block.isCustom) Badge(text = stringResource(R.string.schedule_badge_custom))
                    Text(
                        text = block.course.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                val details = buildList {
                    if (settings.showClassroom && block.course.classroom.isNotBlank()) {
                        add(block.course.classroom)
                    }
                    if (settings.showTeacher && block.course.teacher.isNotBlank()) {
                        add(block.course.teacher)
                    }
                    add(block.sectionLabel)
                }
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(end = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
