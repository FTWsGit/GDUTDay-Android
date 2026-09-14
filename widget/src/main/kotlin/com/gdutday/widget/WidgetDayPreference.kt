package com.gdutday.widget

import android.content.Context

/**
 * "今日课程"插件的"今天↔明天"选择，用 [android.content.SharedPreferences] 落盘。
 *
 * ## 为什么不进 `UserSettings`
 *
 * 这是**单个插件的展示状态**（今天还是明天），不是全局偏好，也不该出现在设置页里。
 * 放进 `core-datastore` 的 `UserSettings` 会让一个纯 UI 开关污染整个设置快照。
 *
 * ## 为什么不是 DataStore
 *
 * 它只有一个 0/1 整数，读写都发生在插件刷新/点击的一瞬间；用 DataStore 只是为一行
 * 整数引入一个协程 Flow 和额外的文件。SharedPreferences 足够，且天然是同步读。
 *
 * ## 为什么所有插件实例共用一个值
 *
 * 多个"今日课程"实例（桌面 + 负一屏）看到的是同一天，这是更符合直觉的行为；
 * 按实例存会需要 GlanceId 维度，收益为零。
 */
internal object WidgetDayPreference {

    private const val PREFS_NAME = "today_schedule_widget"
    private const val KEY_DAY_OFFSET = "day_offset" // 0 = 今天，1 = 明天

    /** 当前展示的偏移：0 或 1。 */
    fun read(context: Context): Int =
        prefs(context).getInt(KEY_DAY_OFFSET, 0).coerceIn(0, 1)

    /**
     * 在"今天"与"明天"之间翻转，落盘后返回新值。
     *
     * 用 `commit()` 而不是 `apply()`：紧接着的 `update()` 会在别的线程重新读这个值，
     * 而 `apply()` 只保证"将来某个时刻"写到磁盘，内存里虽然已更新，但跨平台 / 多进程
     * 下不保证另一个读取者立刻可见，于是点了竖条看起来"没反应"。
     */
    fun toggle(context: Context): Int {
        val next = if (read(context) == 0) 1 else 0
        prefs(context).edit().putInt(KEY_DAY_OFFSET, next).commit()
        return next
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
