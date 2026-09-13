package com.gdutday.feature.schedule

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.common.TermCalendar
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.Term
import com.gdutday.data.repository.ScheduleUiState
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 周视图三页预渲染（前/后周网格生成）的纯 JVM 单元测试。
 */
class NeighborWeekGridsTest {

    private val term = Term(2025, 1)
    private val monday = LocalDate.of(2025, 9, 1)
    private val now = LocalDateTime.of(2025, 9, 8, 8, 0)

    private fun course(weeks: Set<Int>, name: String = "高等数学"): Course = Course(
        term = term,
        name = name,
        dayOfWeek = 1,
        startSection = 1,
        sectionCount = 2,
        weeks = weeks,
    )

    private fun state(selectedWeek: Int, totalWeeks: Int = 20): ScheduleUiState = ScheduleUiState(
        term = term,
        calendar = TermCalendar(monday, totalWeeks),
        timetable = CampusTimetable.of(Campus.UNIVERSITY_CITY),
        selectedWeek = selectedWeek,
        totalWeeks = totalWeeks,
        courses = listOf(course(setOf(1, 2, 3)), course(setOf(2), "大学英语")),
    )

    @Test
    fun `前后周网格按选中周偏移一生成`() {
        val grids = buildNeighborWeekGrids(state(selectedWeek = 2), now)

        assertThat(grids.prev?.week).isEqualTo(1)
        assertThat(grids.next?.week).isEqualTo(3)
        // 相邻周页面内容按各自周次的课程数据生成：
        // 第 1 周只有高等数学（1 块），第 3 周高等数学仍在而大学英语（仅第 2 周）已消失。
        assertThat(grids.prev?.blockCount).isEqualTo(1)
        assertThat(grids.next?.blockCount).isEqualTo(1)
        assertThat(grids.next?.days?.first()?.blocks?.single()?.course?.name).isEqualTo("高等数学")
    }

    @Test
    fun `第1周的前一周为null`() {
        val grids = buildNeighborWeekGrids(state(selectedWeek = 1), now)

        assertThat(grids.prev).isNull()
        assertThat(grids.next?.week).isEqualTo(2)
    }

    @Test
    fun `最后一周的后一周为null`() {
        val grids = buildNeighborWeekGrids(state(selectedWeek = 20, totalWeeks = 20), now)

        assertThat(grids.prev?.week).isEqualTo(19)
        assertThat(grids.next).isNull()
    }
}
