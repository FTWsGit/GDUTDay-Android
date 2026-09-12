package com.gdutday.widget

import com.gdutday.core.common.BlockStatus
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.common.CourseColors
import com.gdutday.core.model.Course
import com.gdutday.core.model.Term
import com.gdutday.data.repository.ScheduleUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class TodayScheduleMapperTest {

    private val term = Term(2025, 1)

    /** 2025-09-10 是周三。 */
    private val wednesday: LocalDate = LocalDate.of(2025, 9, 10)

    private fun course(
        name: String = "高等数学",
        teacher: String = "张老师",
        classroom: String = "教5-301",
    ): Course = Course(
        term = term,
        name = name,
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = 3,
        startSection = 1,
        sectionCount = 2,
        weeks = setOf(1, 2),
    )

    private fun block(status: BlockStatus, name: String = "高等数学"): CourseBlock = CourseBlock(
        course = course(name = name),
        color = CourseColors.DEFAULT,
        startMinute = 8 * 60 + 30,
        endMinute = 10 * 60 + 5,
        status = status,
    )

    private fun state(blocks: List<CourseBlock>): ScheduleUiState = ScheduleUiState(
        courses = listOf(course()),
        availableTerms = listOf(term),
        todayBlocks = blocks,
        todayWeek = 2,
        totalWeeks = 20,
    )

    @Test
    fun `仓库还没给出数据时是加载中`() {
        val result = TodayScheduleMapper.map(null, loggedIn = false, today = wednesday)
        assertThat(result.phase).isEqualTo(TodayPhase.LOADING)
    }

    @Test
    fun `未登录且没有任何本地数据时是未登录`() {
        val result = TodayScheduleMapper.map(
            ScheduleUiState(),
            loggedIn = false,
            today = wednesday,
        )
        assertThat(result.phase).isEqualTo(TodayPhase.NOT_LOGGED_IN)
    }

    @Test
    fun `今天没有课时是空状态`() {
        val result = TodayScheduleMapper.map(state(emptyList()), loggedIn = true, today = wednesday)
        assertThat(result.phase).isEqualTo(TodayPhase.NO_CLASS)
        assertThat(result.rows).isEmpty()
    }

    @Test
    fun `全部上完时显示课程已结束`() {
        val result = TodayScheduleMapper.map(
            state(listOf(block(BlockStatus.FINISHED), block(BlockStatus.FINISHED))),
            loggedIn = true,
            today = wednesday,
        )
        assertThat(result.phase).isEqualTo(TodayPhase.ALL_FINISHED)
    }

    @Test
    fun `有未结束的课时保留列表并带有状态`() {
        val result = TodayScheduleMapper.map(
            state(listOf(block(BlockStatus.FINISHED), block(BlockStatus.ONGOING))),
            loggedIn = true,
            today = wednesday,
        )
        assertThat(result.phase).isEqualTo(TodayPhase.HAS_CLASS)
        assertThat(result.rows.map { it.status })
            .containsExactly(BlockStatus.FINISHED, BlockStatus.ONGOING)
    }

    @Test
    fun `课程名-老师-教室与时间照常显示`() {
        val result = TodayScheduleMapper.map(
            state(listOf(block(BlockStatus.UPCOMING))),
            loggedIn = true,
            today = wednesday,
        )
        val row = result.rows.single()
        assertThat(row.name).isEqualTo("高等数学")
        assertThat(row.teacher).isEqualTo("张老师")
        assertThat(row.classroom).isEqualTo("教5-301")
        assertThat(row.startClock).isEqualTo("08:30")
    }

    @Test
    fun `已有本地课表时即使会话失效也照常显示`() {
        val result = TodayScheduleMapper.map(
            state(listOf(block(BlockStatus.UPCOMING))),
            loggedIn = false,
            today = wednesday,
        )
        assertThat(result.phase).isEqualTo(TodayPhase.HAS_CLASS)
    }

    @Test
    fun `表头包含日期星期与周次`() {
        val result = TodayScheduleMapper.map(state(emptyList()), loggedIn = true, today = wednesday)
        assertThat(result.dateLine).isEqualTo("9月10日 周三 · 第2周")
    }
}
