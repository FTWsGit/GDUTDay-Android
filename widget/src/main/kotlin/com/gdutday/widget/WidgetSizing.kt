package com.gdutday.widget

/**
 * 插件尺寸 → 可见行数的映射。
 *
 * ## 为什么必须动态算，而不是写死 3 条
 *
 * 用户在桌面上可以把 4x2 拉到 4x5。如果写死条数，拉大之后下方会留一大片空白，
 * 这是"插件没做适配"最直观的表现。Glance 的 `LocalSize` 会随 launcher 回调更新，
 * 据此算出能塞下几行即可。
 *
 * ## 参数为什么是 Float 而不是 DpSize
 *
 * 这样这一层就是纯 JVM 代码，单元测试不需要拉起 Compose/Android 环境。
 * Glance 侧只做 `LocalSize.current.height.value` 一次转换。
 *
 * 数值取整策略：
 * - 表头（日期 + 周次）约 26dp，每行课程约 30dp——课程行加了结束时间、字号也调大了
 *   （名称 12sp→14sp，备注 10sp→11sp），比之前单纯一个开始时间要占更多纵向空间，
 *   ROW_DP 从 24 提到 30 才不会把最后一行挤到裁切；
 * - 取 floor 而不是 round —— 宁可少一行也不能把最后一行切掉一半，切一半比不显示更难看；
 * - 下限 1 行（极小尺寸仍显示最重要的那条），上限 6 行（再多用户也不会看）。
 *
 * 默认 4x2（110dp）算出来是 2 行，这也正好符合"几格就大致显示几条"的直觉——
 * 桌面插件的"格数"本身是启动器概念、不同启动器每格的 dp 不一样，没法精确对应，
 * 但按实际可用高度算行数已经是同一个方向的效果：插件越高，能看到的课越多。
 */
public object WidgetSizing {

    /** `LocalSize` 在预览/异常情况下可能是 0，此时给一个接近 4x2 的默认值。 */
    public const val DEFAULT_TODAY_ROWS: Int = 2

    private const val HEADER_DP: Float = 26f
    private const val ROW_DP: Float = 30f
    private const val MIN_ROWS: Int = 1
    private const val MAX_ROWS: Int = 6

    /**
     * @param heightDp 插件可用高度（dp）。传 `LocalSize.current.height.value`。
     * @return 今天课程列表最多显示几条，始终在 1..6。
     */
    public fun todayRowCount(heightDp: Float): Int {
        // 预览布局和某些 launcher 会给 0；此时不要退化成 1 行，按 4x2 的预期给默认值。
        if (heightDp <= 0f) return DEFAULT_TODAY_ROWS
        val usable = heightDp - HEADER_DP
        if (usable <= 0f) return MIN_ROWS
        return (usable / ROW_DP).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
    }
}
