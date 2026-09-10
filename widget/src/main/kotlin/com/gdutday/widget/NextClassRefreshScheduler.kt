package com.gdutday.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gdutday.data.repository.NextClass
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * "下节课"倒计时的自我重排调度器。
 *
 * ## 为什么用"一次性任务自我重排"而不是 `PeriodicWorkRequest`
 *
 * 周期任务只能有一个固定间隔，而倒计时的间隔是**动态**的（见 [NextClassRefreshPolicy]）：
 * 现在 3 小时后有课应该 1 小时醒一次，10 分钟后有课应该 1 分钟醒一次。
 * 用固定周期只能取最小间隔（1 分钟），那在"离上课还有 3 小时"时就是纯粹的电量浪费。
 *
 * 所以每次 [NextClassRefreshWorker] 执行完，都根据**最新**的下一节课距离重新
 * `enqueue` 下一次任务。WorkManager 在 Doze 下会合并唤醒，比 AlarmManager 省电，
 * 且进程被杀/重启后任务仍能恢复。
 *
 * ## 如何避免任务堆积（关键）
 *
 * 用**唯一工作名** + `ExistingWorkPolicy.REPLACE`：
 * - 唯一名保证整个系统里同名任务最多只有一份；
 * - REPLACE 保证新 `enqueue` 会取消旧的待执行任务再排队。
 *
 * 因此即使 [scheduleNext] / [refreshNow] 被频繁调用（同步完成、用户手动刷新、
 * worker 自我重排），队列里也不会出现多份任务。选 REPLACE 而不是 KEEP：KEEP 会保留
 * 第一次排的计划，导致"刚同步出 10 分钟后的课，却还在用 1 小时前的旧计划"。
 */
public object NextClassRefreshScheduler {

    /** 唯一工作名。改这个字符串等于让旧任务失去管理，谨慎。 */
    public const val UNIQUE_WORK_NAME: String = "gdutday.widget.next_class_refresh"

    /** 用于诊断和清理。 */
    private const val TAG: String = "next_class_widget"

    /**
     * 根据最新的下一节课安排下一次刷新。
     *
     * @param next 仓库给出的下一节课；null 表示近期无课
     * @param now 当前时刻（测试可注入）
     */
    public fun scheduleNext(context: Context, next: NextClass?, now: LocalDateTime = LocalDateTime.now()) {
        enqueue(context, NextClassRefreshPolicy.delayMillisFor(next, now))
    }

    /**
     * 立即刷新一次。
     *
     * 插件刚被添加（receiver 的 `onEnabled`）时没有可信的下一节课数据，
     * 先跑一次让 worker 自己算出正确的间隔。`initialDelay = 0` 让 WorkManager
     * 尽快调度，但仍受系统调度约束，不会同步阻塞调用方。
     */
    public fun refreshNow(context: Context) {
        enqueue(context, 0L)
    }

    /** 用户删除插件后取消待执行任务，避免空转。 */
    public fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME) }
    }

    private fun enqueue(context: Context, delayMillis: Long) {
        ensureWorkManager(context)
        val request = OneTimeWorkRequestBuilder<NextClassRefreshWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * 确保 WorkManager 已经初始化。
     *
     * `DefaultAppContainer` 刻意禁用了 `androidx.startup` 的默认初始化，改为在
     * `syncScheduler` 这个 lazy 字段被访问时手动 `WorkManager.initialize(...)`。
     * 插件进程与 App 同进程，所以这里先轻轻碰一下 `syncScheduler` 即可完成初始化；
     * 万一别处已经初始化过，`runCatching` 会把它吞掉，随后 `getInstance` 仍能拿到实例。
     */
    private fun ensureWorkManager(context: Context) {
        runCatching { context.widgetContainer().syncScheduler }
    }
}
