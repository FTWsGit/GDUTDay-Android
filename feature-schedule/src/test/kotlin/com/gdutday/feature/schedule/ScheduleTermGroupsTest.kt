package com.gdutday.feature.schedule

import com.gdutday.core.model.Term
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ScheduleTermGroupsTest {

    /** 2026-09-14 属于 2026-2027 学年第 1 学期，窗口 = 2022..2028。 */
    private val today: LocalDate = LocalDate.of(2026, 9, 14)

    private fun term(year: Int, semester: Int): Term = Term(year, semester)

    @Test
    fun `只保留当前学年往前4年往后2年的窗口`() {
        val all = listOf(
            term(2020, 1), term(2021, 1), term(2022, 1), term(2023, 1),
            term(2024, 1), term(2025, 1), term(2026, 1), term(2027, 1),
            term(2028, 1), term(2029, 1),
        )
        val groups = buildYearTermGroups(all, currentTerm = null, today)
        assertThat(groups.map { it.year })
            .containsExactly(2028, 2027, 2026, 2025, 2024, 2023, 2022)
            .inOrder()
    }

    @Test
    fun `窗口外但当前选中的学期仍保留`() {
        val selected = term(2019, 2)
        val groups = buildYearTermGroups(listOf(term(2019, 2), term(2026, 1)), currentTerm = selected, today)
        assertThat(groups.map { it.year }).containsExactly(2026, 2019).inOrder()
        assertThat(groups.first { it.year == 2019 }.terms).containsExactly(selected)
    }

    @Test
    fun `上半年属于上一学年`() {
        val january = LocalDate.of(2026, 1, 15)
        val groups = buildYearTermGroups(listOf(term(2025, 1), term(2026, 1)), currentTerm = null, january)
        // 2026 年 1 月属 2025-2026 学年，窗口 = 2021..2027，两学期都在内。
        assertThat(groups.map { it.year }).containsExactly(2026, 2025).inOrder()
    }

    @Test
    fun `同年份按学期倒序`() {
        val groups = buildYearTermGroups(listOf(term(2026, 2), term(2026, 1)), currentTerm = null, today)
        assertThat(groups.single().terms.map { it.semester }).containsExactly(2, 1).inOrder()
    }

    @Test
    fun `格式化学年与学期标签`() {
        assertThat(formatAcademicYear(2025)).isEqualTo("2025-2026学年")
        assertThat(semesterLabel(term(2025, 1))).isEqualTo("第一学期")
        assertThat(semesterLabel(term(2025, 2))).isEqualTo("第二学期")
    }
}
