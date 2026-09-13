package com.gdutday.feature.schedule

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.Term
import org.junit.Test

/** [buildEditedCourse] 与周次文本解析的纯函数测试。 */
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
            original = school(), name = "高等数学", teacher = "", classroom = "教5-301",
            dayOfWeek = 1, useRealTime = false, startSection = 3, sectionCount = 2,
            startTime = "8:00", endTime = "9:30", colorKey = "red",
            isSchool = true, scope = OverrideScope.THIS_WEEK, rangeWeeksText = "3", currentWeek = 3,
        )
        assertThat(edited.startMinute).isEqualTo(-1)
        assertThat(edited.endMinute).isEqualTo(-1)
        assertThat(edited.startSection).isEqualTo(3)
        assertThat(edited.classroom).isEqualTo("教5-301")
    }

    @Test
    fun `按具体时间编辑写入绝对分钟`() {
        val edited = buildEditedCourse(
            original = school(), name = "高等数学", teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = true, startSection = 1, sectionCount = 2,
            startTime = "08:30", endTime = "09:15", colorKey = "red",
            isSchool = true, scope = OverrideScope.THIS_WEEK, rangeWeeksText = "3", currentWeek = 3,
        )
        assertThat(edited.startMinute).isEqualTo(8 * 60 + 30)
        assertThat(edited.endMinute).isEqualTo(9 * 60 + 15)
    }

    @Test
    fun `单周作用范围的周次是当前周`() {
        val edited = buildEditedCourse(
            original = school(), name = "高等数学", teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = true, scope = OverrideScope.THIS_WEEK, rangeWeeksText = "", currentWeek = 5,
        )
        assertThat(edited.weeks).containsExactly(5)
    }

    @Test
    fun `全部作用范围的周次是原课程全部周次`() {
        val edited = buildEditedCourse(
            original = school(), name = "高等数学", teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = true, scope = OverrideScope.ALL, rangeWeeksText = "", currentWeek = 5,
        )
        assertThat(edited.weeks).isEqualTo((1..8).toSet())
    }

    @Test
    fun `自定义课程保留全部周次`() {
        val custom = school().copy(id = 2L, source = CourseSource.CUSTOM)
        val edited = buildEditedCourse(
            original = custom, name = "社团", teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = false, scope = OverrideScope.THIS_WEEK, rangeWeeksText = "3", currentWeek = 3,
        )
        assertThat(edited.weeks).isEqualTo((1..8).toSet())
    }

    @Test
    fun `补丁字段在编辑产物里总是清空 由仓库层填写`() {
        val edited = buildEditedCourse(
            original = school(), name = "X", teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = false, startSection = 1, sectionCount = 2,
            startTime = "", endTime = "", colorKey = "red",
            isSchool = true, scope = OverrideScope.ALL, rangeWeeksText = "", currentWeek = 1,
        )
        assertThat(edited.overrideScope).isNull()
        assertThat(edited.overrideTargetNaturalKey).isNull()
        assertThat(edited.overrideWeeks).isEmpty()
    }

    @Test
    fun `周次文本解析容忍中文逗号与空白`() {
        assertThat(parseWeeksText("3,4,5")).containsExactly(3, 4, 5)
        assertThat(parseWeeksText("3，10")).containsExactly(3, 10)
        assertThat(parseWeeksText(" 3 , abc ")).containsExactly(3)
        assertThat(parseWeeksText("")).isEmpty()
    }

    @Test
    fun `时间解析对缺前导零的输入宽容`() {
        val edited = buildEditedCourse(
            original = school(), name = "X", teacher = "", classroom = "",
            dayOfWeek = 1, useRealTime = true, startSection = 1, sectionCount = 2,
            startTime = "8:30", endTime = "10:05", colorKey = "red",
            isSchool = true, scope = OverrideScope.THIS_WEEK, rangeWeeksText = "", currentWeek = 1,
        )
        assertThat(edited.startMinute).isEqualTo(8 * 60 + 30)
        assertThat(edited.endMinute).isEqualTo(10 * 60 + 5)
    }

    @Test
    fun `默认作用周次优先取当前周`() {
        assertThat(affectedWeeksText((1..16).toSet(), 5)).isEqualTo("5")
        assertThat(affectedWeeksText(setOf(1, 3, 5), 2)).isEqualTo("1")
        assertThat(affectedWeeksText(emptySet(), 5)).isEmpty()
    }

    @Test
    fun `绝对时间标签显示为时刻`() {
        assertThat(minuteToClock(8 * 60 + 30)).isEqualTo("08:30")
        assertThat(minuteToClock(0)).isEqualTo("00:00")
    }
}
