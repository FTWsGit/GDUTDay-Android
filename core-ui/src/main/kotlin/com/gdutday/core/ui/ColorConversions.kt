package com.gdutday.core.ui

import androidx.compose.ui.graphics.Color
import com.gdutday.core.common.CourseColor

/**
 * 颜色类型转换。
 *
 * 数据层（`core-common`、Room）统一用不透明 `Int`（ARGB）表示颜色，
 * 因为纯 JVM 的调色板和数据库列都不该依赖 Compose。渲染时再转成
 * `androidx.compose.ui.graphics.Color`。集中在这里，避免每个 feature 各写一遍。
 */

/**
 * 不透明 ARGB `Int` → Compose [Color]。
 *
 * 命名带上 `Argb` 是为了和 `Color(red, green, blue)`、`Color(0xFF...)` 等
 * 重载区分开：这里传入的 **Int 会被当成 ARGB 打包值**，而不是灰度值。
 */
public fun Int.toArgbColor(): Color = Color(this)

/** 调色板颜色 → Compose [Color]。 */
public fun CourseColor.toComposeColor(): Color = Color(argb)

/**
 * 按背景色自动选取可读的文字色（黑或白），供 `CourseTextColor.AUTO` 使用。
 *
 * 真正的亮度计算在纯函数 [ColorContrast] 里，这里只做数值到 Compose 颜色的包装。
 */
public fun autoTextColorFor(backgroundArgb: Int): Color =
    Color(ColorContrast.autoTextColor(backgroundArgb))
