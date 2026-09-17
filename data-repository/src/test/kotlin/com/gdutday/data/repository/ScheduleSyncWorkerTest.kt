package com.gdutday.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 关闭"启动时自动同步"后，手动同步（下拉刷新/同步菜单/切同步源）必须仍然能跑。
 *
 * 回归的 bug：`ScheduleSyncWorker.doWork()` 曾经只看 `autoSyncOnLaunch`，
 * 不区分这次任务是周期任务自动触发的还是用户手动请求的立即同步，
 * 导致关掉那个开关后手动同步被无声跳过。
 */
class ScheduleSyncWorkerTest {

    @Test
    fun `手动同步不受自动同步开关影响`() {
        assertThat(shouldSkipDueToAutoSyncSetting(isManual = true, autoSyncOnLaunch = false)).isFalse()
        assertThat(shouldSkipDueToAutoSyncSetting(isManual = true, autoSyncOnLaunch = true)).isFalse()
    }

    @Test
    fun `自动触发的同步仍然受开关约束`() {
        assertThat(shouldSkipDueToAutoSyncSetting(isManual = false, autoSyncOnLaunch = false)).isTrue()
        assertThat(shouldSkipDueToAutoSyncSetting(isManual = false, autoSyncOnLaunch = true)).isFalse()
    }
}
