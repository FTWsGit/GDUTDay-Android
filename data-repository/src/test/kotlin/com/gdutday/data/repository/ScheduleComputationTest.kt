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

    // ------------------------------------------------------------ 补丁应用（OVERRIDE）

    private fun schoolCourse(
        id: Long,
        name: String = "高等数学",
        weeks: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7, 8),
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        sectionCount: Int = 2,
        classroom: String = "教1-101",
        teacher: String = "张三",
    ): Course = Course(
        id = id,
        term = term,
        name = name,
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        sectionCount = sectionCount,
        weeks = weeks,
        courseCode = "CS101",
        teachingClass = "1班",
        source = com.gdutday.core.model.CourseSource.SCHOOL,
    )

    private fun patch(
        id: Long = 100L,
        target: Course,
        scope: com.gdutday.core.model.OverrideScope,
        weeks: Set<Int> = target.weeks,
        classroom: String = "教5-301",
        teacher: String = "张三",
    ): Course = Course(
        id = id,
        term = target.term,
        name = target.name,
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = target.dayOfWeek,
        startSection = target.startSection,
        sectionCount = target.sectionCount,
        weeks = weeks,
        courseCode = target.courseCode,
        teachingClass = target.teachingClass,
        source = com.gdutday.core.model.CourseSource.OVERRIDE,
        overrideScope = scope,
        overrideTargetNaturalKey = target.naturalKey,
        overrideWeeks = weeks,
    )

    @Test
    fun `全部范围补丁替换整门课`() {
        val school = listOf(schoolCourse(id = 1L))
        val p = patch(target = school.first(), scope = com.gdutday.core.model.OverrideScope.ALL, classroom = "教5-999")

        val result = applyUserOverrides(school, listOf(p))

        // 教务行退场，只剩补丁行
        assertThat(result).hasSize(1)
        val only = result.single()
        assertThat(only.source).isEqualTo(com.gdutday.core.model.CourseSource.OVERRIDE)
        assertThat(only.classroom).isEqualTo("教5-999")
        assertThat(only.weeks).isEqualTo(school.first().weeks)
    }

    @Test
    fun `单周补丁拆走一周其余周次保留原课程`() {
        val school = listOf(schoolCourse(id = 1L))
        val p = patch(
            target = school.first(),
            scope = com.gdutday.core.model.OverrideScope.THIS_WEEK,
            weeks = setOf(3),
            classroom = "教5-301",
        )

        val result = applyUserOverrides(school, listOf(p))

        assertThat(result).hasSize(2)
        val remaining = result.first { it.source == com.gdutday.core.model.CourseSource.SCHOOL }
        assertThat(remaining.weeks).containsExactly(1, 2, 4, 5, 6, 7, 8)
        val override = result.first { it.source == com.gdutday.core.model.CourseSource.OVERRIDE }
        assertThat(override.weeks).containsExactly(3)
        assertThat(override.classroom).isEqualTo("教5-301")
    }

    @Test
    fun `周范围补丁拆走连续周次`() {
        val school = listOf(schoolCourse(id = 1L))
        val p = patch(
            target = school.first(),
            scope = com.gdutday.core.model.OverrideScope.WEEK_RANGE,
            weeks = setOf(3, 4, 5),
            teacher = "李四",
        )

        val result = applyUserOverrides(school, listOf(p))

        val remaining = result.first { it.source == com.gdutday.core.model.CourseSource.SCHOOL }
        assertThat(remaining.weeks).containsExactly(1, 2, 6, 7, 8)
        val override = result.first { it.source == com.gdutday.core.model.CourseSource.OVERRIDE }
        assertThat(override.weeks).containsExactly(3, 4, 5)
        assertThat(override.teacher).isEqualTo("李四")
    }

    @Test
    fun `补丁接走全部周次后原课程整行消失`() {
        val school = listOf(schoolCourse(id = 1L))
        val p = patch(
            target = school.first(),
            scope = com.gdutday.core.model.OverrideScope.THIS_WEEK,
            weeks = school.first().weeks,
        )

        val result = applyUserOverrides(school, listOf(p))

        assertThat(result).hasSize(1)
        assertThat(result.single().source).isEqualTo(com.gdutday.core.model.CourseSource.OVERRIDE)
    }

    @Test
    fun `匹配不到目标的补丁不影响教务课程`() {
        val school = listOf(schoolCourse(id = 1L))
        // 教务改了节次 → 自然键不再匹配
        val orphan = patch(
            target = schoolCourse(id = 9L, startSection = 5, sectionCount = 2),
            scope = com.gdutday.core.model.OverrideScope.ALL,
        )

        val result = applyUserOverrides(school, listOf(orphan))

        // 教务课程原样保留（孤儿补丁由设置页提示"未生效"，数据层不删）
        assertThat(result.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `多条补丁按顺序应用`() {
        val school = listOf(schoolCourse(id = 1L))
        val all = patch(
            id = 101L,
            target = school.first(),
            scope = com.gdutday.core.model.OverrideScope.WEEK_RANGE,
            weeks = setOf(1, 2),
            classroom = "A",
        )
        val range = patch(
            id = 102L,
            target = school.first(),
            scope = com.gdutday.core.model.OverrideScope.WEEK_RANGE,
            weeks = setOf(5, 6),
            classroom = "B",
        )

        val result = applyUserOverrides(school, listOf(all, range))

        val remaining = result.first { it.source == com.gdutday.core.model.CourseSource.SCHOOL }
        assertThat(remaining.weeks).containsExactly(3, 4, 7, 8)
        val classrooms = result
            .filter { it.source == com.gdutday.core.model.CourseSource.OVERRIDE }
            .map { it.classroom }
        assertThat(classrooms).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `非 OVERRIDE 条目被忽略`() {
        val school = listOf(schoolCourse(id = 1L))
        val custom = Course(
            id = 50L, term = term, name = "社团", dayOfWeek = 6, startSection = 10,
            sectionCount = 2, weeks = setOf(1),
            source = com.gdutday.core.model.CourseSource.CUSTOM,
        )

        val result = applyUserOverrides(school, listOf(custom))

        assertThat(result.map { it.id }).containsExactly(1L)
    }
}
