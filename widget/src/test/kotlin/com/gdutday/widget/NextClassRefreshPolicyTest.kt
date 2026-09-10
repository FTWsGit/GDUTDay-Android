package com.gdutday.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/**
 * 倒计时分级刷新策略。
 *
 * 所有用例都传入固定的 `now`，不依赖 `LocalDateTime.now()`，因此不会在跨天/跨时区
 * 的 CI 上抖动。
 */
class NextClassRefreshPolicyTest {

    private val noon: LocalDateTime = LocalDateTime.of(2026, 9, 10, 12, 0)

    @Test
    fun `距下节课超过 60 分钟按每小时刷新`() {
        assertThat(
            NextClassRefreshPolicy.delayMillis(
                hasClassToday = true, isOngoing = false, startsInMinutes = 61, now = noon,
            ),
        ).isEqualTo(NextClassRefreshPolicy.HOUR_MILLIS)
    }

    @Test
    fun `恰好 60 分钟进入每分钟档`() {
        assertThat(
            NextClassRefreshPolicy.delayMillis(
                hasClassToday = true, isOngoing = false, startsInMinutes = 60, now = noon,
            ),
        ).isEqualTo(NextClassRefreshPolicy.MINUTE_MILLIS)
    }

    @Test
    fun `距下节课很近时按每分钟刷新`() {
        assertThat(
            NextClassRefreshPolicy.delayMillis(
                hasClassToday = true, isOngoing = false, startsInMinutes = 7, now = noon,
            ),
        ).isEqualTo(NextClassRefreshPolicy.MINUTE_MILLIS)
    }

    @Test
    fun `正在上课时按每分钟刷新`() {
        assertThat(
            NextClassRefreshPolicy.delayMillis(
                hasClassToday = true, isOngoing = true, startsInMinutes = -10, now = noon,
            ),
        ).isEqualTo(NextClassRefreshPolicy.MINUTE_MILLIS)
    }

    @Test
    fun `今天已无课时睡到次日 0 点`() {
        assertThat(
            NextClassRefreshPolicy.delayMillis(
                hasClassToday = false, isOngoing = false, startsInMinutes = 30, now = noon,
            ),
        ).isEqualTo(NextClassRefreshPolicy.millisUntilNextMidnight(noon))
    }

    @Test
    fun `距次日 0 点的毫秒数正确`() {
        assertThat(NextClassRefreshPolicy.millisUntilNextMidnight(noon))
            .isEqualTo(12 * 60 * 60 * 1000L)
    }

    @Test
    fun `紧邻 0 点时也至少睡 1 分钟，避免空转`() {
        val almostMidnight = LocalDateTime.of(2026, 9, 10, 23, 59, 30, 500_000_000)
        assertThat(NextClassRefreshPolicy.millisUntilNextMidnight(almostMidnight))
            .isEqualTo(NextClassRefreshPolicy.MINUTE_MILLIS)
    }
}
