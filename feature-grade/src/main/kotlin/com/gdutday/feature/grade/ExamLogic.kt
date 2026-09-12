package com.gdutday.feature.grade

import com.gdutday.core.model.Exam
import com.gdutday.core.model.Term
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 一个学期的考试安排分组。
 */
public data class ExamGroup(
    public val term: Term,
    public val exams: List<Exam>,
)

/**
 * 考试安排页的纯逻辑。
 *
 * 抽出来与 [GradeLogic] 同理：日期/星期格式化、按学期分组、已考/未考判定
 * 一旦写错，用户看到的是错的考试时间，直接影响信任度。
 */
public object ExamLogic {

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 按学期分组，学期内按日期升序。学期之间按时间正序（旧在前）。 */
    public fun groupByTerm(exams: List<Exam>): List<ExamGroup> =
        exams.groupBy { it.term }
            .map { (term, list) -> ExamGroup(term, list.sortedBy { it.date }) }
            .sortedBy { it.term }

    /** `"12月20日 · 周六"`。 */
    public fun dateLabel(date: LocalDate): String {
        val dow = WEEKDAYS.getOrNull(date.dayOfWeek.value - 1) ?: ""
        return String.format(Locale.CHINA, "%d月%d日 · %s", date.monthValue, date.dayOfMonth, dow)
    }

    /** 考试是否已结束（日期在今天之前）。 */
    public fun isPast(exam: Exam, today: LocalDate): Boolean = exam.date.isBefore(today)

    /** 距离今天的天数摘要：`"今天"`、`"明天"`、`"3 天后"`、`"已结束"`。 */
    public fun relativeDays(exam: Exam, today: LocalDate): String {
        val days = exam.daysUntil(today)
        return when {
            days == 0L -> "今天"
            days == 1L -> "明天"
            days > 0 -> "$days 天后"
            else -> "已结束"
        }
    }

    /** 周次摘要（考试接口的 `zc` 已落进 [Exam] 吗？没有，这里不强依赖）。保留扩展位。 */
    public const val NO_WEEK: Int = -1

    /** 同一日期的考试聚成一组时用的日期格式器（ISO），用于稳定分组 key。 */
    public val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
}
