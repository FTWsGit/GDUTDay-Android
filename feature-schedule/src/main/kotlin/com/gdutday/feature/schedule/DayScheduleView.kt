package com.gdutday.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlin.math.abs

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
    // 左右滑动切天：先锁定方向，只有横向拖拽才消费事件并触发切天，
    // 纵向拖拽原样交给 LazyColumn 滚动 / 外层下拉刷新，互不干扰。
    // 空的那天也要能滑走，所以容器修饰符在空状态分支之前算好。
    val containerModifier = if (onSwipeDay != null) {
        val touchSlop = LocalViewConfiguration.current.touchSlop
        modifier.pointerInput(onSwipeDay) {
            val threshold = 48.dp.toPx()
            awaitEachGesture {
                awaitFirstDown()
                var dragged = 0f
                var directionLocked = false
                var isHorizontal = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) {
                        if (isHorizontal && dragged > threshold) onSwipeDay(-1)
                        else if (isHorizontal && dragged < -threshold) onSwipeDay(1)
                        break
                    }
                    val delta = change.positionChange()
                    if (!directionLocked && (abs(delta.x) > touchSlop || abs(delta.y) > touchSlop)) {
                        directionLocked = true
                        isHorizontal = abs(delta.x) > abs(delta.y)
                    }
                    if (isHorizontal) {
                        change.consume()
                        dragged += delta.x
                    }
                }
            }
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
        items(blocks, key = { it.course.id.toString() + it.startMinute }) { block ->
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
                    if (block.isExam) Badge(text = "考试")
                    if (block.isCustom) Badge(text = "自定义")
                    Text(
                        text = block.course.name.maskIfNeeded(settings),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                val details = buildList {
                    if (settings.showClassroom && block.course.classroom.isNotBlank()) {
                        add(block.course.classroom)
                    }
                    if (settings.showTeacher && block.course.teacher.isNotBlank()) {
                        add(block.course.teacher.maskIfNeeded(settings))
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
