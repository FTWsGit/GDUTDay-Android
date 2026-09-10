package com.gdutday.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [WidgetSizing] 的行为契约。
 *
 * 这些数字直接决定用户在桌面上看到几行课，任何改动都应先在这里改测试再改实现。
 */
class WidgetSizingTest {

    @Test
    fun `4x2 标准尺寸（110dp）显示 3 行`() {
        assertThat(WidgetSizing.todayRowCount(110f)).isEqualTo(3)
    }

    @Test
    fun `高度增加时显示行数单调不降`() {
        val heights = listOf(60f, 110f, 150f, 190f, 250f, 400f)
        val counts = heights.map { WidgetSizing.todayRowCount(it) }
        assertThat(counts).isInOrder()
    }

    @Test
    fun `最大高度也不会超过 6 行`() {
        assertThat(WidgetSizing.todayRowCount(10_000f)).isEqualTo(6)
    }

    @Test
    fun `极矮高度至少保留 1 行`() {
        assertThat(WidgetSizing.todayRowCount(30f)).isEqualTo(1)
    }

    @Test
    fun `高度为 0 时回退到默认行数而不是崩溃`() {
        // LocalSize 在预览里可能是 0，必须有默认分支。
        assertThat(WidgetSizing.todayRowCount(0f)).isEqualTo(WidgetSizing.DEFAULT_TODAY_ROWS)
        assertThat(WidgetSizing.todayRowCount(-42f)).isEqualTo(WidgetSizing.DEFAULT_TODAY_ROWS)
    }

    @Test
    fun `任何输入都落在 1 到 6 之间`() {
        for (h in listOf(-100f, 0f, 1f, 26f, 27f, 110f, 999f)) {
            assertThat(WidgetSizing.todayRowCount(h)).isIn(1..6)
        }
    }
}
