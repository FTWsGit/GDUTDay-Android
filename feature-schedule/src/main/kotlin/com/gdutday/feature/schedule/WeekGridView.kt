package com.gdutday.feature.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.common.Period
import com.gdutday.core.common.WeekGrid
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.ui.LocalGdutDayColors
import com.gdutday.core.ui.ScheduleBlockText
import java.time.LocalDate

/**
 * 每节对应的固定像素高度。
 *
 * 与旧实现"1 分钟 = 1dp"不同，这里把每一节压成等高，课间休息（含午休）不占高度。
 * 否则第 4 节之后那段 110 分钟的午休会把第 4 节所在行撑成第 1 节的 3 倍高，
 * 行高参差不齐、色块之间还留一大段空白。
 */
private val PERIOD_HEIGHT: Dp = 56.dp

/** 左侧节次栏宽度。要放下 "08:30"（8sp）+ 节次数字（11sp）。 */
private val GUTTER_WIDTH: Dp = 44.dp

/** 顶部星期表头高度。 */
private val DAY_HEADER_HEIGHT: Dp = 44.dp

/** 色块与相邻色块/网格线的内缩，避免并排时糊在一起。 */
private val BLOCK_INSET: Dp = 0.5.dp

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 周视图网格。
 *
 * ## 渲染方案（性能关键）
 *
 * 课表是一个 7 列 × 12 节的矩阵，但**实际有内容的格子通常不到 25 个**。
 * 如果用 `Column { repeat(12) { Row { repeat(7) { ... } } } }` 铺满，
 * 会产生 84 个格子、每格再嵌套若干文字节点，整个屏幕上千个 Composable 节点，
 * 纵向滚动时重组与布局开销直接体现在掉帧上。
 *
 * 因此这里拆成三层：
 * 1. **背景层**：一个 [Canvas] 画完节次栏底色、今天列高亮、全部网格线和节次文字。
 *    网格线是纯绘制，不产生任何节点；
 * 2. **课程块层**：自定义 [Layout]，只对真实存在的色块测量/摆放，
 *    每块是一个小 Composable（通常 ≤ 25 个，可接受）；
 * 3. **交互**：色块自身可点击，空网格不需要热区。
 *
 * ## 为什么按节次等高定位
 *
 * [CourseBlock.startMinute/endMinute] 是当天分钟数，但按分钟线性定位会让
 * "45 分钟的课 + 课间休息"共同决定行高，导致各节行高不一致、色块间出现空隙。
 * 因此改用 [ScheduleGridMath.minuteToYByPeriods]：每一节占等高槽位，节内分钟线性插值，
 * 课间不占高度。考试给的具体时刻（`08:30--10:05`）也照常映射，不损失精度。
 *
 * ## 左右滑动切周（页面跟随）
 *
 * 横向拖动时页面随手势平移（`graphicsLayer { translationX = drag.value }`），
 * 松手时位移不足阈值弹回原位，超过阈值则提交翻页并由 [AnimatedContent] 完成过渡。
 * 具体手势逻辑见 [detectHorizontalSwipe]。
 */
