package com.gdutday.feature.schedule

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Term
import org.junit.Test

/**
 * [buildEditedCourse] 的纯函数测试。
 *
 * 这个 sheet 只编辑用户点开的这一个色块（见文件顶部 KDoc）：不接受 name（名字锁死
 * 不可改）、不接受作用范围（教务课程恒为"只覆盖当前这一周"）。
 */
class CourseEditSheetLogicTest {

    private val term = Term(2025, 1)

    private fun school() = Course(
        id = 1L, term = term, name = "高等数学", dayOfWeek = 1,
        startSection = 1, sectionCount = 2, weeks = (1..8).toSet(),
        source = CourseSource.SCHOOL,
    )

    @Test
    fun `按节次编辑不写绝对时间`() {
        val edited = buildEditedCourse(
            original = school(), teacher = "", classroom = "教5-301",
            dayOfWeek = 1, useRealTime = false, startSection = 3, sectionCount = 2,
            startTime = "8:00", endTime = "9:30", colorKey = "red",
            isSchool = true, currentWeek = 3,
        )
        assertThat(edited.startMinute).isEqualTo(-1)
        assertThat(edited.endMinute).isEqualTo(-1)
        assertThat(edited.startSection).isEqualTo(3)
        assertThat(edited.classroom).isEqualTo("教5-301")
    }

    @Test
    fun `按具体时间编辑写入绝对分钟`() {
        val edited = buildEditedCourse(
            original = school(), teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = true, startSection = 1, sectionCount = 2,
            startTime = "08:30", endTime = "09:15", colorKey = "red",
            isSchool = true, currentWeek = 3,
        )
        assertThat(edited.startMinute).isEqualTo(8 * 60 + 30)
        assertThat(edited.endMinute).isEqualTo(9 * 60 + 15)
    }

    @Test
    fun `教务课程编辑只覆盖当前这一周`() {
        // 回归：以前有 ALL 作用范围可选，一不小心就把"全部周次"都改了；
        // 现在这个 sheet 恒定只编辑当前这一周，跨周批量改只能走高级编辑。
        val edited = buildEditedCourse(
            original = school(), teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = true, currentWeek = 5,
        )
        assertThat(edited.weeks).containsExactly(5)
    }

    @Test
    fun `自定义课程保留全部周次`() {
        val custom = school().copy(id = 2L, source = CourseSource.CUSTOM)
        val edited = buildEditedCourse(
            original = custom, teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = false, currentWeek = 3,
        )
        assertThat(edited.weeks).isEqualTo((1..8).toSet())
    }

    @Test
    fun `名字恒等于原课程 这个sheet从不改名字`() {
        val edited = buildEditedCourse(
            original = school(), teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = true, currentWeek = 1,
        )
        assertThat(edited.name).isEqualTo("高等数学")
    }

    @Test
    fun `补丁字段在编辑产物里总是清空 由仓库层填写`() {
        val edited = buildEditedCourse(
            original = school(), teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = true, currentWeek = 1,
        )
        assertThat(edited.overrideScope).isNull()
        assertThat(edited.overrideTargetNaturalKey).isNull()
        assertThat(edited.overrideWeeks).isEmpty()
    }

    @Test
    fun `时间解析对缺前导零的输入宽容`() {
        val edited = buildEditedCourse(
            original = school(), teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = true, startSection = 1, sectionCount = 2,
            startTime = "8:30", endTime = "10:05", colorKey = "red",
            isSchool = true, currentWeek = 1,
        )
        assertThat(edited.startMinute).isEqualTo(8 * 60 + 30)
        assertThat(edited.endMinute).isEqualTo(10 * 60 + 5)
    }

    @Test
    fun `绝对时间标签显示为时刻`() {
        assertThat(minuteToClock(8 * 60 + 30)).isEqualTo("08:30")
        assertThat(minuteToClock(0)).isEqualTo("00:00")
    }
}
