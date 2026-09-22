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
    fun `阈值边界 - 必须严格大于阈值才算浅色`() {
        // 纯黑是深色
        assertThat(ColorContrast.isLightBackground(0xFF000000.toInt())).isFalse()
        // 灰色 #808080 的线性亮度约 0.2158，已越过 0.179 交叉点，黑字更清晰
        assertThat(ColorContrast.isLightBackground(0xFF808080.toInt())).isTrue()
        // 绯红 #D75455 线性亮度约 0.215，黑字（5.3:1）优于白字（4.0:1）
        assertThat(ColorContrast.isLightBackground(0xFFD75455.toInt())).isTrue()
        // 深红这类更暗的颜色仍用白字
        assertThat(ColorContrast.isLightBackground(0xFFB03A3A.toInt())).isFalse()
    }

    @Test
    fun `调色板每一个颜色的自动文字色都与亮度一致`() {
        for (color in com.gdutday.core.common.CourseColors.palette) {
            val luminance = ColorContrast.relativeLuminance(color.argb)
            val expected = if (luminance > ColorContrast.LIGHT_BACKGROUND_THRESHOLD) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
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
        // 绯红 #D75455 亮度约 0.215，越过交叉点：黑字对比度更高，必须选黑
        assertThat(ColorContrast.autoTextColor(0xFFD75455.toInt())).isEqualTo(0xFF000000.toInt())
        // 更暗的深红仍选白
        assertThat(ColorContrast.autoTextColor(0xFFB03A3A.toInt())).isEqualTo(0xFFFFFFFF.toInt())
        // 调色板的中亮色（green/grey/pink）在新阈值下不再选到 2:1 的白字
        assertThat(ColorContrast.autoTextColor(0xFF81C784.toInt())).isEqualTo(0xFF000000.toInt())
        assertThat(ColorContrast.autoTextColor(0xFFA6BAB9.toInt())).isEqualTo(0xFF000000.toInt())
        assertThat(ColorContrast.autoTextColor(0xFFE16B8C.toInt())).isEqualTo(0xFF000000.toInt())
    }
}
