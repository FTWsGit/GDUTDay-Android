package com.gdutday.core.common

import com.gdutday.core.model.Course
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 学期历：**周次 ↔ 日期**的双向换算。
 *
 * ## 旧实现的两个坑（本项目已修）
 *
 * 1. `commonFun.js` 里 `termStart` 是**模块加载时读取一次的模块级常量**：
 *    ```js
 *    let termStart = getStorageSync('schoolOpening', "2020.9.7");
 *    ```
 *    改了开学日期必须重启小程序才生效，代码里甚至专门写了
 *    "请更新完后重启小程序同步时间" 的提示。本类每次由 Repository 用最新值构造，不存在这个问题。
 *
 * 2. 旧实现直接用 `(now - termStart) / 7天` 计算周数，如果开学日期不是周一，
 *    "第 1 周" 就会从周三开始，导致周三之前的课显示成上一周。
 *    本类统一把第 1 周对齐到**包含开学日期的那个周一**。
 *
 * ## 开学日期从哪来
 *
 * 没有后端可以问，所以按优先级取（见 `data-repository` 的 `SemesterStartResolver`）：
 * 1. 用户在设置里手动指定
 * 2. **从课表数据反推** —— `getDataList` 接口的每一行都带 `pkrq`（具体上课日期）和 `zc`（周次），
 *    两者一减就是开学日期。这是最可靠的来源，见 [deriveSemesterStart]。
 * 3. 内置的已知学期开学日期表 [KnownSemesterStarts]
 * 4. 兜底猜测：第一学期 9 月 1 日、第二学期 2 月下旬
 *
 * @property semesterStart 开学日期（第 1 周的第 1 天）。
 */
