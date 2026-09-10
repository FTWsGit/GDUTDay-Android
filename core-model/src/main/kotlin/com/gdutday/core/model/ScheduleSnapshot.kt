package com.gdutday.core.model

import java.time.Instant

/**
 * 一次"课表同步"的完整产物。
 *
 * Repository 把它写进 Room，UI / Widget 从 Room 读。
 * 之所以要有这么一个聚合类型，是因为同步是**多接口拼装**的：
 * 课表 + 考试安排 + 学期列表 + 校区探测，任何一环失败都要能部分成功。
 *
 * @property term 本次同步的学期。
 * @property courses 归一化后的课程条目（已按连续节次切分、已合并周次）。
 * @property exams 考试安排。
 * @property campus 探测到的校区；[Campus.UNKNOWN] 表示需要用户手选。
 * @property availableTerms 教务系统里可选的学期列表，用于学期切换下拉框。
 * @property fetchedAt 同步完成时刻（UTC）。用于"上次更新：3 天前"和自动刷新判断。
 * @property source 数据来自哪个接口，写进诊断信息便于排查改版问题。
 * @property warnings 非致命问题，例如"劳动教育成绩缺失已兜底"、"检测到调课"。UI 以非阻塞方式提示。
 */
public data class ScheduleSnapshot(
    public val term: Term,
    public val courses: List<Course> = emptyList(),
    public val exams: List<Exam> = emptyList(),
    public val campus: Campus = Campus.UNKNOWN,
    public val availableTerms: List<Term> = emptyList(),
    public val fetchedAt: Instant = Instant.now(),
    public val source: ScheduleSource = ScheduleSource.UNKNOWN,
    public val warnings: List<String> = emptyList(),
) {
    public val isEmpty: Boolean get() = courses.isEmpty() && exams.isEmpty()

    /**
     * 该学期实际用到的最大周次，用于决定周次选择器的范围。
     *
     * 旧小程序把周数硬编码成 20（`weekLength = 20`），于是第 21 周的课直接消失。
     * 这里改成从数据推导，并保底 20 周以维持用户熟悉的滑动范围。
     */
    public val maxWeek: Int
        get() = maxOf(MIN_DISPLAYED_WEEKS, courses.flatMap { it.weeks }.maxOrNull() ?: 0)

    public companion object {
        /** 即使数据里没有第 20 周，也至少显示 20 周，与旧版行为保持一致。 */
        public const val MIN_DISPLAYED_WEEKS: Int = 20
    }
}

/**
 * 本次课表数据实际来自哪个接口。**是结果，不是配置** ——
 * 用户想指定用哪个接口，用 [ScheduleFetchStrategy]。
 *
 * 本科生有两个可用接口，返回结构完全不同，本项目两个都实现了：
 *
 * | 接口 | 粒度 | 优点 | 风险 |
 * |---|---|---|---|
 * | `xsgrkbcx!xsAllKbList.action` | 每教学班一行，`zcs="1,2,…,16"` | 数据量小（约 10 行），无分页风险，天然给出周次集合 | 2022 年逆向所得，可能已下线；返回的是 **HTML**，需正则抠 `var kbxx = [...]` |
 * | `xsgrkbcx!getDataList.action` | 每周每教学班一行 | 2024 年仍在用，返回标准 JSON，带 `pkrq` 具体上课日期（可反推开学日期、检测调课） | 数据量大，需分页；周次要自己聚合 |
 */
public enum class ScheduleSource(
    public val label: String,
) {
    /** 每教学班一行的聚合接口（返回 HTML）。 */
    ALL_KB_LIST("xsAllKbList"),

    /** 按周炸开的分页接口（返回 JSON）。 */
    DATA_LIST("getDataList"),

    UNKNOWN("未知"),
}

/**
 * 课表抓取策略。**这是用户可配置项**（设置页的"课表数据源"）。
 *
 * 为什么要暴露给用户：某个接口挂了的时候，用户能自己切到另一个立刻恢复，
 * 不必等我们发版。这也是把两个接口都实现掉的最大收益。
 */
public enum class ScheduleFetchStrategy(
    public val displayName: String,
    public val description: String,
) {
    /**
     * 先试 `xsAllKbList`，失败或返回空则回退 `getDataList`。默认值。
     *
     * 优先 `xsAllKbList` 是因为它数据量小得多（约 10 行 vs 上百行），
     * 一次请求就能拿全，没有分页取不全的风险。
     */
    AUTO("自动", "优先聚合接口，失败自动切换到分页接口"),

    /** 只用 `xsAllKbList`。失败就失败，不回退。用于确认是不是这个接口挂了。 */
    ONLY_ALL_KB_LIST("仅聚合接口", "只用 xsAllKbList，失败不回退"),

    /** 只用 `getDataList`。 */
    ONLY_DATA_LIST("仅分页接口", "只用 getDataList，失败不回退"),

    /** 完全不联网，只读本地数据库。飞行模式或教务系统维护时用。 */
    LOCAL_ONLY("仅本地", "不联网，只显示已缓存的课表"),
    ;

    public companion object {
        public fun fromName(raw: String?): ScheduleFetchStrategy =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}
