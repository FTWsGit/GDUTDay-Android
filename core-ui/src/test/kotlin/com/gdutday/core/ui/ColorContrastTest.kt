package com.gdutday.core.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 相对亮度与自动对比色的测试。
 *
 * 这是 "课程块上文字看不清" 这类 bug 的根源，必须对**全部 11 个调色板颜色**
 * 以及黑白极值做穷举，而不是只测一两个手挑的颜色。
 */
class ColorContrastTest {

    @Test
    fun `纯黑亮度为 0 纯白亮度为 1`() {
        assertThat(ColorContrast.relativeLuminance(0xFF000000.toInt())).isWithin(1e-6).of(0.0)
        assertThat(ColorContrast.relativeLuminance(0xFFFFFFFF.toInt())).isWithin(1e-6).of(1.0)
    }

    @Test
    fun `WCAG 官方样例 - 红绿蓝的亮度排序`() {
        // 人眼对绿最敏感、蓝最不敏感，权重 0.2126/0.7152/0.0722 必须体现出来
        val red = ColorContrast.relativeLuminance(0xFFFF0000.toInt())
        val green = ColorContrast.relativeLuminance(0xFF00FF00.toInt())
        val blue = ColorContrast.relativeLuminance(0xFF0000FF.toInt())
        assertThat(green).isGreaterThan(red)
        assertThat(red).isGreaterThan(blue)
        assertThat(red).isWithin(1e-6).of(0.2126)
        assertThat(green).isWithin(1e-6).of(0.7152)
        assertThat(blue).isWithin(1e-6).of(0.0722)
    }

    @Test
    fun `alpha 通道不影响亮度判定`() {
        // AUTO 用的是调色板里的不透明 argb，透明与否必须得到同一结论
        assertThat(ColorContrast.relativeLuminance(0x00FFFFFF)).isEqualTo(
            ColorContrast.relativeLuminance(0xFFFFFFFF.toInt()),
        )
        assertThat(ColorContrast.autoTextColor(0x00FFFF00)).isEqualTo(ColorContrast.autoTextColor(0xFFFFFF00.toInt()))
    }

    @Test
    fun `深色背景用白字 浅色背景用黑字`() {
        assertThat(ColorContrast.autoTextColor(0xFF000000.toInt())).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(ColorContrast.autoTextColor(0xFFFFFFFF.toInt())).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `阈值边界 - 必须严格大于 0_5 才算浅色`() {
        // 灰色 #808080 的线性亮度约 0.2158，属于深色
        assertThat(ColorContrast.isLightBackground(0xFF808080.toInt())).isFalse()
        // 明黄 #F7D94C 明显是浅色
        assertThat(ColorContrast.isLightBackground(0xFFF7D94C.toInt())).isTrue()
    }

    @Test
    fun `调色板每一个颜色的自动文字色都与亮度一致`() {
        for (color in com.gdutday.core.common.CourseColors.palette) {
            val luminance = ColorContrast.relativeLuminance(color.argb)
            val expected = if (luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            assertWithMessage("${color.key}(${color.displayName}) 的文字色")
                .that(ColorContrast.autoTextColor(color.argb))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `调色板里可预见的对比问题都被正确处理`() {
        // 反例：明黄/橄榄这类浅色若配白字会几乎看不见，必须选黑
        assertThat(ColorContrast.autoTextColor(0xFFF7D94C.toInt())).isEqualTo(0xFF000000.toInt())
        assertThat(ColorContrast.autoTextColor(0xFFE4C63F.toInt())).isEqualTo(0xFF000000.toInt())
        // 绯红这类深色配黑字会糊成一团，必须选白
        assertThat(ColorContrast.autoTextColor(0xFFD75455.toInt())).isEqualTo(0xFFFFFFFF.toInt())
    }
}
