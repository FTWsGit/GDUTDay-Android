package com.gdutday.widget

import com.gdutday.core.datastore.UserSettings

/**
 * 隐私打码。
 *
 * ## 打码的边界（重要）
 *
 * 需求是"公共场合防偷窥"，不是"让插件变得没用"，所以只打码**课程名和老师名**，
 * 时间与教室保持可读。如果连时间和地点都打掉，用户在地铁上想确认"下一节课在哪"
 * 也做不到，插件就彻底失去意义了。
 *
 * 用 `·` 而不是 `*`：`*` 在等宽显示下容易和列表符号混淆，`·` 更接近"内容被遮住"的观感，
 * 且中英文混排下宽度稳定。
 */
public object WidgetText {

    /** 打码字符。 */
    public const val MASK: String = "·"

    /**
     * 生成与原文等长的掩码。保留原长度是为了让打码后的布局与真实数据一致，
     * 不会因为"课程名变短了"导致行高/对齐跳动。
     *
     * @param value 原文
     * @param blur 是否打码
     */
    public fun mask(value: String, blur: Boolean): String =
        if (!blur || value.isEmpty()) value else MASK.repeat(value.length)

    /**
     * 两个开关都打开才在插件里打码。
     *
     * `privacyBlurInWidget` 默认 true，含义是"跟随总开关"：用户只要开了 `privacyBlurEnabled`，
     * 插件默认一起打码；但用户也可以单独关掉插件打码（比如手机只有自己用，桌面想看真名）。
     */
    public fun shouldBlur(settings: UserSettings): Boolean =
        settings.privacyBlurEnabled && settings.privacyBlurInWidget
}
