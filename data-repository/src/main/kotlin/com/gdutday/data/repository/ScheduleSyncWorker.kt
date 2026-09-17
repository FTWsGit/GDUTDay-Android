package com.gdutday.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.model.GdutException
import kotlinx.coroutines.flow.first

/**
 * 后台周期同步的 Worker。
 *
 * ## 为什么 Worker 里几乎没有逻辑
 *
 * 它只做三件事：读一次设置判断要不要跑、调用 [ScheduleRepository.sync]、把异常翻译成
 * WorkManager 的重试语义。真正的编排全在 Repository —— 这样"同步流程"只有一份实现，
 * 手动下拉刷新与后台任务走的是同一段代码，不会出现"后台同步对了、手动同步漏了一步"。
 *
 * ## `autoSyncOnLaunch` 只挡自动触发，不挡手动触发
 *
 * [SyncSchedulerImpl.schedulePeriodicSync] 和 [doWork] 里都读了这个设置，看起来重复，
 * 但两处的用途不一样：`schedulePeriodicSync` 决定"要不要排周期任务"，
 * 这里的检查是给"任务已经排进队列、用户之后才关掉开关"这种竞态兜底。
 *
 * 这个兜底**只能**用于自动触发的任务（周期任务、以及将来若有的"冷启动自动同步"），
 * 绝不能套到 [SyncSchedulerImpl.requestImmediateSync] 发起的立即同步上 —— 那是用户
 * 点了"同步"菜单或下拉刷新的显式请求，关掉"启动时自动同步"不代表用户不能手动同步，
 * 两者是完全独立的开关。因此立即同步的 [androidx.work.WorkRequest] 会带上
 * [KEY_MANUAL]，这里据此跳过设置检查。
 *
 * ## 失败分级
 *
 * - [GdutException.SessionExpired]：**不重试**。后台拿不到滑块验证码，也没有用户在场，
 *   重试只会不断失败。等用户下次打开 App 手动登录即可。
 * - [GdutException.recoverable]（网络抖动/5xx）：`Result.retry()`，交给 WorkManager 的退避策略。
 * - 其余（解析失败、接口改版）：不重试，重试也不会变好，等发版修。
 */
public class ScheduleSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: SettingsStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 手动同步（下拉刷新/同步菜单/切同步源）不受"启动时自动同步"开关影响，见类注释。
        val isManual = inputData.getBoolean(KEY_MANUAL, false)
        if (shouldSkipDueToAutoSyncSetting(isManual, settingsStore.settings.first().autoSyncOnLaunch)) {
            return Result.success()
        }

        return try {
            scheduleRepository.sync()
            Result.success()
        } catch (e: GdutException.SessionExpired) {
            Result.success()
        } catch (e: GdutException) {
            if (e.recoverable) Result.retry() else Result.success()
        } catch (e: Exception) {
            // 未归一化的异常（例如 SQLite 瞬时锁）当作可重试，让 WorkManager 退避后再来。
            Result.retry()
        }
    }

    public companion object {
        /** [androidx.work.Data] 里的 key：标记这是一次用户手动发起的立即同步。 */
        public const val KEY_MANUAL: String = "manual"
    }
}

/**
 * 是否因为"启动时自动同步"关闭而跳过这次同步。
 *
 * 抽成纯函数只是为了能在纯 JVM 测试里锁死这条判断——它曾经写成
 * `!settingsStore.settings.first().autoSyncOnLaunch`，忘了 `isManual` 这一半，
 * 导致关掉开关后手动同步也被短路掉。
 */
internal fun shouldSkipDueToAutoSyncSetting(isManual: Boolean, autoSyncOnLaunch: Boolean): Boolean =
    !isManual && !autoSyncOnLaunch

/**
 * 给 WorkManager 注入依赖的工厂。
 *
 * 默认的 `DefaultWorkerFactory` 用反射构造 Worker，而 [ScheduleSyncWorker] 需要
 * Repository 与 SettingsStore。这个工厂让 Worker 保持普通构造函数，不必去摸全局单例，
 * 于是 Worker 也能在纯 JVM 测试里直接构造。
 *
 * 返回 null 表示"不是我负责的 Worker"，WorkManager 会回退到默认的反射工厂 ——
 * 将来若有别的 Worker，不需要改这里。
 */
internal class GdutWorkerFactory(
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: SettingsStore,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ScheduleSyncWorker::class.java.name ->
            ScheduleSyncWorker(appContext, workerParameters, scheduleRepository, settingsStore)
        else -> null
    }
}
