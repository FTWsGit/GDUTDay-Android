package com.gdutday.core.common

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Term
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 冲突聚类（[ConflictCluster] + `buildConflictClusters`）的测试。
 *
 * 聚类口径必须与 [ScheduleGridBuilder.layout] 一致：按"传递时间重叠"分簇，
 * 簇内 ≥3 门时 UI 切换为纸边堆叠渲染。
 */
class ConflictClusterTest {

    private val term = Term(2025, 1)
    private val timetable = CampusTimetable.of(Campus.UNIVERSITY_CITY)
    private val calendar = TermCalendar(LocalDate.of(2025, 9, 1))
    private val builder = ScheduleGridBuilder(timetable, calendar)

    /** 2025-09-08 是第 2 周的周一。 */
    private val now = LocalDateTime.of(LocalDate.of(2025, 9, 8), java.time.LocalTime.of(7, 0))

    private fun course(
        name: String,
        day: Int = 1,
        start: Int,
        count: Int = 2,
        weeks: Set<Int> = (1..16).toSet(),
        source: CourseSource = CourseSource.SCHOOL,
    ) = Course(
        term = term, name = name, dayOfWeek = day,
        startSection = start, sectionCount = count,
        weeks = weeks, source = source,
    )

    private fun blocks(vararg courses: Course) =
        builder.buildWeek(courses.toList(), week = 2, now = now).day(1).blocks

    @Test
    fun `互不重叠的课各成单块簇`() {
        val clusters = blocks(course("A", start = 1), course("B", start = 5)).buildConflictClusters()
        assertThat(clusters).hasSize(2)
        assertThat(clusters.all { it.blocks.size == 1 }).isTrue()
        assertThat(clusters.all { it.overflowCount == 0 }).isTrue()
    }

    @Test
    fun `两门重叠课聚为一簇且溢出数为1`() {
        val clusters = blocks(course("A", start = 1), course("B", start = 1)).buildConflictClusters()
        assertThat(clusters).hasSize(1)
        val cluster = clusters.single()
        assertThat(cluster.blocks).hasSize(2)
        assertThat(cluster.overflowCount).isEqualTo(1)
    }

    @Test
    fun `三门传递重叠的课聚为一簇`() {
        val clusters = blocks(
            course("A", start = 1, count = 4),
            course("B", start = 2, count = 3),
            course("C", start = 3, count = 2),
        ).buildConflictClusters()
        assertThat(clusters).hasSize(1)
        assertThat(clusters.single().blocks).hasSize(3)
        assertThat(clusters.single().overflowCount).isEqualTo(2)
    }

    @Test
    fun `首尾相接的课不聚为一簇`() {
        // A 结束于 10:05，B 从 10:25（第 3 节）开始，中间隔着课间
        val clusters = blocks(course("A", start = 1), course("B", start = 3)).buildConflictClusters()
        assertThat(clusters).hasSize(2)
    }

    @Test
    fun `primary 取排序后第一门`() {
        val clusters = blocks(
            course("晚课", start = 2),
            course("早课", start = 1),
        ).buildConflictClusters()
        assertThat(clusters).hasSize(1)
        assertThat(clusters.single().primary.course.name).isEqualTo("早课")
    }

    @Test
    fun `空列表返回空簇`() {
        assertThat(emptyList<CourseBlock>().buildConflictClusters()).isEmpty()
    }

    @Test
    fun `DayColumn 扩展与列表扩展结果一致`() {
        val grid = builder.buildWeek(
            listOf(course("A", start = 1), course("B", start = 1), course("C", start = 2)),
            week = 2, now = now,
        )
        val viaColumn = grid.day(1).buildConflictClusters()
        val viaList = grid.day(1).blocks.buildConflictClusters()
        assertThat(viaColumn).isEqualTo(viaList)
        assertThat(viaColumn.single().blocks).hasSize(3)
    }

    @Test
    fun `不同天的课不会跨簇`() {
        val day1 = blocks(course("周一的课", day = 1, start = 1))
        val day2 = builder.buildWeek(listOf(course("周二的课", day = 2, start = 1)), week = 2, now = now).day(2).blocks
        assertThat(day1).hasSize(1)
        assertThat(day2).hasSize(1)
        assertThat(day1.single().course.name).isEqualTo("周一的课")
        assertThat(day2.single().course.name).isEqualTo("周二的课")
    }
}
