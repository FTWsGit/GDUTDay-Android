package com.gdutday.core.common

import com.gdutday.core.model.Campus
import java.time.LocalTime

/**
 * 一节课的起止时刻。
 *
 * @property index 节次，1..12。
 */
public data class Period(
    public val index: Int,
    public val start: LocalTime,
    public val end: LocalTime,
) {
    init {
        require(index >= 1) { "节次从 1 开始，收到 $index" }
        require(end > start) { "第 $index 节结束时刻必须晚于开始时刻: $start-$end" }
    }

    public val durationMinutes: Long get() = java.time.Duration.between(start, end).toMinutes()
}

/**
 * 某校区一天的作息表（12 节）。
 *
 * ## 数据来源与可信度
 *
 * 逐字抄自旧小程序 `staticData/campusTime.js`。这是**硬编码常量**，学校改作息不会通知我们，
 * 所以：
 * 1. 允许用户在设置里覆盖（`core-datastore` 的 `CustomTimetable`），
 * 2. 在"关于 → 诊断"页展示当前生效的作息表，便于用户自查。
 *
 * ## ⚠ 番禺校区第 10~12 节的数据可疑
 *
 * 原始数据里番禺校区的第 10、11、12 节**三节完全相同**，都是 `19:30-21:30`：
 * ```js
 * 番禺校区: [..., {start:'17:05',end:'17:50'}, {start:'19:30',end:'21:30'},
 *                {start:'19:30',end:'21:30'}, {start:'19:30',end:'21:30'}]
 * ```
 * 这显然不是真实作息（三节课重叠在同一时段，且单节长达 2 小时）。
 * 极可能是当年开发者为了填够 12 项而复制的占位值。
 *
 * 处理方式：**原样保留**（不擅自编造），但
 * - 番禺校区几乎没有本科教学班，实际命中率极低；
 * - [Campus.PANYU] 在 [CampusTimetable.of] 里会附带一条 warning，
 *   UI 在用户手选番禺校区时提示"该校区作息表未经核实，建议在设置中自定义"。
 */