@Composable
public fun WeekGridView(
    grid: WeekGrid,
    settings: UserSettings,
    today: LocalDate,
    onBlockClick: (CourseBlock) -> Unit,
    onSwipeWeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val periodHeightPx = with(density) { PERIOD_HEIGHT.toPx() }
    val gutterPx = with(density) { GUTTER_WIDTH.toPx() }
    val insetPx = with(density) { BLOCK_INSET.toPx() }
    val range = grid.verticalRange

    val visibleDays = remember(settings.showWeekend) {
        ScheduleGridMath.visibleDayIndices(settings.showWeekend)
    }

    // 作息表恒为 12 节。showExtraSections 的语义是"是否展开 13/14 节"，
    // 内置作息没有这两节，所以这里最多取到 size（未来若作息表扩到 14 节，这里自动生效）。
    val periods = remember(grid.timetable, settings.showExtraSections) {
        if (settings.showExtraSections) grid.timetable.periods
        else grid.timetable.periods.take(12)
    }

    val periodRanges = remember(periods, range) { buildPeriodRanges(periods, range) }

    val touchSlop = LocalViewConfiguration.current.touchSlop
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(grid.week) {
                detectHorizontalSwipe(
                    drag = drag,
                    scope = scope,
                    touchSlop = touchSlop,
                    threshold = 64.dp.toPx(),
                    onSwipe = { delta -> onSwipeWeek(grid.week + delta) },
                )
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val gridHeightPx = periodRanges.size * periodHeightPx
        val dayWidthPx = ((widthPx - gutterPx) / visibleDays.size).coerceAtLeast(1f)
        val dayWidthDp = with(density) { dayWidthPx.toDp() }
        val gridHeightDp = with(density) { gridHeightPx.toDp() }

        Column(Modifier.fillMaxSize().graphicsLayer { translationX = drag.value }) {
            DayHeaderRow(
                grid = grid,
                visibleDays = visibleDays,
                today = today,
                gutterWidth = GUTTER_WIDTH,
                dayWidth = dayWidthDp,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 周次切换的滑动过渡动画。方向由新旧周次的相对大小决定：
                // 往右滑看上一周 → 新内容从左侧滑入；往左滑看下一周 → 新内容从右侧滑入。
                AnimatedContent(
                    targetState = grid,
                    transitionSpec = {
                        val direction = if (targetState.week > initialState.week) 1 else -1
                        (slideInHorizontally { full -> full * direction } + fadeIn())
                            .togetherWith(
                                slideOutHorizontally { full -> -full * direction } + fadeOut(),
                            )
                    },
                    label = "weekGrid",
                ) { currentGrid ->
                    // 只在几何输入变化时重建色块列表，滚动/重组不重新分配。
                    val placed = remember(
                        currentGrid, visibleDays, dayWidthPx, gutterPx, gridHeightPx, insetPx,
                    ) {
                        buildPlacedBlocks(
                            currentGrid, visibleDays, periodRanges, dayWidthPx, gutterPx,
                            gridHeightPx, insetPx,
                        )
                    }

                    Box(Modifier.fillMaxWidth().height(gridHeightDp)) {
                        GridBackground(
                            visibleDays = visibleDays,
                            today = today,
                            periods = periods,
                            periodRanges = periodRanges,
                            gutterPx = gutterPx,
                            dayWidthPx = dayWidthPx,
                        )
                        CourseBlockLayer(
                            placed = placed,
                            settings = settings,
                            onBlockClick = onBlockClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把作息表的各节转成分钟区间，并在必要时于首/末节之外补一个"溢出槽"，
 * 容纳早于第 1 节、晚于第 12 节的自定义课程或考试，避免它们被钳制到网格内。
 */
private fun buildPeriodRanges(periods: List<Period>, range: IntRange): List<IntRange> {
    val ranges = periods.map { CourseBlock.clockToMinute(it.start)..CourseBlock.clockToMinute(it.end) }
    if (ranges.isEmpty()) return ranges
    val result = ranges.toMutableList()
    val firstStart = ranges.first().first
    val lastEnd = ranges.last().last
    if (range.first < firstStart) result.add(0, range.first..(firstStart - 1))
    if (range.last > lastEnd) result.add((lastEnd + 1)..range.last)
    return result
}

/** 预计算好的色块几何。像素坐标在布局阶段直接使用，避免在 measure 里现算。 */
private data class PlacedBlock(
    val block: CourseBlock,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

private fun buildPlacedBlocks(
    grid: WeekGrid,
    visibleDays: List<Int>,
    periodRanges: List<IntRange>,
    dayWidthPx: Float,
    gutterPx: Float,
    gridHeightPx: Float,
    insetPx: Float,
): List<PlacedBlock> = buildList {
    for (day in grid.days) {
        val slot = visibleDays.indexOf(day.dayOfWeek)
        if (slot < 0) continue
        for (block in day.blocks) {
            val x = ScheduleGridMath.blockX(slot, dayWidthPx, block.columnIndex, block.columnCount, gutterPx) + insetPx
            val y = ScheduleGridMath.minuteToYByPeriods(block.startMinute, periodRanges, gridHeightPx) + insetPx
            val w = (ScheduleGridMath.blockWidth(dayWidthPx, block.columnCount) - insetPx * 2).coerceAtLeast(1f)
            val h = (ScheduleGridMath.blockHeightByPeriods(block.startMinute, block.endMinute, periodRanges, gridHeightPx) - insetPx * 2)
                .coerceAtLeast(1f)
            add(PlacedBlock(block, x, y, w, h))
        }
    }
}

/** 顶部星期表头。列宽与网格列严格一致，否则表头和色块会对不齐。 */
@Composable
private fun DayHeaderRow(
    grid: WeekGrid,
    visibleDays: List<Int>,
    today: LocalDate,
    gutterWidth: Dp,
    dayWidth: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(DAY_HEADER_HEIGHT)) {
        Box(Modifier.width(gutterWidth).fillMaxHeight())
        for (dow in visibleDays) {
            val column = grid.day(dow)
            val isToday = column.date == today
            Column(
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = WEEKDAY_LABELS[dow - 1],
                    style = ScheduleBlockText.dayHeader,
                    color = if (isToday) scheme.primary else scheme.onSurface,
                )
                Text(
                    text = "${column.date.monthValue}/${column.date.dayOfMonth}",
                    style = ScheduleBlockText.sectionTime,
                    color = if (isToday) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 背景层：网格线、今天列高亮、节次栏底色与文字。
 *
 * 全部在一个 [Canvas] 里画完，不产生布局节点。节次文字用 [TextMeasurer]
 * 直接绘制，避免再建 12 个 Text。
 */
@Composable
private fun GridBackground(
    visibleDays: List<Int>,
    today: LocalDate,
    periods: List<Period>,
    periodRanges: List<IntRange>,
    gutterPx: Float,
    dayWidthPx: Float,
) {
    val colors = LocalGdutDayColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val numberStyle = ScheduleBlockText.sectionNumber.copy(color = scheme.onSurfaceVariant)
    val timeStyle = ScheduleBlockText.sectionTime.copy(color = scheme.onSurfaceVariant)

    Canvas(Modifier.fillMaxSize()) {
        val height = size.height
        val slotHeight = height / periodRanges.size.coerceAtLeast(1)

        // 1. 节次栏底色
        drawRect(color = colors.sectionGutter, size = Size(gutterPx, height))

        // 2. 今天列高亮（必须在网格线之前画）
        val todaySlot = visibleDays.indexOf(today.dayOfWeek.value)
        if (todaySlot >= 0) {
            drawRect(
                color = colors.todayHighlight,
                topLeft = Offset(gutterPx + todaySlot * dayWidthPx, 0f),
                size = Size(dayWidthPx, height),
            )
        }

        // 3. 横向网格线：每个节次槽位的边界（等高，不随分钟浮动）
        for (i in 0..periodRanges.size) {
            val y = i * slotHeight
            drawLine(colors.gridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        // 4. 纵向网格线（含节次栏右边界）
        for (i in 0..visibleDays.size) {
            val x = gutterPx + i * dayWidthPx
            drawLine(colors.gridLine, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
        }

        // 5. 节次栏文字：节次号居中，开始/结束时刻分列其下。
        //    溢出槽（早于第 1 节或晚于第 12 节）没有节次，不画数字。
        periods.forEachIndexed { index, period ->
            val y = index * slotHeight
            val centerY = y + slotHeight / 2f
            val number = measurer.measure(period.index.toString(), numberStyle)
            drawText(
                textLayoutResult = number,
                topLeft = Offset((gutterPx - number.size.width) / 2f, centerY - number.size.height - timeStyle.fontSize.toPx()),
            )
            val start = measurer.measure(formatClock(period.start), timeStyle)
            drawText(
                textLayoutResult = start,
                topLeft = Offset((gutterPx - start.size.width) / 2f, centerY - timeStyle.fontSize.toPx() / 2f),
            )
            val end = measurer.measure(formatClock(period.end), timeStyle)
            drawText(
                textLayoutResult = end,
                topLeft = Offset((gutterPx - end.size.width) / 2f, centerY + timeStyle.fontSize.toPx() / 2f),
            )
        }
    }
}

/** `08:30`。 */
private fun formatClock(time: java.time.LocalTime): String =
    "%02d:%02d".format(time.hour, time.minute)

/**
 * 课程块层。用 [Layout] 把预计算的几何直接落到像素位置。
 *
 * 这是整个页面唯一会"按数据量增长"的节点来源（每门课一个），
 * 而非按网格尺寸增长（12×7）。
 */
@Composable
private fun CourseBlockLayer(
    placed: List<PlacedBlock>,
    settings: UserSettings,
    onBlockClick: (CourseBlock) -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            placed.forEach { item ->
                CourseBlockItem(
                    block = item.block,
                    settings = settings,
                    onClick = { onBlockClick(item.block) },
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { index, measurable ->
            val item = placed[index]
            measurable.measure(
                Constraints.fixed(
                    width = item.width.toInt().coerceAtLeast(1),
                    height = item.height.toInt().coerceAtLeast(1),
                ),
            )
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val item = placed[index]
                placeable.place(item.x.toInt(), item.y.toInt())
            }
        }
    }
}
