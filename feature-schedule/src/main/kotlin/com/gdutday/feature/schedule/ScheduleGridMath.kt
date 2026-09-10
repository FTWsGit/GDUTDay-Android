package com.gdutday.feature.schedule

/**
 * 课表网格的几何换算。**全部是纯函数，不依赖 Compose，可跑 JVM 单测。**
 *
 * 抽出来的理由：课表的定位规则（分钟 → 像素、并排列宽、周末列映射）
 * 一旦算错，表现是"课块位置整体偏移"或"周末列错位"，
 * 但这种错误在真机上肉眼很难定位到具体是哪个公式出的问题。
 * 放到纯函数里就能用固定输入钉死。
 *
 * ## 坐标约定
 *
 * - 单位一律是**像素**，调用方在 Composable 里用 `LocalDensity` 换算好再传进来；
 *   纯 JVM 测试直接传逻辑值，不需要密度。
 * - 横轴：最左是固定的节次栏（宽 [gutterPx]），之后每列一天。
 * - 纵轴：0 对应 [IntRange] 的 `first` 分钟，`gridHeightPx` 对应 `last` 分钟。
 */
public object ScheduleGridMath {

    /** 周历视为可见的星期序号。`showWeekend=false` 时只保留周一至周五。 */
    public fun visibleDayIndices(showWeekend: Boolean): List<Int> =
        if (showWeekend) (1..7).toList() else (1..5).toList()

    /**
     * 把 ISO 星期序号映射成可见列的下标（0-based）。
     *
     * `showWeekend=false` 时周六周日返回 null —— 调用方据此跳过该列，
     * 而不是把周六硬塞进第 6 列（那会让画出来的列数和表头对不上）。
     */
    public fun slotOf(dayOfWeek: Int, showWeekend: Boolean): Int? =
        visibleDayIndices(showWeekend).indexOf(dayOfWeek).takeIf { it >= 0 }

    /** 把任意输入的周次钳制到 `[1, totalWeeks]`。totalWeeks 非法时保底为 1。 */
    public fun clampWeek(week: Int, totalWeeks: Int): Int =
        week.coerceIn(1, totalWeeks.coerceAtLeast(1))

    /** 网格覆盖的总分钟数，至少为 1 以避免除零。 */
    public fun totalMinutes(range: IntRange): Int =
        (range.last - range.first).coerceAtLeast(1)

    /**
     * 某一分钟的纵坐标。
     *
     * 这是**按分钟而非按节次**定位的核心：考试给的是 `08:30--10:05` 这样的具体时刻，
     * 映射到"第几节"会损失精度，直接线性插值才能落在正确高度。
     */
    public fun minuteToY(minute: Int, range: IntRange, gridHeightPx: Float): Float {
        val total = totalMinutes(range)
        val offset = (minute - range.first).coerceIn(0, total)
        return offset.toFloat() / total * gridHeightPx
    }

    /** 色块的纵向高度，同样按分钟比例。 */
    public fun blockHeight(
        startMinute: Int,
        endMinute: Int,
        range: IntRange,
        gridHeightPx: Float,
    ): Float {
        val total = totalMinutes(range)
        val duration = (endMinute - startMinute).coerceIn(0, total)
        return duration.toFloat() / total * gridHeightPx
    }

    /** 并排冲突时，每个色块分到的宽度。 */
    public fun blockWidth(dayWidthPx: Float, columnCount: Int): Float =
        dayWidthPx / columnCount.coerceAtLeast(1)

    /**
     * 色块左上角横坐标。
     *
     * `columnIndex/columnCount` 已经由 `ScheduleGridBuilder.layout()` 算好，
     * UI 只负责按比例缩窄并右移，绝不能再自行判冲突。
     */
    public fun blockX(
        daySlot: Int,
        dayWidthPx: Float,
        columnIndex: Int,
        columnCount: Int,
        gutterPx: Float,
    ): Float =
        gutterPx + daySlot * dayWidthPx + blockWidth(dayWidthPx, columnCount) * columnIndex
}
