package com.gdutday.feature.schedule

import com.gdutday.core.common.CourseColors
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Term
import java.time.LocalTime

/**
 * 生效周次的选择方式。
 *
 * 自定义课程没有"作用范围拆分"的问题（见 `OverrideScope`），这里只是
 * "这门课在第几周上"的四种常见填法，保存时统一展开成 [Course.weeks]。
 */
public enum class WeekMode {
    /** 只在当前浏览的周生效。 */
    CURRENT,

    /** 只在指定的一周生效。 */
    SINGLE,

    /** 在 [startWeek, endWeek] 的连续范围内生效。 */
    RANGE,

    /** 全学期每周都上。 */
    ALL,
}

/**
 * "新增课程"表单的纯数据。UI 只改这份快照，转成 [Course] 的规则集中在 [toCourse]，
 * 便于用纯 JVM 测试钉死周次与绝对时间的换算。
 *
 * 时间支持两种表达（与 [CourseEditSheet] 一致）：
 * - 按节次：[startSection] + [sectionCount]，沿用学期作息表换算；
 * - 按具体时间：[startTime]/[endTime]（`HH:mm`），写入 `startMinute`/`endMinute`。
 */
public data class AddCourseForm(
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val sectionCount: Int = 2,
    val useRealTime: Boolean = false,
    val startTime: String = "08:30",
    val endTime: String = "10:05",
    val weekMode: WeekMode = WeekMode.CURRENT,
    val singleWeek: Int = 1,
    val rangeStart: Int = 1,
    val rangeEnd: Int = 16,
    val colorKey: String = CourseColors.DEFAULT.key,
) {
    /** 展开后的生效周次。range 起止倒置时为空（保存按钮会因此禁用）。 */
    public fun weeks(currentWeek: Int, totalWeeks: Int): Set<Int> = when (weekMode) {
        WeekMode.CURRENT -> setOf(currentWeek)
        WeekMode.SINGLE -> setOf(singleWeek)
        WeekMode.RANGE -> if (rangeStart <= rangeEnd) (rangeStart..rangeEnd).toSet() else emptySet()
        WeekMode.ALL -> (1..totalWeeks.coerceAtLeast(1)).toSet()
    }

    /** 表单是否能保存：名称非空、星期合法、周次展开后非空。 */
    public fun isValid(currentWeek: Int, totalWeeks: Int): Boolean =
        name.isNotBlank() && dayOfWeek in 1..7 && weeks(currentWeek, totalWeeks).isNotEmpty()

    /**
     * 转成待入库的 [Course]。source 一律 [CourseSource.CUSTOM]，
     * 补丁字段清空 —— 它们只属于 OVERRIDE 行。
     */
    public fun toCourse(term: Term, currentWeek: Int, totalWeeks: Int): Course {
        val startMin = if (useRealTime) parseClockMinute(startTime) ?: -1 else -1
        val endMin = if (useRealTime) parseClockMinute(endTime) ?: -1 else -1
        return Course(
            id = 0L,
            term = term,
            name = name.trim(),
            teacher = teacher.trim(),
            classroom = classroom.trim(),
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            sectionCount = sectionCount,
            weeks = weeks(currentWeek, totalWeeks),
            source = CourseSource.CUSTOM,
            colorKey = colorKey,
            startMinute = startMin,
            endMinute = endMin,
            overrideScope = null,
            overrideTargetNaturalKey = null,
            overrideWeeks = emptySet(),
        )
    }

    public companion object {
        /** `"08:30"` → 510。容忍缺前导零；解析失败返回 null。 */
        public fun parseClockMinute(raw: String): Int? {
            val parts = raw.trim().split(':')
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }
    }
}

/** `HH:mm` 合法性校验，UI 的保存按钮用。 */
internal fun isValidClock(raw: String): Boolean =
    AddCourseForm.parseClockMinute(raw) != null || runCatching { LocalTime.parse(raw) }.isSuccess
