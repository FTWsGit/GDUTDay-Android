package com.gdutday.feature.schedule

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 网格几何纯函数的测试。
 *
 * 这些公式控制"课块画在哪里、多宽、多高"。真机上肉眼看不出是哪一个
 * 公式偏了，所以必须用固定输入钉死。
 */
class ScheduleGridMathTest {

    /** 大学城作息：8:30(510) ~ 20:55(1255)，共 745 分钟。 */
    private val range = 510..1255
    private val gridHeight = 745f

    @Test
    fun `showWeekend 决定可见列`() {
        assertThat(ScheduleGridMath.visibleDayIndices(showWeekend = true))
            .containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(ScheduleGridMath.visibleDayIndices(showWeekend = false))
            .containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun `隐藏周末时列索引重新映射且周六周日不可见`() {
        // 周一是第 0 列，周五是第 4 列
        assertThat(ScheduleGridMath.slotOf(1, showWeekend = false)).isEqualTo(0)
        assertThat(ScheduleGridMath.slotOf(5, showWeekend = false)).isEqualTo(4)
        // 周六周日在 5 列模式下没有位置，返回 null 而不是硬塞到第 5/6 列
        assertThat(ScheduleGridMath.slotOf(6, showWeekend = false)).isNull()
        assertThat(ScheduleGridMath.slotOf(7, showWeekend = false)).isNull()
        // 显示周末时周日才是第 6 列
        assertThat(ScheduleGridMath.slotOf(7, showWeekend = true)).isEqualTo(6)
    }

    @Test
    fun `周次钳制到 1 到 totalWeeks`() {
        assertThat(ScheduleGridMath.clampWeek(0, totalWeeks = 20)).isEqualTo(1)
        assertThat(ScheduleGridMath.clampWeek(-5, totalWeeks = 20)).isEqualTo(1)
        assertThat(ScheduleGridMath.clampWeek(21, totalWeeks = 20)).isEqualTo(20)
        assertThat(ScheduleGridMath.clampWeek(12, totalWeeks = 20)).isEqualTo(12)
        // totalWeeks 非法时保底 1，绝不产生 0 或负数周次
        assertThat(ScheduleGridMath.clampWeek(5, totalWeeks = 0)).isEqualTo(1)
    }

    @Test
    fun `分钟到纵坐标按比例线性换算`() {
        // 上界 0，下界满高
        assertThat(ScheduleGridMath.minuteToY(510, range, gridHeight)).isWithin(1e-3f).of(0f)
        assertThat(ScheduleGridMath.minuteToY(1255, range, gridHeight)).isWithin(1e-3f).of(745f)
        // 8:30 + 90 分钟 = 10:00
        assertThat(ScheduleGridMath.minuteToY(600, range, gridHeight)).isWithin(1e-3f).of(90f)
        // 越界被钳制，不会画到网格外
        assertThat(ScheduleGridMath.minuteToY(0, range, gridHeight)).isEqualTo(0f)
        assertThat(ScheduleGridMath.minuteToY(9999, range, gridHeight)).isEqualTo(745f)
    }

    @Test
    fun `色块高度等于时长占比 - 考试按时刻而非节次`() {
        // 08:30-10:05 共 95 分钟，占 745 中的 95
        assertThat(ScheduleGridMath.blockHeight(510, 605, range, gridHeight)).isWithin(1e-3f).of(95f)
        // 单节 45 分钟
        assertThat(ScheduleGridMath.blockHeight(510, 555, range, gridHeight)).isWithin(1e-3f).of(45f)
        // 结束早于开始时高度为 0，不产生负高度
        assertThat(ScheduleGridMath.blockHeight(600, 500, range, gridHeight)).isEqualTo(0f)
    }

    @Test
    fun `并排冲突时按 columnCount 均分列宽`() {
        val dayWidth = 350f
        assertThat(ScheduleGridMath.blockWidth(dayWidth, 1)).isEqualTo(350f)
        assertThat(ScheduleGridMath.blockWidth(dayWidth, 2)).isEqualTo(175f)
        assertThat(ScheduleGridMath.blockWidth(dayWidth, 4)).isEqualTo(87.5f)
        // 非法 columnCount 保底为 1，不产生除零
        assertThat(ScheduleGridMath.blockWidth(dayWidth, 0)).isEqualTo(350f)
    }

    @Test
    fun `色块横坐标 = 节次栏 + 天偏移 + 冲突列偏移`() {
        val gutter = 44f
        val dayWidth = 350f

        // 周一(第 0 列)的一份独占色块，正好从节次栏右侧开始
        assertThat(ScheduleGridMath.blockX(0, dayWidth, 0, 1, gutter)).isEqualTo(44f)
        // 周三(第 2 列)
        assertThat(ScheduleGridMath.blockX(2, dayWidth, 0, 1, gutter)).isEqualTo(44f + 700f)
        // 周二一簇两门并排：columnIndex=1 时右移半个列宽
        assertThat(ScheduleGridMath.blockX(1, dayWidth, 1, 2, gutter)).isEqualTo(44f + 350f + 175f)
    }

    // 大学城作息：12 节，节间课间 5~20 分钟不等，第 4/5 节之间有 110 分钟午休。
    // 用 120px 总高（12 节 × 10px/节）验证"每节等高、课间不占高度"。
    private val periodRanges = listOf(
        510..555,   // 1 08:30-09:15
        560..605,   // 2 09:20-10:05
        625..670,   // 3 10:25-11:10
        675..720,   // 4 11:15-12:00
        830..875,   // 5 13:50-14:35
        880..925,   // 6 14:40-15:25
        930..975,   // 7 15:30-16:15
        990..1035,  // 8 16:30-17:15
        1040..1085, // 9 17:20-18:05
        1110..1155, // 10 18:30-19:15
        1160..1205, // 11 19:20-20:05
        1210..1255, // 12 20:10-20:55
    )
    private val periodGridHeight = 120f

    @Test
    fun `节次等高映射 - 每节槽位边界固定`() {
        // 第 1 节开始 = 0，第 12 节结束 = 满高
        assertThat(ScheduleGridMath.minuteToYByPeriods(510, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(0f)
        assertThat(ScheduleGridMath.minuteToYByPeriods(1255, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(120f)
        // 每节 10px：第 4 节开始(675) = 30，第 5 节开始(830) = 40 —— 午休被折叠
        assertThat(ScheduleGridMath.minuteToYByPeriods(675, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(30f)
        assertThat(ScheduleGridMath.minuteToYByPeriods(830, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(40f)
        // 越界钳制
        assertThat(ScheduleGridMath.minuteToYByPeriods(0, periodRanges, periodGridHeight)).isEqualTo(0f)
        assertThat(ScheduleGridMath.minuteToYByPeriods(9999, periodRanges, periodGridHeight)).isEqualTo(120f)
    }

    @Test
    fun `节次等高映射 - 课间分钟折叠到相邻节边界`() {
        // 第 1 节结束(555) 与第 2 节开始(560) 之间的课间折叠：都映射到 10px
        assertThat(ScheduleGridMath.minuteToYByPeriods(555, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(10f)
        assertThat(ScheduleGridMath.minuteToYByPeriods(560, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(10f)
        // 午休：第 4 节结束(720) 与第 5 节开始(830) 都落在 40px
        assertThat(ScheduleGridMath.minuteToYByPeriods(720, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(40f)
        assertThat(ScheduleGridMath.minuteToYByPeriods(830, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(40f)
    }

    @Test
    fun `节次等高映射 - 跨节色块不留课间空隙`() {
        // 单节 45 分钟 = 10px
        assertThat(ScheduleGridMath.blockHeightByPeriods(510, 555, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(10f)
        // 08:30-10:05 跨第 1、2 节，中间的 5 分钟课间折叠，高度 = 2 节
        assertThat(ScheduleGridMath.blockHeightByPeriods(510, 605, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(20f)
        // 跨午休的第 4~5 节(11:15-14:35)也是恰好 2 节高
        assertThat(ScheduleGridMath.blockHeightByPeriods(675, 875, periodRanges, periodGridHeight))
            .isWithin(1e-3f).of(20f)
        // 结束早于开始时高度为 0
        assertThat(ScheduleGridMath.blockHeightByPeriods(600, 500, periodRanges, periodGridHeight)).isEqualTo(0f)
    }
}