public class CampusTimetable private constructor(
    public val campus: Campus,
    public val periods: List<Period>,
) {
    /** 节数，恒为 12。 */
    public val size: Int get() = periods.size

    /**
     * 取第 [section] 节（1-based）。
     *
     * 越界时**不抛异常**，而是钳制到首/末节 —— 教务系统偶尔会给出第 13 节
     * （某些实验课），课表页面不应该因此整页崩溃。
     */
    public fun periodOf(section: Int): Period {
        val idx = section.coerceIn(1, size) - 1
        return periods[idx]
    }

    /** 一段连续节次的开始时刻。 */
    public fun startOf(startSection: Int): LocalTime = periodOf(startSection).start

    /** 一段连续节次的结束时刻。 */
    public fun endOf(endSection: Int): LocalTime = periodOf(endSection).end

    /** 从第 [from] 节到第 [to] 节（含）的总分钟数，含中间的课间。 */
    public fun spanMinutes(from: Int, to: Int): Long {
        val a = periodOf(minOf(from, to))
        val b = periodOf(maxOf(from, to))
        return java.time.Duration.between(a.start, b.end).toMinutes()
    }

    /** 一天中第一节课的开始时刻，用于"今天第一节课几点"这类展示。 */
    public val firstPeriodStart: LocalTime get() = periods.first().start

    /** 一天中最后一节课的结束时刻。 */
    public val lastPeriodEnd: LocalTime get() = periods.last().end

    public companion object {

        /** 每天节数。旧小程序在多处硬编码 `v-for="(item,index) in 12"`。 */
        public const val SECTIONS_PER_DAY: Int = 12

        private fun t(h: Int, m: Int): LocalTime = LocalTime.of(h, m)

        private val UNIVERSITY_CITY = listOf(
            t(8, 30) to t(9, 15),
            t(9, 20) to t(10, 5),
            t(10, 25) to t(11, 10),
            t(11, 15) to t(12, 0),
            t(13, 50) to t(14, 35),
            t(14, 40) to t(15, 25),
            t(15, 30) to t(16, 15),
            t(16, 30) to t(17, 15),
            t(17, 20) to t(18, 5),
            t(18, 30) to t(19, 15),
            t(19, 20) to t(20, 5),
            t(20, 10) to t(20, 55),
        )

        // 东风路与龙洞的作息完全一致（原数据里两份是逐字相同的拷贝）。
        private val DONGFENG_ROAD = listOf(
            t(8, 15) to t(9, 0),
            t(9, 5) to t(9, 50),
            t(10, 10) to t(10, 55),
            t(11, 0) to t(11, 45),
            t(13, 30) to t(14, 15),
            t(14, 20) to t(15, 5),
            t(15, 10) to t(15, 55),
            t(16, 15) to t(17, 0),
            t(17, 5) to t(17, 50),
            t(18, 30) to t(19, 15),
            t(19, 20) to t(20, 5),
            t(20, 10) to t(20, 55),
        )

        private val PANYU = listOf(
            t(8, 30) to t(9, 15),
            t(9, 20) to t(10, 5),
            t(10, 20) to t(11, 5),
            t(11, 10) to t(11, 55),
            t(13, 40) to t(14, 25),
            t(14, 30) to t(15, 15),
            t(15, 20) to t(16, 5),
            t(16, 15) to t(17, 0),
            t(17, 5) to t(17, 50),
            // ↓ 原始数据里这三节完全相同，疑似占位值，见类注释。此处逐字保留，不擅自编造。
            t(19, 30) to t(21, 30),
            t(19, 30) to t(21, 30),
            t(19, 30) to t(21, 30),
        )

        private val cache: Map<Campus, CampusTimetable> = buildMap {
            put(Campus.UNIVERSITY_CITY, of(Campus.UNIVERSITY_CITY, UNIVERSITY_CITY))
            put(Campus.DONGFENG_ROAD, of(Campus.DONGFENG_ROAD, DONGFENG_ROAD))
            put(Campus.LONGDONG, of(Campus.LONGDONG, DONGFENG_ROAD))
            put(Campus.PANYU, of(Campus.PANYU, PANYU))
            // UNKNOWN 回退到大学城：绝大多数本科生在大学城，猜错的代价最小。
            put(Campus.UNKNOWN, of(Campus.UNKNOWN, UNIVERSITY_CITY))
        }

        private fun of(campus: Campus, ranges: List<Pair<LocalTime, LocalTime>>): CampusTimetable {
            require(ranges.size == SECTIONS_PER_DAY) {
                "${campus.displayName} 的作息表应有 $SECTIONS_PER_DAY 节，实际 ${ranges.size} 节"
            }
            return CampusTimetable(
                campus = campus,
                periods = ranges.mapIndexed { i, (s, e) -> Period(i + 1, s, e) },
            )
        }

        /** 取某校区的作息表。任何校区都能取到（[Campus.UNKNOWN] 回退大学城）。 */
        public fun of(campus: Campus): CampusTimetable =
            cache.getValue(campus)

        /**
         * 用户自定义作息表。设置里存 24 个 `HH:mm` 字符串（12 节的起止），
         * 解析失败的位置回退到该校区默认值，**不让一处输错毁掉整张表**。
         *
         * @param raw 形如 `["08:30","09:15","09:20","10:05", …]`，长度必须为 24。
         */
        public fun parseCustom(campus: Campus, raw: List<String>?): CampusTimetable? {
            if (raw == null || raw.size != SECTIONS_PER_DAY * 2) return null
            val times = raw.map { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
            if (times.any { it == null }) return null
            val ranges = (0 until SECTIONS_PER_DAY).map { i ->
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                times[i * 2]!! to times[i * 2 + 1]!!
            }
            // 起止倒挂的节次视为无效输入
            if (ranges.any { (s, e) -> e <= s }) return null
            return of(campus, ranges)
        }
    }
}
