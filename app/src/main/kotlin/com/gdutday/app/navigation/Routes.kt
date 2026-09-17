package com.gdutday.app.navigation

/**
 * 路由表。
 *
 * 用常量而不是散在各处的字符串字面量：写错一个字符编译期就能发现，
 * 而字符串字面量写错只会在运行时表现为"点了没反应"。
 */
public object Routes {
    /** 课表主页面（起始路由）。 */
    public const val SCHEDULE: String = "schedule"

    /** 成绩页。 */
    public const val GRADE: String = "grade"

    /** 工具箱页。 */
    public const val TOOLBOX: String = "toolbox"

    /** 空闲教室查询页（工具箱下钻）。 */
    public const val FREE_ROOM: String = "free_room"

    /** 设置页。 */
    public const val SETTINGS: String = "settings"

    /** 登录页。 */
    public const val LOGIN: String = "login"
}
