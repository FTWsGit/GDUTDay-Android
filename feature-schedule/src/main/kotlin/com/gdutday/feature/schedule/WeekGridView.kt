package com.gdutday.feature.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import com.gdutday.core.common.buildConflictClusters
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.ui.LocalGdutDayColors
import com.gdutday.core.ui.ScheduleBlockText
import com.gdutday.core.ui.toComposeColor
import java.time.LocalDate
import kotlin.math.roundToInt

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
 * ## 左右滑动切周（三页预渲染 + 固定节次栏）
 *
 * 左侧节次栏和顶部星期表头在滑动区域**之外**，任何位移都不带动它们；
 * 滑动区内三页并排（前一周 / 本周 / 后一周），`translationX = drag - pageWidth`
 * 让本周初始停在中间页，拖动时相邻周内容实时跟手进入视野。
 * 松手不足阈值弹回原位，超过阈值提交翻页并把 [drag] 归零。
 * 具体手势逻辑见 [detectHorizontalSwipe]。
 */
@Composable
public fun WeekGridView(
    grid: WeekGrid,
    settings: UserSettings,
    today: LocalDate,
    isCurrentWeek: Boolean,
    onBlockClick: (CourseBlock) -> Unit,
    onSwipeWeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    prevWeekGrid: WeekGrid? = null,
    nextWeekGrid: WeekGrid? = null,
    onOpenConflictList: (List<CourseBlock>) -> Unit = {},
) {
    val density = LocalDensity.current
    val periodHeightPx = with(density) { PERIOD_HEIGHT.toPx() }
    val gutterPx = with(density) { GUTTER_WIDTH.toPx() }
    val insetPx = with(density) { BLOCK_INSET.toPx() }

    val visibleDays = remember(settings.showWeekend) {
        ScheduleGridMath.visibleDayIndices(settings.showWeekend)
    }

    // 作息表恒为 12 节。showExtraSections 的语义是"是否展开 13/14 节"，
    // 内置作息没有这两节，所以这里最多取到 size（未来若作息表扩到 14 节，这里自动生效）。
    val periods = remember(grid.timetable, settings.showExtraSections) {
        if (settings.showExtraSections) grid.timetable.periods
        else grid.timetable.periods.take(12)
    }

    // 是否有早于第 1 节的"前置溢出槽"（自定义课程/考试）。节次栏的纵向对齐需要它：
    // 有前置溢出时第 1 节整体下移一格，节次栏也必须跟着下移一格，否则数字和行错位。
    val frontOverflowSlots = remember(grid) {
        if (grid.verticalRange.first < CourseBlock.clockToMinute(grid.timetable.firstPeriodStart)) 1 else 0
    }

    val touchSlop = LocalViewConfiguration.current.touchSlop
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 纵向滚动状态提升到这里，三页周视图与左侧节次栏共享同一份偏移：
    // 内容上下滚动时节次栏用 graphicsLayer 同步移动，不再错位。
    val verticalScroll = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        DayHeaderRow(
            grid = grid,
            visibleDays = visibleDays,
            today = today,
            gutterWidth = GUTTER_WIDTH,
        )

        Row(Modifier.fillMaxWidth().weight(1f)) {
            // 左侧节次栏：横向滑动时固定不动；纵向滚动时随内容同步移动。
            PeriodGutter(
                periods = periods,
                translucent = settings.backgroundImageUri != null,
                periodHeightPx = periodHeightPx,
                frontOverflowSlots = frontOverflowSlots,
                scrollState = verticalScroll,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .pointerInput(grid.week) {
                        detectHorizontalSwipe(
                            drag = drag,
                            scope = scope,
                            touchSlop = touchSlop,
                            threshold = 64.dp.toPx(),
                            onSwipe = { delta -> onSwipeWeek(grid.week + delta) },
                            fullPageCommit = true,
                        )
                    },
            ) {
                val pageWidth = constraints.maxWidth.toFloat()
                val gridHeightPx = (periods.size.coerceAtLeast(1) + overflowSlots(grid)) * periodHeightPx
                val dayWidthPx = (pageWidth / visibleDays.size).coerceAtLeast(1f)
                val dayWidthDp = with(density) { dayWidthPx.toDp() }
                val gridHeightDp = with(density) { gridHeightPx.toDp() }

                // 三页并排：本周初始停在中间页，拖动时相邻周实时跟手进入视野。
                // 用自定义 Layout 按固定宽度逐页摆放，而不是 Row：Row 会按"剩余宽度"
                // 重新分配各页约束，导致后两页被摆到同一个 x 上重叠、内容整体偏左。
                Layout(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = drag.value - pageWidth },
                    content = {
                        WeekPage(
                            grid = prevWeekGrid,
                            fallback = grid,
                            scrollState = verticalScroll,
                            visibleDays = visibleDays,
                            today = today,
                            isCurrentWeek = false,
                            periods = periods,
                            settings = settings,
                            dayWidthPx = dayWidthPx,
                            gutterPx = 0f,
                            gridHeightPx = gridHeightPx,
                            insetPx = insetPx,
                            gridHeightDp = gridHeightDp,
                            dayWidthDp = dayWidthDp,
                            onBlockClick = onBlockClick,
                            onOpenConflictList = onOpenConflictList,
                        )
                        WeekPage(
                            grid = grid,
                            fallback = grid,
                            scrollState = verticalScroll,
                            visibleDays = visibleDays,
                            today = today,
                            isCurrentWeek = isCurrentWeek,
                            periods = periods,
                            settings = settings,
                            dayWidthPx = dayWidthPx,
                            gutterPx = 0f,
                            gridHeightPx = gridHeightPx,
                            insetPx = insetPx,
                            gridHeightDp = gridHeightDp,
                            dayWidthDp = dayWidthDp,
                            onBlockClick = onBlockClick,
                            onOpenConflictList = onOpenConflictList,
                        )
                        WeekPage(
                            grid = nextWeekGrid,
                            fallback = grid,
                            scrollState = verticalScroll,
                            visibleDays = visibleDays,
                            today = today,
                            isCurrentWeek = false,
                            periods = periods,
                            settings = settings,
                            dayWidthPx = dayWidthPx,
                            gutterPx = 0f,
                            gridHeightPx = gridHeightPx,
                            insetPx = insetPx,
                            gridHeightDp = gridHeightDp,
                            dayWidthDp = dayWidthDp,
                            onBlockClick = onBlockClick,
                            onOpenConflictList = onOpenConflictList,
                        )
                    },
                ) { measurables, constraints ->
                    val pageW = pageWidth.roundToInt()
                    val pageH = constraints.maxHeight
                    val placeables = measurables.map { measurable ->
                        measurable.measure(
                            Constraints(
                                minWidth = pageW,
                                maxWidth = pageW,
                                minHeight = pageH,
                                maxHeight = pageH,
                            ),
                        )
                    }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeables.forEachIndexed { index, placeable ->
                            placeable.placeRelative(index * pageW, 0)
                        }
                    }
                }
            }
        }
    }
}

