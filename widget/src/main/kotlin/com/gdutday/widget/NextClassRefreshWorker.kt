package com.gdutday.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * "下节课"倒计时的刷新 Worker。
 *
 * 每次只做三件事：
 * 1. 让两个插件重画（插件重画时会重新读一遍 Room 的最新快照）；
 * 2. 读一次最新的"下一节课"；
 * 3. 按新的距离把**下一次**任务排好。
 *
 * 它不在 `GdutWorkerFactory` 里注册（那个工厂只认 `ScheduleSyncWorker`），
 * 因此走 WorkManager 的默认反射工厂 —— 所以构造函数必须是标准的
 * `(Context, WorkerParameters)`，依赖通过 `widgetContainer()` 取。
 */
public class NextClassRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val container = applicationContext.widgetContainer()
            WidgetUpdateManager.updateToday(applicationContext)
            WidgetUpdateManager.updateNext(applicationContext)
            val next = container.scheduleRepository.observeNextClass().first()
            reschedule(next)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 刷新失败（数据库瞬时锁、进程被杀等）绝不能把倒计时永久停摆：
            // 先按"今天已无课"排到次日 0 点兜底，再让 WorkManager 退避重试。
            reschedule(null)
            Result.retry()
        }
    }

    /**
     * 在 [NonCancellable] 里排下一次任务。
     *
     * 这里有个必须处理的细节：`ExistingWorkPolicy.REPLACE` 会取消**同名**的待执行任务，
     * 而当前正在运行的正是同名任务，于是它会请求取消自己。如果直接在普通上下文里
     * `enqueue`，协程可能在写库完成前就被取消，导致"下一次任务没排上"。
     * `NonCancellable` 保证这条写操作一定完成。
     */
    private suspend fun reschedule(next: com.gdutday.data.repository.NextClass?) {
        withContext(NonCancellable) {
            NextClassRefreshScheduler.scheduleNext(applicationContext, next, LocalDateTime.now())
        }
    }
}
