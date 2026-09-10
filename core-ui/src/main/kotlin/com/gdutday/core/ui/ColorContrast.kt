package com.gdutday.core.ui

/**
 * 背景色的相对亮度与对比文字色计算。
 *
 * ## 为什么单独抽成纯函数
 *
 * 课程块的字体色设置为 `AUTO` 时，需要"背景深就用白字、背景浅就用黑字"。
 * 这个判断做错的表现是**特定颜色的课上文字几乎看不见**（例如明黄底配白字），
 * 而 UI 测试很难稳定复现所有 11 种调色板 × 深色/浅色主题的组合。
 * 抽成不依赖 Android / Compose 的纯 JVM 函数后，可以直接跑穷举单测。
 *
 * ## 公式（WCAG 2.x 相对亮度）
 *
 * 1. 每个 sRGB 通道先归一化到 `[0, 1]`；
 * 2. 做 sRGB 线性化：`c ≤ 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ^ 2.4`；
 * 3. 加权求和 `0.2126 R + 0.7152 G + 0.0722 B`（人眼对绿最敏感、蓝最不敏感）。
 *
 * 注意：这里**刻意不使用** WCAG 的对比度比值阈值 `(L1+0.05)/(L2+0.05) > 4.5`。
 * 课程块面积小、字号只有 11sp，黑/白两个极值里选哪个本来就只是二选一；
 * 用单一的相对亮度阈值 0.5 更简单，且与旧小程序 `isLightColor` 的观感一致。
 *
 * 透明度被忽略：`AUTO` 判据用的是调色板里的**不透明** `argb`。
 * 真实背景还叠了用户可调的 alpha，但把 alpha 也算进去会让同一门课在不同
 * 透明度下字体忽然变色，反倒更令人困惑。
 */
public object ColorContrast {

    /**
     * 相对亮度判定阈值。大于它视为"浅色背景"，文字用黑；否则用白。
     *
     * 0.5 是主观值：绝对中灰（#808080）的线性亮度约 0.216，落在"深色"一侧，
     * 也就是说只有相当明亮的颜色才会被判定为浅色。
     */
    public const val LIGHT_BACKGROUND_THRESHOLD: Double = 0.5

    /** 计算不透明 ARGB 颜色的 WCAG 相对亮度，范围 `[0.0, 1.0]`。 */
    public fun relativeLuminance(argb: Int): Double {
        val r = linearize(((argb ushr 16) and 0xFF) / 255.0)
        val g = linearize(((argb ushr 8) and 0xFF) / 255.0)
        val b = linearize((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** 背景是否是浅色（决定 AUTO 文字色用黑还是白）。 */
    public fun isLightBackground(argb: Int): Boolean =
        relativeLuminance(argb) > LIGHT_BACKGROUND_THRESHOLD

    /**
     * 按背景色挑黑字或白字。
     *
     * @param argb 背景色（不透明 ARGB）
     * @param dark 浅色背景上使用的文字色，默认黑
     * @param light 深色背景上使用的文字色，默认白
     */
    public fun autoTextColor(
        argb: Int,
        dark: Int = 0xFF000000.toInt(),
        light: Int = 0xFFFFFFFF.toInt(),
    ): Int = if (isLightBackground(argb)) dark else light

    /** sRGB 通道线性化，输入已归一化到 `[0, 1]`。 */
    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) {
            channel / 12.92
        } else {
            Math.pow((channel + 0.055) / 1.055, 2.4)
        }
}
