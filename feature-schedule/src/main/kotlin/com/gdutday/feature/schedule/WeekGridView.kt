package com.gdutday.feature.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.common.WeekGrid
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.ui.LocalGdutDayColors
import com.gdutday.core.ui.ScheduleBlockText
import java.time.LocalDate

/** 网格整体每分钟对应的像素高度。745 分钟（8:30~20:55）≈ 745dp，比一屏高，需要纵向滚动。 */
private val MINUTE_HEIGHT: Dp = 1.dp

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
 * ## 为什么纵向按分钟定位
 *
 * `CourseBlock.startMinute/endMinute` 是当天分钟数。考试只给具体时刻
 * （`08:30--10:05`），自定义课程也可能是任意时段，按节次整数格摆会画错。
 * 用 [ScheduleGridMath.minuteToY] 线性插值即可与节次无关地精确定位。
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
    val minuteHeightPx = with(density) { MINUTE_HEIGHT.toPx() }
    val gutterPx = with(density) { GUTTER_WIDTH.toPx() }
    val insetPx = with(density) { BLOCK_INSET.toPx() }
    val range = grid.verticalRange

    val visibleDays = remember(settings.showWeekend) {
        ScheduleGridMath.visibleDayIndices(settings.showWeekend)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(grid.week) {
                var dragged = 0f
                val threshold = 64.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged > threshold) {
                            onSwipeWeek(grid.week - 1)
                        } else if (dragged < -threshold) {
                            onSwipeWeek(grid.week + 1)
                        }
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                    },
                )
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val gridHeightPx = ScheduleGridMath.totalMinutes(range) * minuteHeightPx
        val dayWidthPx = ((widthPx - gutterPx) / visibleDays.size).coerceAtLeast(1f)
        val dayWidthDp = with(density) { dayWidthPx.toDp() }
        val gridHeightDp = with(density) { gridHeightPx.toDp() }

        // 只在几何输入变化时重建色块列表，滚动/重组不重新分配。
        val placed = remember(grid, visibleDays, dayWidthPx, gutterPx, gridHeightPx, insetPx) {
            buildPlacedBlocks(grid, visibleDays, range, dayWidthPx, gutterPx, gridHeightPx, insetPx)
        }

        Column(Modifier.fillMaxSize()) {
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
                Box(Modifier.fillMaxWidth().height(gridHeightDp)) {
                    GridBackground(
                        grid = grid,
                        settings = settings,
                        visibleDays = visibleDays,
                        today = today,
                        range = range,
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
    range: IntRange,
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
            val y = ScheduleGridMath.minuteToY(block.startMinute, range, gridHeightPx) + insetPx
            val w = (ScheduleGridMath.blockWidth(dayWidthPx, block.columnCount) - insetPx * 2).coerceAtLeast(1f)
            val h = (ScheduleGridMath.blockHeight(block.startMinute, block.endMinute, range, gridHeightPx) - insetPx * 2)
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
    grid: WeekGrid,
    settings: UserSettings,
    visibleDays: List<Int>,
    today: LocalDate,
    range: IntRange,
    gutterPx: Float,
    dayWidthPx: Float,
) {
    val colors = LocalGdutDayColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val numberStyle = ScheduleBlockText.sectionNumber.copy(color = scheme.onSurfaceVariant)
    val timeStyle = ScheduleBlockText.sectionTime.copy(color = scheme.onSurfaceVariant)

    // 作息表恒为 12 节。showExtraSections 的语义是"是否展开 13/14 节"，
    // 内置作息没有这两节，所以这里最多取到 size（未来若作息表扩到 14 节，这里自动生效）。
    val periods = remember(grid.timetable, settings.showExtraSections) {
        if (settings.showExtraSections) grid.timetable.periods
        else grid.timetable.periods.take(12)
    }

    Canvas(Modifier.fillMaxSize()) {
        val height = size.height

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

        // 3. 横向网格线：每节开始处 + 末节结束处
        for (period in periods) {
            val y = ScheduleGridMath.minuteToY(CourseBlock.clockToMinute(period.start), range, height)
            drawLine(colors.gridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val bottomY = ScheduleGridMath.minuteToY(CourseBlock.clockToMinute(periods.last().end), range, height)
        drawLine(colors.gridLine, Offset(0f, bottomY), Offset(size.width, bottomY), strokeWidth = 1f)

        // 4. 纵向网格线（含节次栏右边界）
        for (i in 0..visibleDays.size) {
            val x = gutterPx + i * dayWidthPx
            drawLine(colors.gridLine, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
        }

        // 5. 节次栏文字：节次号居中，开始时刻在下方
        for (period in periods) {
            val startY = ScheduleGridMath.minuteToY(CourseBlock.clockToMinute(period.start), range, height)
            val endY = ScheduleGridMath.minuteToY(CourseBlock.clockToMinute(period.end), range, height)
            val centerY = (startY + endY) / 2f
            val number = measurer.measure(period.index.toString(), numberStyle)
            drawText(
                textLayoutResult = number,
                topLeft = Offset((gutterPx - number.size.width) / 2f, centerY - number.size.height),
            )
            val clock = measurer.measure(formatClock(period.start), timeStyle)
            drawText(
                textLayoutResult = clock,
                topLeft = Offset((gutterPx - clock.size.width) / 2f, centerY + 1f),
            )
        }
    }
}

/** `08:30`。只画开始时刻，结束时刻由学生自己看下一节。 */
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
