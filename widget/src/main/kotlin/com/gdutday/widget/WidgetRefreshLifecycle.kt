package com.gdutday.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 两个插件的启用 / 停用生命周期收口。
 *
 * ## 为什么不能各写各的
 *
 * 刷新链（[NextClassRefreshScheduler] 的唯一任务）是**两个插件共用**的，但
 * `onEnabled` / `onDisabled` 是 **per-provider** 回调：
 *
 * - 只放"今日课程"插件、没放"下节课"插件时，如果只有后者的 receiver 挂钩子，
 *   刷新链永远不会启动，跨天时"今日课程"没有任何刷新源（M18）；
 * - 反过来，若两个 receiver 都无条件在 `onDisabled` 里 `cancel`，
 *   移除其中一个插件会把另一个仍在使用的插件的刷新链砍掉。
 *
 * 所以：任一方被添加都 `refreshNow` 启动/延续刷新链；任一方被移除时，
 * **只有当两个插件都没有实例了**才取消。
 */
internal object WidgetRefreshLifecycle {

    fun onEnabled(context: Context) {
        // 此刻还没有可信的"下一节课"，先立刻跑一次，让 worker 自己算出正确间隔。
        NextClassRefreshScheduler.refreshNow(context)
    }

    fun onDisabled(context: Context) {
        val appContext = context.applicationContext
        // onDisabled 在主线程，查看插件实例数需要挂起调用，故放到后台协程。
        // 即使进程随后被杀，最坏也只是少取消一次 WorkManager 任务，不影响正确性。
        CoroutineScope(Dispatchers.IO).launch {
            val manager = GlanceAppWidgetManager(appContext)
            val hasNext = runCatching {
                manager.getGlanceIds(NextClassWidget::class.java)
            }.getOrNull()?.isNotEmpty() == true
            val hasToday = runCatching {
                manager.getGlanceIds(TodayScheduleWidget::class.java)
            }.getOrNull()?.isNotEmpty() == true
            if (!hasNext && !hasToday) {
                NextClassRefreshScheduler.cancel(appContext)
            }
        }
    }
}
