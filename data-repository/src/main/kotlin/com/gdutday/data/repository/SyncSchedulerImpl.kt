package com.gdutday.data.repository

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gdutday.core.datastore.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * [SyncScheduler] 的 WorkManager 实现。
 *
 * ## 周期下限的钳制
 *
 * WorkManager 的周期任务**硬性下限是 15 分钟**，比这更小的间隔会在 `enqueue` 时直接
 * 抛 `IllegalArgumentException`。因此这里把 `autoSyncIntervalHours` 换算成分钟后
 * `coerceAtLeast(15)`。这个钳制必须有注释，否则将来有人看到"设置里填 0 小时"会以为
 * 调度器坏了 —— 其实是系统限制，不是 bug。
 *
 * ## 为什么用 UPDATE 而不是 KEEP / REPLACE
 *
 * - KEEP：用户改了间隔不会生效；
 * - REPLACE：会取消正在跑的任务并重新开始，浪费已完成的进度；
 * - UPDATE：保留进度，只更新周期与约束。见 `AppContainer.SyncScheduler` 的 KDoc。
 */
public class SyncSchedulerImpl(
    private val workManager: WorkManager,
    private val settingsStore: SettingsStore,
    private val syncing: StateFlow<Boolean>,
) : SyncScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val isSyncing: StateFlow<Boolean> get() = syncing

    override fun schedulePeriodicSync() {
        // 读设置是 suspend，而本接口是同步的（见 AppContainer 的 KDoc），
        // 所以在自己的作用域里异步取一次。读到的瞬间值与之后的变化都只影响"下一次"排程，
        // 用户改设置时会再调一次本方法，不存在窗口期丢失。
        scope.launch {
            val settings = settingsStore.settings.first()
            if (!settings.autoSyncOnLaunch) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                return@launch
            }
            val intervalMinutes = (settings.autoSyncIntervalHours * 60L).coerceAtLeast(MIN_PERIODIC_MINUTES)
            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                intervalMinutes,
                TimeUnit.MINUTES,
            )
                .setConstraints(syncConstraints())
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    override fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    override fun requestImmediateSync(expedited: Boolean) {
        val request = OneTimeWorkRequestBuilder<ScheduleSyncWorker>()
            .setConstraints(syncConstraints())
            .apply {
                if (expedited) {
                    // 下拉刷新是用户正在等；配额不足时降级为普通任务，而不是失败。
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        // APPEND_OR_REPLACE：连续下拉时排队而不是丢弃，且失败的任务不会阻塞后续。
        workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** 只有联网且电量不低时才跑，避免在飞机模式或低电量时无意义地唤醒。 */
    private fun syncConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    public companion object {
        /** WorkManager 周期任务的最小间隔，硬限制，见类注释。 */
        public const val MIN_PERIODIC_MINUTES: Long = 15L

        public const val PERIODIC_WORK_NAME: String = "gdutday.schedule.periodic"
        public const val IMMEDIATE_WORK_NAME: String = "gdutday.schedule.immediate"
    }
}