public class TermCalendar(
    public val semesterStart: LocalDate,
    /** 本学期总周数上限，仅用于 UI 边界，不参与换算。 */
    public val totalWeeks: Int = DEFAULT_TOTAL_WEEKS,
) {
    /** 第 1 周的周一。若 [semesterStart] 本身是周一则等于它。 */
    public val weekOneMonday: LocalDate =
        semesterStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * 某日期属于第几周（1-based）。
     *
     * 开学前返回 ≤ 0，假期后返回 > [totalWeeks]。**不钳制**，
     * 让调用方能区分"还没开学"和"第 1 周"。UI 展示时再自行钳制。
     */
    public fun weekOf(date: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(weekOneMonday, date)
        // ⚠ 必须用 floorDiv，不能用普通的 `/`。
        // Kotlin/Java 的整数除法向零截断：开学前一天（days = -1）会算成 -1/7 = 0，
        // 于是"开学前那个周日"被判定为第 1 周 —— 用户会在开学前就看到本周课表，
        // 而且周次选择器的边界也跟着错。floorDiv(-1, 7) = -1，+1 得 0，才是对的。
        // 这个 bug 是单元测试 `开学前返回 0 或负数` 抓出来的。
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /** 当前周次（1-based）。 */
    public fun currentWeek(today: LocalDate = LocalDate.now()): Int = weekOf(today)

    /**
     * UI 用的钳制版当前周次：开学前显示第 1 周，结课后显示最后一周。
     *
     * 旧小程序 `getCurrentWeek()` 钳制到 `[0, 19]`（0-based），本方法钳制到 `[1, totalWeeks]`（1-based）。
     */
    public fun currentWeekClamped(today: LocalDate = LocalDate.now()): Int =
        weekOf(today).coerceIn(1, totalWeeks)

    /**
     * 第 [week] 周、星期 [dayOfWeek]（1=周一 … 7=周日）对应的日期。
     */
    public fun dateOf(week: Int, dayOfWeek: Int): LocalDate {
        require(dayOfWeek in 1..7) { "dayOfWeek 必须在 1..7，收到 $dayOfWeek" }
        return weekOneMonday.plusWeeks((week - 1).toLong()).plusDays((dayOfWeek - 1).toLong())
    }

    /** 第 [week] 周的周一。 */
    public fun mondayOf(week: Int): LocalDate = weekOneMonday.plusWeeks((week - 1).toLong())

    /** 第 [week] 周的周日。 */
    public fun sundayOf(week: Int): LocalDate = mondayOf(week).plusDays(6)

    /** 第 [week] 周的起止（周一~周日）。 */
    public fun weekRange(week: Int): ClosedRange<LocalDate> = mondayOf(week)..sundayOf(week)

    /** [date] 是否落在学期区间内（第 1 周周一 ~ 第 [totalWeeks] 周周日）。 */
    public fun isInSemester(date: LocalDate): Boolean = weekOf(date) in 1..totalWeeks

    /** 该日期是否已经过了本周（用于把已上完的课块置灰）。 */
    public fun isPastWeek(week: Int, today: LocalDate = LocalDate.now()): Boolean =
        sundayOf(week) < today

    /**
     * 距今 [offsetDays] 天的日期所属周次，供 Widget 的"下周预览"用。
     */
    public fun weekAfter(days: Int, today: LocalDate = LocalDate.now()): Int =
        weekOf(today.plusDays(days.toLong()))

    override fun toString(): String =
        "TermCalendar(week1Monday=$weekOneMonday, totalWeeks=$totalWeeks)"

    public companion object {
        /**
         * 默认总周数。旧小程序硬编码 20；`Course.MAX_WEEK` 放宽到 25 是为了容纳长学期。
         * UI 上实际显示多少周由 `ScheduleSnapshot.maxWeek` 决定。
         */
        public const val DEFAULT_TOTAL_WEEKS: Int = 20

        /**
         * 从课表数据反推开学日期（第 1 周的周一）。
         *
         * `xsgrkbcx!getDataList.action` 的每一行都同时给出
         * `pkrq`（具体上课日期，如 `2025-09-15`）、`zc`（周次，如 `3`）、`xq`（星期，如 `1`），
         * 三者足以唯一确定学期历：
         * ```
         * weekOneMonday = pkrq - (zc - 1) 周 - (xq - 1) 天
         * ```
         *
         * 多行会给出多个候选，正常情况下它们**完全一致**。若不一致（调课、跨校区数据混乱、
         * 或 `zc` 语义变化），采用**众数**：出现次数最多的那个日期，其次取最早的。
         * 这样单条脏数据不会污染整张课表的日期。
         *
         * @param samples (上课日期, 周次, 星期) 三元组。星期为 1..7（周一..周日）。
         * @return 反推出的第 1 周周一；样本为空或全部无效时返回 null。
         */
        public fun deriveSemesterStart(samples: List<SemesterStartSample>): LocalDate? {
            val candidates = samples.mapNotNull { s ->
                if (s.week < 1 || s.dayOfWeek !in 1..7) return@mapNotNull null
                s.classDate
                    .minusWeeks((s.week - 1).toLong())
                    .minusDays((s.dayOfWeek - 1).toLong())
                    // 再对齐到周一，吸收 pkrq 本身可能不是该周对应星期的脏数据
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            }
            if (candidates.isEmpty()) return null
            return candidates
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<LocalDate, Int>> { it.value }.thenBy { it.key })
                .first()
                .key
        }

        /**
         * 仅凭学期号粗略猜测开学日期，作为最后的兜底。
         *
         * 第一学期取 9 月 1 日附近，第二学期取 2 月下旬。
         * **这只是猜测**，误差可达两周，UI 必须提示用户去设置里校准。
         */
        public fun guessSemesterStart(year: Int, semester: Int): LocalDate = when (semester) {
            1 -> firstMondayOf(year, 9)
            // 第二学期取 2 月下旬那个周的周一。
            // 用 previousOrSame 而不是 nextOrSame：2 月 24 日若是周二，
            // nextOrSame 会跳到 3 月 2 日，把猜测值推出 2 月，误差凭空多一周。
            else -> LocalDate.of(year + 1, 2, 24)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }

        /** [year] 年 [month] 月的第一个周一。 */
        private fun firstMondayOf(year: Int, month: Int): LocalDate =
            LocalDate.of(year, month, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY))

        /**
         * 把课程自带的 [Course.classDates] 转成反推样本。
         *
         * 一门 [Course] 的 `classDates` 与它的 `weeks` 一一对应（都是升序），
         * 若长度不等说明存在调课/合并行，此时**跳过该课程**而不是硬凑。
         */
        public fun samplesFrom(course: Course): List<SemesterStartSample> {
            val weeks = course.weeks.sorted()
            val dates = course.classDates
            if (weeks.size != dates.size) return emptyList()
            return weeks.zip(dates).map { (w, d) ->
                SemesterStartSample(classDate = d, week = w, dayOfWeek = course.dayOfWeek)
            }
        }
    }
}

/**
 * 反推开学日期所需的最小样本。
 *
 * @property classDate `pkrq` 具体上课日期
 * @property week `zc` 周次，1-based
 * @property dayOfWeek `xq` 星期，1=周一 … 7=周日
 */
public data class SemesterStartSample(
    public val classDate: LocalDate,
    public val week: Int,
    public val dayOfWeek: Int,
)
