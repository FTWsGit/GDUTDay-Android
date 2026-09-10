package com.gdutday.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

/**
 * 插件刷新的统一入口。
 *
 * ## 谁应该调用它
 *
 * - 同步完成后：课表数据变了，让桌面立刻反映；
 * - 跨天时：[NextClassRefreshPolicy] 会在 0 点排一次刷新，把"今天"推进到新的一天；
 * - 用户手动点插件上的刷新：见 [WidgetRefreshAction]。
 *
 * ## 为什么不在这里持有 Room 的长期订阅
 *
 * 最直觉的做法是在 Application 里 `collect` 一遍 Room Flow，一变就 `updateAll`。
 * 代价是**进程会被迫常驻**：插件数据一天只变几次，却要为此让 App 进程一直活着，
 * 违背本项目"省电、少驻留"的目标。所以这里保留"被动刷新"——
 * 有人明确知道数据变了才推一次，刷新时机与数据变更时机严格对齐。
 *
 * 真正的"实时"由 Glance 的 `GlanceStateDefinition` 兜底：每次 `update()` 时
 * 插件都会重新读一遍 Room（见 [TodayScheduleWidgetStateDefinition]），
 * 因此即使调用方漏了某次通知，下一次刷新也一定能拿到最新数据。
 */
public object WidgetUpdateManager {

    /**
     * 刷新两个插件的全部实例，并重排下节课倒计时。
     *
     * 这是给"数据变了"的调用点用的便捷方法。若只关心其中一个，可用
     * [updateToday] / [updateNext] / [rescheduleCountdown] 单独触发。
     */
    public suspend fun updateAll(context: Context) {
        updateToday(context)
        updateNext(context)
        rescheduleCountdown(context)
    }

    /** 只刷新"今日课程"插件的全部实例。 */
    public suspend fun updateToday(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val widget = TodayScheduleWidget()
        manager.getGlanceIds(TodayScheduleWidget::class.java).forEach { id ->
            widget.update(context, id)
        }
    }

    /** 只刷新"下节课"插件的全部实例。 */
    public suspend fun updateNext(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val widget = NextClassWidget()
        manager.getGlanceIds(NextClassWidget::class.java).forEach { id ->
            widget.update(context, id)
        }
    }

    /**
     * 按最新的"下一节课"重新排一次倒计时刷新。
     *
     * 数据变化后必须重排：例如刚同步出了新课，原本"今天已无课 → 排到 0 点"的计划要
     * 改成"每分钟刷新"。用 [NextClassRefreshScheduler.scheduleNext] 内部的
     * `ExistingWorkPolicy.REPLACE` 保证只保留最新一份计划。
     */
    public suspend fun rescheduleCountdown(context: Context) {
        val next = context.widgetContainer().scheduleRepository.observeNextClass().first()
        NextClassRefreshScheduler.scheduleNext(context, next, LocalDateTime.now())
    }
}

/**
 * "今日课程"插件表头上的刷新按钮。
 *
 * 点整张插件是打开 App（需求要求），刷新按钮是额外的、不冲突的点击目标：
 * 公共场合用户可能只想更新一下数据，不想跳进 App。动作本身只做一次
 * [WidgetUpdateManager.updateAll] 然后结束，不启动 Activity。
 */
public class WidgetRefreshAction : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: androidx.glance.GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        WidgetUpdateManager.updateAll(context)
    }
}
