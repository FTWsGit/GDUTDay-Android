package com.gdutday.feature.grade

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Exam
import com.gdutday.core.model.Term
import org.junit.Test
import java.time.LocalDate

class ExamLogicTest {

    private val term = Term(2025, 1)

    private fun exam(
        name: String = "高等数学",
        date: LocalDate = LocalDate.of(2025, 12, 20),
        term: Term = this.term,
    ): Exam = Exam(
        term = term,
        courseName = name,
        date = date,
        classroom = "教5-301",
        campus = Campus.UNIVERSITY_CITY,
    )

    @Test
    fun `groupByTerm 按学期分组且组内按日期升序`() {
        val exams = listOf(
            exam(name = "B", date = LocalDate.of(2025, 12, 25)),
            exam(name = "A", date = LocalDate.of(2025, 12, 20)),
            exam(name = "C", date = LocalDate.of(2026, 1, 5), term = Term(2025, 2)),
        )
        val groups = ExamLogic.groupByTerm(exams)
        assertThat(groups.map { it.term }).containsExactly(term, Term(2025, 2)).inOrder()
        val first = groups[0]
        assertThat(first.exams.map { it.courseName }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `dateLabel 包含月日与星期`() {
        // 2025-12-20 是周六
        assertThat(ExamLogic.dateLabel(LocalDate.of(2025, 12, 20))).isEqualTo("12月20日 · 周六")
    }

    @Test
    fun `relativeDays 今天明天与未来`() {
        val today = LocalDate.of(2025, 12, 20)
        assertThat(ExamLogic.relativeDays(exam(date = today), today)).isEqualTo("今天")
        assertThat(ExamLogic.relativeDays(exam(date = today.plusDays(1)), today)).isEqualTo("明天")
        assertThat(ExamLogic.relativeDays(exam(date = today.plusDays(3)), today)).isEqualTo("3 天后")
        assertThat(ExamLogic.relativeDays(exam(date = today.minusDays(1)), today)).isEqualTo("已结束")
    }

    @Test
    fun `isPast 早于今天为真`() {
        val today = LocalDate.of(2025, 12, 20)
        assertThat(ExamLogic.isPast(exam(date = today.minusDays(1)), today)).isTrue()
        assertThat(ExamLogic.isPast(exam(date = today), today)).isFalse()
    }
}
