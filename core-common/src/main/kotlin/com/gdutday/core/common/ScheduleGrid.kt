package com.gdutday.core.common

import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Exam
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 课程块相对"现在"的时间状态。UI 据此决定置灰 / 高亮 / 正常。 */
public enum class BlockStatus {
    /** 还没开始（今天之后的课，或今天晚些时候的课）。 */
    UPCOMING,

    /** 正在上。UI 应高亮，Widget 应显示倒计时。 */
    ONGOING,

    /** 已经上完。旧小程序的 `isPassedCourseBlockGray` 开关控制是否置灰。 */
    FINISHED,
}

/**
 * 课表网格上的一个色块。这是 UI 直接消费的最终形态。
 *
 * ## 为什么用"当天分钟数"而不是节次来定位
 *
 * 节次只能表达整节对齐的课程。但考试安排给的是**具体时刻**（`08:30--10:05`），
 * 自定义课程也可能被用户设成任意时段。用 `[startMinute, endMinute)` 统一表达，
 * UI 就能用同一套定位逻辑画课程、考试和自定义条目，
 * 高度按 `(endMinute - startMinute) / 全天分钟跨度` 比例计算。
 *
 * 节次信息仍然保留在 [course] 里，用于"第几节"的文字展示。
 *
 * @property columnIndex 并排布局时的列序号，0-based。无冲突时为 0。
 * @property columnCount 所属冲突簇的总列数。无冲突时为 1。
 *   UI 应把色块宽度设为 `1f / columnCount`，左边距设为 `columnIndex / columnCount`。
 */
public data class CourseBlock(
    public val course: Course,
    public val color: CourseColor,
    public val startMinute: Int,
    public val endMinute: Int,
    public val columnIndex: Int = 0,
    public val columnCount: Int = 1,
    public val status: BlockStatus = BlockStatus.UPCOMING,
) {
    public val isExam: Boolean get() = course.source == CourseSource.EXAM
    public val isCustom: Boolean get() = course.source == CourseSource.CUSTOM
    public val durationMinutes: Int get() = endMinute - startMinute

    /** `"08:30"` */
    public val startClock: String get() = minuteToClock(startMinute)
    public val endClock: String get() = minuteToClock(endMinute)

    /** `"1-2节 · 教5-301"` 这样的紧凑摘要，Widget 里直接用。 */
    public val sectionLabel: String
        get() = when {
            // 非整节边界的补丁没有"第几节"可说，直接显示时刻
            course.startMinute >= 0 -> "$startClock-$endClock"
            course.sectionCount == 1 -> "${course.startSection}节"
            else -> "${course.startSection}-${course.endSection}节"
        }

    public companion object {
        public fun clockToMinute(clock: LocalTime): Int = clock.hour * 60 + clock.minute
        public fun minuteToClock(minute: Int): String =
            "%02d:%02d".format(minute / 60, minute % 60)
    }
}

/** 星期几的一列。 */
public data class DayColumn(
    /** 1=周一 … 7=周日 */
    public val dayOfWeek: Int,
    /** 该列对应的真实日期。 */
    public val date: LocalDate,
    /** 该列的色块，已按开始时间升序、并完成并排列布局。 */
    public val blocks: List<CourseBlock>,
) {
    public val isToday: Boolean get() = date == LocalDate.now()

    /** 该列第一个色块的开始分钟；空列返回 null。 */
    public val firstStartMinute: Int? get() = blocks.minOfOrNull { it.startMinute }

    /** 节次标签（左侧那一列的 "1 2 3 …"）由 UI 用 [CampusTimetable] 自行生成，此处不重复。 */
}

/**
 * 一周的课表网格。
 *
 * @property days 固定 7 项，`days[0]` 是周一，`days[6]` 是周日。
 */
public data class WeekGrid(
    public val week: Int,
    public val days: List<DayColumn>,
    public val timetable: CampusTimetable,
) {
    init {
        require(days.size == 7) { "一周必须有 7 列，收到 ${days.size}" }
    }

    public fun day(dayOfWeek: Int): DayColumn = days[dayOfWeek - 1]

    /** 本周是否完全没有安排。UI 用来显示空状态插画。 */
    public val isEmpty: Boolean get() = days.all { it.blocks.isEmpty() }

    /** 本周色块总数。 */
    public val blockCount: Int get() = days.sumOf { it.blocks.size }

    /**
     * 网格的纵向绘制范围（分钟）。
     *
     * 取"作息表首节开始"与"本周最早色块"的较小值作为上界，
     * "作息表末节结束"与"本周最晚色块"的较大值作为下界，
     * 这样既有早于第 1 节的自定义条目、也有晚于第 12 节的晚课都能画得下。
     */
    public val verticalRange: IntRange
        get() {
            val all = days.flatMap { it.blocks }
            val top = if (all.isEmpty()) CourseBlock.clockToMinute(timetable.firstPeriodStart)
            else minOf(all.minOf { it.startMinute }, CourseBlock.clockToMinute(timetable.firstPeriodStart))
            val bottom = if (all.isEmpty()) CourseBlock.clockToMinute(timetable.lastPeriodEnd)
            else maxOf(all.maxOf { it.endMinute }, CourseBlock.clockToMinute(timetable.lastPeriodEnd))
            return top..bottom
        }
}

