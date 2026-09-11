package com.gdutday.core.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体排版。
 *
 * ## 课表场景的特殊要求
 *
 * 课程块非常窄（7 列均分屏幕，每列约 50dp），要同时塞下
 * **课程名 + 教室 + 老师**三行信息。所以：
 * - 课程名用 11sp/紧凑行高，最多 3 行，超出省略
 * - 教室和老师用 9sp，单行省略
 * - **不用** Material 默认的 BodyMedium(14sp) —— 在课程块里根本放不下
 *
 * 这些"块内文字"规格放在 [ScheduleBlockText] 而不是 Typography 里，
 * 因为它们不是通用的排版层级，只在课表网格内使用。
 */
public val GdutDayTypography: Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

/** 课程块内部的文字规格。见 [GdutDayTypography] 的注释。 */
public object ScheduleBlockText {
    /** 课程名。 */
    public val courseName: TextStyle = TextStyle(
        fontSize = 11.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    )

    /** 教室 / 老师。 */
    public val detail: TextStyle = TextStyle(
        fontSize = 9.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp,
    )

    /** 课程块顶部的时间范围（08:30-09:15）。 */
    public val timeRange: TextStyle = TextStyle(
        fontSize = 8.sp,
        lineHeight = 9.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.2).sp,
    )

    /** 课程名最多显示几行。3 行 × 12sp = 36sp ≈ 40dp，是 2 节课高度（约 90dp）的一半，留得下详情。 */
    public const val COURSE_NAME_MAX_LINES: Int = 3

    /** 左侧节次列的"第 N 节"数字。 */
    public val sectionNumber: TextStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)

    /** 左侧节次列的时间。 */
    public val sectionTime: TextStyle = TextStyle(fontSize = 8.sp, lineHeight = 9.sp)

    /** 顶部星期列的"周一"和日期。 */
    public val dayHeader: TextStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
}
