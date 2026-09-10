package com.gdutday.widget

import java.time.Duration
import java.time.LocalDateTime

/**
 * "下节课"倒计时的分级刷新策略。
 *
 * ## 为什么要分级，而不是固定每分钟
 *
 * 倒计时**不走秒**：每秒刷 RemoteViews 会让 launcher 进程持续占 CPU，是插件被卸载的
 * 头号原因（见 next_class_widget_info.xml 的注释）。分钟粒度已经够用，但仍要分辨
 * "离上课还有 5 小时"和"马上上课"—— 前者每分钟唤醒一次纯属浪费。
 *
 * | 场景 | 刷新间隔 | 理由 |
 * |------|----------|------|
 * | 今天还有课，距开始 > 60 分钟 | 60 分钟 | 一小时内分钟数变化对用户不敏感 |
 * | 今天还有课，距开始 ≤ 60 分钟 | 1 分钟 | 用户开始关注倒计时 |
 * | 正在上课 | 1 分钟 | 要更新"还剩 X 分钟下课" |
 * | 今天已无课 / 近期无课 | 次日 0 点一次 | 次日要切到新一天，中间没有任何信息会变 |
 *
 * 全部函数把"现在"作为参数传入，不读 `LocalDateTime.now()`：这样单元测试可以固化时间，
 * 不用依赖真实的墙钟，也不会在跨天边界抖动。
 */
public object NextClassRefreshPolicy {

    /** 每分钟档。 */
    public const val MINUTE_MILLIS: Long = 60_000L

    /** 每小时档。 */
    public const val HOUR_MILLIS: Long = 3_600_000L

    /** 阈值：距上课不超过 60 分钟就进入每分钟档。 */
    public const val SHORT_RANGE_MINUTES: Long = 60L

    /**
     * 计算下一次唤醒距现在多久。
     *
     * @param hasClassToday 今天是否还有未结束（含正在进行）的课。
     *   注意：不是"`next != null`"。下一节课可能是明天/下周的，此时仍应睡到次日 0 点。
     * @param isOngoing 下节课是否正在进行
     * @param startsInMinutes 距下节课开始还有多少分钟；[isOngoing] 为 true 时无意义
     * @param now 当前时刻
     * @return 下一次执行前应等待的毫秒数，至少 1 分钟
     */
    public fun delayMillis(
        hasClassToday: Boolean,
        isOngoing: Boolean,
        startsInMinutes: Long,
        now: LocalDateTime,
    ): Long {
        // 今天没课（或已经全上完但 next 指向未来某天）：睡到次日 0 点。
        if (!hasClassToday) return millisUntilNextMidnight(now)
        // 正在上课：每分钟更新剩余分钟数。
        if (isOngoing) return MINUTE_MILLIS
        return if (startsInMinutes <= SHORT_RANGE_MINUTES) MINUTE_MILLIS else HOUR_MILLIS
    }

    /**
     * 距下一个 0 点的毫秒数。
     *
     * 下限钳到 1 分钟，避免刚好卡在 0 点时算出 0 导致 WorkManager 立即执行 → 数据没变 →
     * 又排到 24 小时后，白白多跑一次。多睡一分钟不会漏掉任何信息。
     */
    public fun millisUntilNextMidnight(now: LocalDateTime): Long {
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
        return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(MINUTE_MILLIS)
    }

    /**
     * 按 [NextClass] 计算间隔的便捷入口。放在这里而不是调度器里，
     * 是为了让"距离 → 间隔"这条规则只有一个来源。
     */
    public fun delayMillisFor(next: com.gdutday.data.repository.NextClass?, now: LocalDateTime): Long {
        val today = now.toLocalDate()
        val hasClassToday = next != null && next.date == today
        return delayMillis(
            hasClassToday = hasClassToday,
            isOngoing = next?.isOngoing == true,
            startsInMinutes = next?.startsInMinutes ?: Long.MAX_VALUE,
            now = now,
        )
    }
}
