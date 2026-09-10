package com.gdutday.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ============================================================================
// 主题。
//
// ⚠ 这是一个**契约文件**：feature-* 模块和 app 的 NavHost 都依赖
// `GdutDayTheme { ... }` 这个入口签名。改签名会让 4 个 feature 模块同时编译失败。
//
// 下面给出的是可直接编译的最小实现（占位配色），
// 具体视觉设计由 core-ui 的负责人补全，见 docs/03-ui-spec.md。
// ============================================================================

/**
 * 课程块专用色。与 Material 色板分开，因为它需要独立控制透明度
 * （用户在设置里调 [com.gdutday.core.datastore.UserSettings.courseBlockAlpha]）。
 *
 * 这里只放**语义色**；具体 11 色调色板在
 * [com.gdutday.core.common.CourseColors]（纯 JVM，Widget 也能用）。
 */
@Immutable
public data class CourseBlockColors(
    /** 课程块上的主文字色。 */
    public val onBlock: Color,
    /** 课程块上的次要文字（老师、教室）。 */
    public val onBlockSecondary: Color,
    /** 已上完课程的遮罩色。 */
    public val finishedScrim: Color,
    /** 正在上课的课程块描边色。 */
    public val ongoingBorder: Color,
    /** 考试块的底色（与普通课程区分）。 */
    public val examTint: Color,
    /** 自定义课程块的底色。 */
    public val customTint: Color,
)

/** 扩展色板，挂在 MaterialTheme 之外（Compose 没有官方的"扩展色"机制，用 CompositionLocal）。 */
@Immutable
public data class GdutDayColors(
    public val courseBlock: CourseBlockColors,
    /** 课表网格的分隔线。 */
    public val gridLine: Color,
    /** 课表左侧节次列的背景。 */
    public val sectionGutter: Color,
    /** "今天"这一列的高亮底色。 */
    public val todayHighlight: Color,
)

public val LocalGdutDayColors = androidx.compose.runtime.staticCompositionLocalOf {
    GdutDayColors(
        courseBlock = CourseBlockColors(
            onBlock = Color.White,
            onBlockSecondary = Color(0xCCFFFFFF),
            finishedScrim = Color(0x66000000),
            ongoingBorder = Color(0xFFFFC107),
            examTint = Color(0x33FF5252),
            customTint = Color(0x33000000),
        ),
        gridLine = Color(0x1F000000),
        sectionGutter = Color(0xFFFAFAFA),
        todayHighlight = Color(0x0A000000),
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFE16B8C),      // 旧小程序的 defaultColor: 'pink'
    onPrimary = Color.White,
    secondary = Color(0xFF8BD2D8),
    tertiary = Color(0xFF90B44B),
    background = Color(0xFFFFFBFC),
    surface = Color(0xFFFFFBFC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB2C4),
    onPrimary = Color(0xFF5C1129),
    secondary = Color(0xFF8BD2D8),
    tertiary = Color(0xFFBFE07A),
    background = Color(0xFF171214),
    surface = Color(0xFF171214),
)

/**
 * 全 App 的主题入口。
 *
 * @param darkTheme 是否深色。默认跟随系统。
 * @param dynamicColor 是否用 Material You 动态取色（Android 12+）。
 *   **默认 false**：动态取色会覆盖掉品牌色，而课表 App 的辨识度很大程度来自
 *   那套固定的 11 色课程调色板。要开的话应该做成设置项而不是硬编码。
 */
@Composable
public fun GdutDayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorSchemeCompat(context) else dynamicLightColorSchemeCompat(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalGdutDayColors provides if (darkTheme) darkGdutDayColors() else LocalGdutDayColors.current.let { it },
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GdutDayTypography,
            content = content,
        )
    }
}

private fun darkGdutDayColors() = GdutDayColors(
    courseBlock = CourseBlockColors(
        onBlock = Color(0xFF1A1A1A),
        onBlockSecondary = Color(0xCC1A1A1A),
        finishedScrim = Color(0x66000000),
        ongoingBorder = Color(0xFFFFD54F),
        examTint = Color(0x33FF5252),
        customTint = Color(0x33FFFFFF),
    ),
    gridLine = Color(0x1FFFFFFF),
    sectionGutter = Color(0xFF1F1A1C),
    todayHighlight = Color(0x0AFFFFFF),
)

// 动态取色需要 @RequiresApi(S)，用反射太重，这里直接引用 androidx 的顶层函数。
// 它们本身就是 @RequiresApi(S) 的，调用前已判断 SDK_INT。
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
private fun dynamicLightColorSchemeCompat(context: android.content.Context) =
    androidx.compose.material3.dynamicLightColorScheme(context)

@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
private fun dynamicDarkColorSchemeCompat(context: android.content.Context) =
    androidx.compose.material3.dynamicDarkColorScheme(context)