/**
 * 从领域模型构建 [WeekGrid]。
 *
 * 这是**旧小程序里最绕的一块逻辑**（散落在 `schedule_store.js` 的 `productClassAndExam`、
 * `schedule-common-fun.js` 的 `textConflict`、`week-content.vue` 的模板里），
 * 本项目把它收敛成一个纯函数对象，配单元测试。
 *
 * ## 它做了旧版没做的两件事
 *
 * 1. **并排冲突布局**：同一时段有多门课时，旧版直接叠在一起（后画的盖住先画的），
 *    用户根本看不到被盖住的课。这里用经典的"区间分列"算法给每个色块算出
 *    `columnIndex` / `columnCount`，UI 只要按比例缩窄并右移即可。
 * 2. **考试按时刻落格**：考试没有节次，只有 `08:30--10:05` 这样的时段。
 *    这里用作息表反查它覆盖哪些节次，从而能和课程共用同一套网格。
 *
 * @property timetable 生效的作息表（决定节次 → 时刻）
 * @property calendar 学期历（决定周次 → 日期）
 * @property colorAssignment 课程名 → 颜色，来自 [CourseColors.assign]
 */
public class ScheduleGridBuilder(
    private val timetable: CampusTimetable,
    private val calendar: TermCalendar,
    private val colorAssignment: Map<String, CourseColor> = emptyMap(),
) {

    /**
     * 构建某一周的网格。
     *
     * @param courses 全部课程（所有周次的），内部按 `week` 过滤
     * @param exams 全部考试，内部按日期落在本周过滤
     * @param week 1-based 周次
     * @param now 判定 [BlockStatus] 的参照时刻。测试里传固定值。
     */
    public fun buildWeek(
        courses: List<Course>,
        exams: List<Exam> = emptyList(),
        week: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ): WeekGrid {
        val weekMonday = calendar.mondayOf(week)
        val weekSunday = calendar.sundayOf(week)

        // 按星期分桶，避免 7×N 的重复扫描
        val byDay = Array(8) { mutableListOf<RawBlock>() }

        for (c in courses) {
            if (!c.occursInWeek(week)) continue
            if (c.dayOfWeek !in 1..7) continue
            // 绝对时间（用户改到非整节边界）优先；否则按节次查作息表。
            val startMin = if (c.startMinute >= 0) c.startMinute
            else CourseBlock.clockToMinute(timetable.periodOf(c.startSection).start)
            val endMin = if (c.endMinute >= 0) c.endMinute
            else CourseBlock.clockToMinute(timetable.periodOf(c.endSection).end)
            byDay[c.dayOfWeek] += RawBlock(c, startMin, endMin)
        }

        for (e in exams) {
            if (e.date < weekMonday || e.date > weekSunday) continue
            val dow = e.date.dayOfWeek.value
            val pseudo = e.toCourse(week)
            val startMin = e.startTime?.let { CourseBlock.clockToMinute(it) }
                ?: CourseBlock.clockToMinute(timetable.periodOf(pseudo.startSection).start)
            val endMin = e.endTime?.let { CourseBlock.clockToMinute(it) }
                ?: CourseBlock.clockToMinute(timetable.periodOf(pseudo.endSection).end)
            byDay[dow] += RawBlock(pseudo, startMin, endMin)
        }

        val days = (1..7).map { dow ->
            val date = weekMonday.plusDays((dow - 1).toLong())
            val laid = layout(byDay[dow])
            val colored = laid.map { raw ->
                val today = date == now.toLocalDate()
                val status = when {
                    !today && date < now.toLocalDate() -> BlockStatus.FINISHED
                    !today -> BlockStatus.UPCOMING
                    now.toLocalTime() >= raw.endMinuteAsTime() -> BlockStatus.FINISHED
                    now.toLocalTime() >= raw.startMinuteAsTime() -> BlockStatus.ONGOING
                    else -> BlockStatus.UPCOMING
                }
                CourseBlock(
                    course = raw.course,
                    color = colorAssignment[raw.course.name] ?: CourseColors.DEFAULT,
                    startMinute = raw.startMinute,
                    endMinute = raw.endMinute,
                    columnIndex = raw.columnIndex,
                    columnCount = raw.columnCount,
                    status = status,
                )
            }
            DayColumn(dow, date, colored)
        }

        return WeekGrid(week, days, timetable)
    }

    /**
     * 构建某一天的日程列表（日视图 / Widget 的"今日课程"）。
     *
     * 与 [buildWeek] 的区别：跨周的自定义条目只要日期命中就返回，
     * 且结果按开始时间排序、带冲突列布局。
     */
    public fun buildDay(
        courses: List<Course>,
        exams: List<Exam> = emptyList(),
        date: LocalDate,
        now: LocalDateTime = LocalDateTime.now(),
    ): DayColumn = buildWeek(courses, exams, calendar.weekOf(date), now)
        .day(date.dayOfWeek.value)
        // 日期可能不属于该周的这一天（例如 date 在学期外），这里强制回填真实日期
        .copy(date = date)

    // ------------------------------------------------------------ 内部实现

    // internal 而非 private：单元测试需要直接构造冲突场景来验证分列算法，
    // 但不希望它出现在公开 API 里（UI 只应消费 CourseBlock）。
    internal class RawBlock(
        internal val course: Course,
        internal val startMinute: Int,
        internal var endMinute: Int,
        internal var columnIndex: Int = 0,
        internal var columnCount: Int = 1,
    )

    private fun RawBlock.startMinuteAsTime(): LocalTime = LocalTime.of(startMinute / 60, startMinute % 60)
    private fun RawBlock.endMinuteAsTime(): LocalTime =
        if (endMinute >= 24 * 60) LocalTime.MAX else LocalTime.of(endMinute / 60, endMinute % 60)

    /**
     * 区间分列算法。
     *
     * 1. 按开始时间升序（开始相同则长的在前）排序；
     * 2. 切成"冲突簇"—— 互相传递重叠的块归为一簇；
     * 3. 簇内贪心分配列：找第一个"上一块的结束时间 ≤ 当前块开始时间"的列，
     *    找不到就新开一列；
     * 4. 簇内所有块的 `columnCount` = 该簇用到的列数。
     *
     * 按簇而非全局统一 `columnCount`，是为了避免一门跨全天的课把其它不冲突的课也压成 1/2 宽。
     */
    internal fun layout(input: List<RawBlock>): List<RawBlock> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedWith(compareBy({ it.startMinute }, { -it.endMinute }))

        val clusters = mutableListOf<MutableList<RawBlock>>()
        var current = mutableListOf(sorted.first())
        var clusterEnd = sorted.first().endMinute
        for (b in sorted.drop(1)) {
            if (b.startMinute < clusterEnd) {
                current += b
                if (b.endMinute > clusterEnd) clusterEnd = b.endMinute
            } else {
                clusters += current
                current = mutableListOf(b)
                clusterEnd = b.endMinute
            }
        }
        clusters += current

        val out = ArrayList<RawBlock>(sorted.size)
        for (cluster in clusters) {
            val columnEnds = mutableListOf<Int>()
            for (b in cluster) {
                var col = columnEnds.indexOfFirst { it <= b.startMinute }
                if (col == -1) {
                    columnEnds += b.endMinute
                    col = columnEnds.lastIndex
                } else {
                    columnEnds[col] = b.endMinute
                }
                b.columnIndex = col
            }
            val count = columnEnds.size
            for (b in cluster) {
                b.columnCount = count
                out += b
            }
        }
        return out.sortedWith(compareBy({ it.startMinute }, { it.columnIndex }))
    }

    /**
     * 把 [Exam] 适配成一条 `source = EXAM` 的 [Course]，让它能复用课程的渲染管线。
     *
     * 节次由考试时间反查作息表得出（覆盖到的最小连续区间）。
     */
    private fun Exam.toCourse(week: Int): Course {
        val run = sectionsForTimeRange(startTime, endTime)
        return Course(
            term = term,
            name = courseName,
            teacher = category,
            classroom = classroom,
            dayOfWeek = date.dayOfWeek.value,
            startSection = run?.start ?: 1,
            sectionCount = run?.count ?: 1,
            weeks = setOf(week),
            description = buildString {
                append("考试")
                if (arrangementType.isNotBlank()) append(" · ").append(arrangementType)
                if (campus.displayName.isNotBlank()) append(" · ").append(campus.displayName)
            },
            teachingClass = "",
            courseCode = courseCode,
            source = CourseSource.EXAM,
        )
    }

    /**
     * 反查某个时段覆盖了哪些节次，返回最小连续区间。
     *
     * 判定用**重叠**而非包含：`08:30--10:05` 覆盖第 1、2 节（8:30-9:15、9:20-10:05），
     * 虽然 10:05 正好是第 2 节结束，第 3 节 10:25 才开始，不该被算进来。
     *
     * 时间缺失时返回 null，调用方回退到"占第 1 节"。
     */
    public fun sectionsForTimeRange(start: LocalTime?, end: LocalTime?): SectionRun? {
        if (start == null) return null
        val to = end ?: start
        val hit = timetable.periods.filter { p -> p.start < to && p.end > start }
        if (hit.isEmpty()) {
            // 落在课间或作息表之外：找最近的一节
            val nearest = timetable.periods.minByOrNull { p ->
                minOf(
                    kotlin.math.abs(java.time.Duration.between(p.start, start).toMinutes()),
                    kotlin.math.abs(java.time.Duration.between(start, p.end).toMinutes()),
                )
            } ?: return null
            return SectionRun(nearest.index, 1)
        }
        val from = hit.minOf { it.index }
        val till = hit.maxOf { it.index }
        return SectionRun(from, till - from + 1)
    }
}
