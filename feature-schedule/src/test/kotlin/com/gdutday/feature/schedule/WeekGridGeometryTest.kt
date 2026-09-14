package com.gdutday.feature.schedule

import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.model.Campus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 节次栏与网格共用同一套"溢出槽"几何，这里的回归点就是两者必须一致。
 *
 * 背景：默认只显示 12 节（末节 20:55），但 [com.gdutday.core.common.WeekGrid.verticalRange]
 * 的尾部取自**完整作息表**的 22:35，于是永远存在一格尾部溢出槽。若 [overflowSlots] 与
 * [buildPeriodRanges] 用了不同的边界，网格高度就会少算一格、每格被压扁，节次栏与课程错位。
 */
class WeekGridGeometryTest {

    private val timetable = CampusTimetable.of(Campus.UNIVERSITY_CITY)
    private val shownPeriods = timetable.periods.take(12)
    private val fullRange =
        CourseBlock.clockToMinute(timetable.firstPeriodStart)..CourseBlock.clockToMinute(timetable.lastPeriodEnd)

    @Test
    fun `溢出槽数与补槽后的区间数始终一致`() {
        for (periods in listOf(shownPeriods, timetable.periods)) {
            val extra = overflowSlots(periods, fullRange)
            val ranges = buildPeriodRanges(periods, fullRange)
            assertThat(periods.size + extra).isEqualTo(ranges.size)
        }
    }

    @Test
    fun `只显示12节时尾部仍有一格溢出槽`() {
        assertThat(overflowSlots(shownPeriods, fullRange)).isEqualTo(1)
        assertThat(buildPeriodRanges(shownPeriods, fullRange)).hasSize(13)
    }

    @Test
    fun `显示全部14节时没有溢出槽`() {
        assertThat(overflowSlots(timetable.periods, fullRange)).isEqualTo(0)
        assertThat(buildPeriodRanges(timetable.periods, fullRange)).hasSize(14)
    }

    @Test
    fun `早于第一节课时首尾各补一格`() {
        val earlyRange = 400..CourseBlock.clockToMinute(timetable.lastPeriodEnd)
        assertThat(overflowSlots(shownPeriods, earlyRange)).isEqualTo(2)
        assertThat(buildPeriodRanges(shownPeriods, earlyRange)).hasSize(14)
    }
}