/** 相邻周网格缺失（边界周 / 未生成）时的占位页：渲染本周内容的空网格副本。 */
private fun overflowSlots(grid: WeekGrid): Int {
    val range = grid.verticalRange
    val timetableRange = CourseBlock.clockToMinute(grid.timetable.firstPeriodStart)..CourseBlock.clockToMinute(grid.timetable.lastPeriodEnd)
    var extra = 0
    if (range.first < timetableRange.first) extra++
    if (range.last > timetableRange.last) extra++
    return extra
}

/** 单周页面：空网格背景 + 色块层。缺数据时渲染占位（不画色块）。 */
@Composable
private fun WeekPage(
    grid: WeekGrid?,
    fallback: WeekGrid,
    visibleDays: List<Int>,
    today: LocalDate,
    isCurrentWeek: Boolean,
    periods: List<Period>,
    settings: UserSettings,
    dayWidthPx: Float,
    gutterPx: Float,
    scrollState: ScrollState,
    gridHeightPx: Float,
    insetPx: Float,
    gridHeightDp: Dp,
    dayWidthDp: Dp,
    onBlockClick: (CourseBlock) -> Unit,
    onOpenConflictList: (List<CourseBlock>) -> Unit,
) {
    // 网格几何（作息表/纵向范围）用 fallback 保证三页行高一致；
    // 但色块只属于各自的周，缺数据时绝不画 fallback 的课程，否则
    // 本周课程会被复制到相邻页上，滑动时出现"课程重叠"的错觉。
    val currentGrid = grid ?: fallback
    val showBlocks = grid != null
    val periodRanges = remember(periods, currentGrid) { buildPeriodRanges(periods, currentGrid.verticalRange) }

    // width 由父级三页 Layout 的固定约束给定（见 WeekGridView 里的 measure 策略），
    // 这里只需撑满高度并允许纵向滚动。
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(scrollState),
    ) {
        // 只在几何输入变化时重建色块列表，滚动/重组不重新分配。
        val placed = remember(
            currentGrid, showBlocks, visibleDays, periodRanges, dayWidthPx, gutterPx, gridHeightPx, insetPx,
        ) {
            if (!showBlocks) emptyList()
            else buildPlacedBlocks(
                currentGrid, visibleDays, periodRanges, dayWidthPx, gutterPx,
                gridHeightPx, insetPx,
            )
        }

        Box(Modifier.fillMaxWidth().height(gridHeightDp)) {
            GridBackground(
                visibleDays = visibleDays,
                today = today,
                isCurrentWeek = isCurrentWeek,
                periods = periods,
                periodRanges = periodRanges,
                gutterPx = 0f,
                dayWidthPx = dayWidthPx,
            )
            CourseBlockLayer(
                placed = placed,
                settings = settings,
                onBlockClick = onBlockClick,
                onOpenConflictList = onOpenConflictList,
            )
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
    /** 所属星期（1=周一…7=周日）。冲突聚类必须逐天进行，见 [buildRenderUnits]。 */
    val dayOfWeek: Int,
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
            add(PlacedBlock(block, day.dayOfWeek, x, y, w, h))
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
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(DAY_HEADER_HEIGHT)) {
        Box(Modifier.width(gutterWidth).fillMaxHeight())
        for (dow in visibleDays) {
            val column = grid.day(dow)
            val isToday = column.date == today
            Column(
                modifier = Modifier
                    .weight(1f)
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
 * 左侧节次栏。独立 composable，**不**加横向 `graphicsLayer` / `AnimatedContent`：
 * 左右滑动切周时它保持静止，只有右侧内容区随手势平移。
 *
 * 纵向则相反：它需要与内容一起上下滚动。所以外层是一个裁剪到视口的 [Box]，
 * 内层 [Canvas] 按"节数 × 每节高度"排满内容高度，再用 `graphicsLayer.translationY`
 * 抵消共享的 [ScrollState] 偏移，于是纵向滚动时节次数字与课程行严格对齐。
 */
@Composable
private fun PeriodGutter(
    periods: List<Period>,
    translucent: Boolean,
    periodHeightPx: Float,
    frontOverflowSlots: Int,
    scrollState: ScrollState,
) {
    val colors = LocalGdutDayColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val numberStyle = ScheduleBlockText.sectionNumber.copy(color = scheme.onSurfaceVariant)
    val timeStyle = ScheduleBlockText.sectionTime.copy(color = scheme.onSurfaceVariant)
    val widthPx = with(LocalDensity.current) { GUTTER_WIDTH.toPx() }
    val heightPx = periods.size * periodHeightPx
    val heightDp = with(LocalDensity.current) { heightPx.toDp() }

    Box(Modifier.width(GUTTER_WIDTH).fillMaxHeight().clipToBounds()) {
        Canvas(
            Modifier
                .width(GUTTER_WIDTH)
                // requiredHeight：内容高度（节数 × 每节高）通常大于视口，必须强制、不能被
                // 父约束压缩，否则 slotHeight 会退化成"视口高度 ÷ 节数"，与右侧网格错位。
                .requiredHeight(heightDp)
                .graphicsLayer { translationY = frontOverflowSlots * periodHeightPx - scrollState.value },
        ) {
            val slotHeight = size.height / periods.size.coerceAtLeast(1)
            val gutterColor = if (translucent) colors.sectionGutter.copy(alpha = 0.6f) else colors.sectionGutter
            drawRect(color = gutterColor, size = Size(size.width, size.height))
            // 右边界线（与网格纵向线同色，随内容滑动也不会出现断口）。
            drawLine(colors.gridLine, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1f)
            periods.forEachIndexed { index, period ->
                val y = index * slotHeight
                val centerY = y + slotHeight / 2f
                val number = measurer.measure(period.index.toString(), numberStyle)
                drawText(
                    textLayoutResult = number,
                    topLeft = Offset((widthPx - number.size.width) / 2f, centerY - number.size.height - timeStyle.fontSize.toPx()),
                )
                val start = measurer.measure(formatClock(period.start), timeStyle)
                drawText(
                    textLayoutResult = start,
                    topLeft = Offset((widthPx - start.size.width) / 2f, centerY - timeStyle.fontSize.toPx() / 2f),
                )
                val end = measurer.measure(formatClock(period.end), timeStyle)
                drawText(
                    textLayoutResult = end,
                    topLeft = Offset((widthPx - end.size.width) / 2f, centerY + timeStyle.fontSize.toPx() / 2f),
                )
            }
        }
    }
}

/** `08:30`。 */
private fun formatClock(time: java.time.LocalTime): String =
    "%02d:%02d".format(time.hour, time.minute)

/**
 * 背景层：网格线、今天列高亮。
 *
 * 节次栏已拆到 [PeriodGutter]（滑动时固定），这里只画内容区。
 * 全部在一个 [Canvas] 里画完，不产生布局节点。
 */
@Composable
private fun GridBackground(
    visibleDays: List<Int>,
    today: LocalDate,
    isCurrentWeek: Boolean,
    periods: List<Period>,
    periodRanges: List<IntRange>,
    gutterPx: Float,
    dayWidthPx: Float,
) {
    val colors = LocalGdutDayColors.current

    Canvas(Modifier.fillMaxSize()) {
        val height = size.height
        val slotHeight = height / periodRanges.size.coerceAtLeast(1)

        // 1. 今天列高亮（必须在网格线之前画）。
        //    仅当前周才画：翻到其它周次时同一天的列不该挂"今天"指示条，
        //    否则每一周的这一天都亮，语义完全错误。
        val todaySlot = if (isCurrentWeek) visibleDays.indexOf(today.dayOfWeek.value) else -1
        if (todaySlot >= 0) {
            drawRect(
                color = colors.todayHighlight,
                topLeft = Offset(gutterPx + todaySlot * dayWidthPx, 0f),
                size = Size(dayWidthPx, height),
            )
        }

        // 2. 横向网格线：每个节次槽位的边界（等高，不随分钟浮动）
        for (i in 0..periodRanges.size) {
            val y = i * slotHeight
            drawLine(colors.gridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        // 3. 纵向网格线（含最左侧边界，与固定节次栏的分隔线重叠）
        for (i in 0..visibleDays.size) {
            val x = gutterPx + i * dayWidthPx
            drawLine(colors.gridLine, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
        }
    }
}

/**
 * 课程块层。用 [Layout] 把预计算的几何直接落到像素位置。
 *
 * 这是整个页面唯一会"按数据量增长"的节点来源（每门课一个），
 * 而非按网格尺寸增长（12×7）。
 *
 * ≥3 门重叠的堆叠降级：整簇只渲染主块，被盖住的课在 [CourseBlockItem] 里
 * 以"露出纸边"的形式呈现；点击整块打开完整冲突列表。
 * 冲突必须**逐天**聚类——`buildConflictClusters` 只按时间重叠判断，
 * 整周混在一起会把不同天的同时段课程误判为冲突。
 */
@Composable
private fun CourseBlockLayer(
    placed: List<PlacedBlock>,
    settings: UserSettings,
    onBlockClick: (CourseBlock) -> Unit,
    onOpenConflictList: (List<CourseBlock>) -> Unit,
) {
    val units: List<RenderUnit> = remember(placed) { buildRenderUnits(placed) }

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            units.forEach { unit ->
                CourseBlockItem(
                    block = unit.block,
                    settings = settings,
                    onClick = {
                        val conflict = unit.conflictBlocks
                        if (conflict != null) onOpenConflictList(conflict) else onBlockClick(unit.block)
                    },
                    behindColors = unit.behindColors,
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = units[index].rect
            measurable.measure(
                Constraints.fixed(
                    width = rect.width.toInt().coerceAtLeast(1),
                    height = rect.height.toInt().coerceAtLeast(1),
                ),
            )
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val rect = units[index].rect
                placeable.place(rect.x.toInt(), rect.y.toInt())
            }
        }
    }
}

/** 色块层的一次渲染：普通色块，或一个 ≥3 门冲突簇（只画主块 + 纸边）。 */
private class RenderUnit(
    val block: CourseBlock,
    val rect: BlockRect,
    /** 非空表示这是堆叠主块，点击应打开冲突列表。 */
    val conflictBlocks: List<CourseBlock>?,
    /** 被盖住的课程颜色，交给 [CourseBlockItem] 画纸边。 */
    val behindColors: List<Color>,
)

private class BlockRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * 逐天做冲突聚类，产出渲染单元。≥[STACK_THRESHOLD] 门的一簇合并为一个单元，
 * 用簇内所有色块的并集矩形作为它占据的格子（列宽即整列宽）。
 */
private fun buildRenderUnits(placed: List<PlacedBlock>): List<RenderUnit> {
    val units = mutableListOf<RenderUnit>()
    for (dayItems in placed.groupBy { it.dayOfWeek }.values) {
        val clusters = dayItems.map { it.block }.buildConflictClusters()
            .filter { it.blocks.size >= STACK_THRESHOLD }
        val consumed = BooleanArray(dayItems.size)
        for (cluster in clusters) {
            val memberIndices = dayItems.indices.filter { index ->
                cluster.blocks.any { it === dayItems[index].block }
            }
            if (memberIndices.isEmpty()) continue
            memberIndices.forEach { consumed[it] = true }
            val members = memberIndices.map { dayItems[it] }
            val left = members.minOf { it.x }
            val top = members.minOf { it.y }
            val right = members.maxOf { it.x + it.width }
            val bottom = members.maxOf { it.y + it.height }
            units += RenderUnit(
                block = members.first { it.block === cluster.primary }.block,
                rect = BlockRect(left, top, right - left, bottom - top),
                conflictBlocks = cluster.blocks,
                behindColors = cluster.blocks.drop(1).map { it.color.toComposeColor() },
            )
        }
        dayItems.forEachIndexed { index, item ->
            if (consumed[index]) return@forEachIndexed
            units += RenderUnit(
                block = item.block,
                rect = BlockRect(item.x, item.y, item.width, item.height),
                conflictBlocks = null,
                behindColors = emptyList(),
            )
        }
    }
    return units
}
