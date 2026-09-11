package com.gdutday.data.repository

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.database.CourseColorEntity
import com.gdutday.core.database.CourseEntity
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.Term
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课表页纯计算的单元测试。
 *
 * 重点覆盖三块最容易出错、又只能在真机上肉眼发现问题的逻辑：
 * 冲突判定（单双周）、配色持久化（只有新课程参与分配）、UI 状态组装（周次与网格）。
 */
class ScheduleComputationTest {

    private val term = Term(2025, 1)
    private val monday = LocalDate.of(2025, 9, 1)

    private fun course(
        id: Long = 0L,
        name: String = "高等数学",
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        sectionCount: Int = 2,
        weeks: Set<Int> = setOf(1, 2, 3),
    ): Course = Course(
        id = id,
        term = term,
        name = name,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        sectionCount = sectionCount,
        weeks = weeks,
    )

    private fun courseEntity(
        name: String = "高等数学",
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        sectionCount: Int = 2,
        weeks: String = "1,2,3",
    ): CourseEntity = CourseEntity(
        termCode = "20251",
        name = name,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        sectionCount = sectionCount,
        weeks = weeks,
    )

    private fun meta(
        code: String,
        current: Boolean = false,
        start: String = "2025-09-01",
        source: SemesterStartSource = SemesterStartSource.DERIVED,
    ): TermMetaEntity = TermMetaEntity(
        termCode = code,
        xnxqdm = if (code.length == 5) code + "0" else code,
        displayName = "display-$code",
        isCurrent = current,
        semesterStart = start,
        startSource = source.name,
    )

    // ------------------------------------------------------------ 冲突判定

    @Test
    fun `节次区间相交判定`() {
        assertThat(sectionRangesOverlap(1, 2, 2, 3)).isTrue()
        assertThat(sectionRangesOverlap(1, 2, 3, 4)).isFalse()
        assertThat(sectionRangesOverlap(5, 6, 1, 4)).isFalse()
        assertThat(sectionRangesOverlap(3, 3, 3, 3)).isTrue()
    }

    @Test
    fun `同天同节次同周次才算冲突`() {
        val existing = course(id = 1, weeks = setOf(1, 2, 3))
        val overlapping = course(id = 0, weeks = setOf(3, 4))
        assertThat(findScheduleConflicts(listOf(existing), overlapping)).hasSize(1)
    }

    @Test
    fun `周次不重叠的单双周课是合法的`() {
        val odd = course(id = 1, weeks = setOf(1, 3, 5))
        val even = course(id = 0, weeks = setOf(2, 4, 6))
        assertThat(findScheduleConflicts(listOf(odd), even)).isEmpty()
    }

    @Test
    fun `不同天或不同节次不算冲突`() {
        val existing = course(id = 1, dayOfWeek = 2)
        assertThat(findScheduleConflicts(listOf(existing), course(dayOfWeek = 1))).isEmpty()
        assertThat(findScheduleConflicts(listOf(existing), course(dayOfWeek = 2, startSection = 3, sectionCount = 2))).isEmpty()
    }

    @Test
    fun `编辑时忽略自己`() {
        val existing = course(id = 7)
        assertThat(findScheduleConflicts(listOf(existing), course(id = 7))).isEmpty()
    }

    // ------------------------------------------------------------ 配色持久化

    @Test
    fun `只有全新课程参与自动分配并落盘`() {
        val existing = listOf(
            CourseColorEntity(courseName = "数学", colorKey = "red", isUserChosen = true),
        )
        val plan = CourseColorPolicy.plan(listOf("数学", "英语"), existing)

        assertThat(plan.assignment["数学"]).isEqualTo("red") // 用户手选保留
        assertThat(plan.assignment).containsKey("英语")
        // 已存在的课程名不重复落盘，只有英语一行
        assertThat(plan.toPersist.map { it.courseName }).containsExactly("英语")
        assertThat(plan.toPersist.single().isUserChosen).isFalse()
    }

    @Test
    fun `没有任何已存在配色时全部落盘`() {
        val plan = CourseColorPolicy.plan(listOf("A", "B"), emptyList())
        assertThat(plan.assignment.keys).containsExactly("A", "B")
        assertThat(plan.toPersist.map { it.courseName }).containsExactly("A", "B")
    }

    // ------------------------------------------------------------ UI 状态组装

    private fun buildState(
        selectedWeek: Int? = null,
        now: LocalDateTime = LocalDateTime.of(2025, 9, 1, 8, 0),
        settings: UserSettings = UserSettings(campus = Campus.UNIVERSITY_CITY),
    ): ScheduleUiState = buildScheduleUiState(
        ScheduleInputs(
            meta = meta("20251", current = true),
            allMeta = listOf(meta("20251", current = true), meta("20242", start = "2025-02-24")),
            courses = with(com.gdutday.core.database.Mappers) {
                listOf(courseEntity()).mapNotNull { it.toDomain() }
            },
            exams = emptyList(),
            settings = settings,
            syncState = SyncStateEntity(
                lastSyncAt = Instant.parse("2025-09-10T10:00:00Z"),
                lastTermCode = "20251",
                lastSuccess = true,
            ),
            isSyncing = false,
            selectedWeek = selectedWeek,
            colorKeys = emptyMap(),
            now = now,
        ),
    )

    @Test
    fun `组装出学期 日历 网格与配色`() {
        val state = buildState()

        assertThat(state.term).isEqualTo(term)
        assertThat(state.availableTerms).containsExactly(Term(2025, 1), Term(2024, 2)).inOrder()
        assertThat(state.currentTerm).isEqualTo(term)
        assertThat(state.semesterStartSource).isEqualTo(SemesterStartSource.DERIVED)
        assertThat(state.calendar).isNotNull()
        assertThat(state.totalWeeks).isEqualTo(20)
        assertThat(state.todayWeek).isEqualTo(1)
        assertThat(state.selectedWeek).isEqualTo(1)
        assertThat(state.grid?.blockCount).isEqualTo(1)
        assertThat(state.todayBlocks).hasSize(1)
        assertThat(state.courseNames).containsExactly("高等数学")
        assertThat(state.colorAssignment).containsKey("高等数学")
        assertThat(state.lastSync?.success).isTrue()
        assertThat(state.isInitialLoad).isFalse()
    }

    @Test
    fun `选中的周次被钳制在合法范围`() {
        assertThat(buildState(selectedWeek = 3).selectedWeek).isEqualTo(3)
        assertThat(buildState(selectedWeek = 999).selectedWeek).isEqualTo(20)
    }

    @Test
    fun `自定义作息解析失败时回退内置表`() {
        val settings = UserSettings(
            campus = Campus.UNIVERSITY_CITY,
            customTimetableEnabled = true,
            customTimetable = listOf("不是时间"),
        )
        val timetable = resolveTimetable(settings, Campus.UNIVERSITY_CITY)
        assertThat(timetable.campus).isEqualTo(Campus.UNIVERSITY_CITY)
        assertThat(timetable.size).isEqualTo(14)
    }

    @Test
    fun `下一节课取今天第一段未结束的课`() {
        val state = buildState()
        val next = computeNextClass(state, LocalDateTime.of(2025, 9, 1, 8, 0))

        assertThat(next).isNotNull()
        assertThat(next!!.block.course.name).isEqualTo("高等数学")
        assertThat(next.isOngoing).isFalse()
        // 大学城第 1 节 8:30，参照 8:00 → 还有 30 分钟
        assertThat(next.startsInMinutes).isEqualTo(30)
    }
}
