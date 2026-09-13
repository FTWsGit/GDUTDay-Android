package com.gdutday.core.model

import java.time.LocalDate

/**
 * 用户修改教务课程（补丁）的作用范围。
 *
 * 补丁生效时，原教务课程的周次按此范围被拆走：未涉及的周次保留原样，
 * 涉及的周次由补丁行接管。见 data-repository 的 `applyUserOverrides`。
 */
public enum class OverrideScope {
    /** 覆盖原课程的全部周次。 */
    ALL,

    /** 只覆盖一个单独的周（周次在 overrideWeeks 里）。 */
    THIS_WEEK,

    /** 覆盖一个连续周次范围（周次在 overrideWeeks 里）。 */
    WEEK_RANGE,
}

/**
 * 一条课程条目 = **某学期 · 某教学班 · 某星期几 · 一段连续节次 · 一组周次**。
 *
 * ## 为什么是"连续节次段"而不是"节次集合"
 *
 * 教务系统给的节次是一个集合，例如 `jcdm = "01020506"`（第 1、2、5、6 节）。
 * 这在课表网格上是**两个互不相邻的色块**，不能画成一个矩形。
 * 所以解析阶段就要按连续性切分：`{1,2,5,6}` → `(start=1,count=2)` 和 `(start=5,count=2)` 两条 [Course]。
 *
 * 切分逻辑在 `data-gdut` 的 `SectionRunSplitter`，并配有单元测试。
 * 旧 Java 后端没做这件事（直接把 `"0102"` 逐对转成 `"1,2"` 字符串塞给前端），
 * 遇到非连堂课就会画出跨越午休的错误色块。
 *
 * ## 为什么周次是 Set 而不是每周一条
 *
 * `xsgrkbcx!getDataList.action` 返回的是**按周炸开**的数据（一周一条，一学期一门课能有 16 条），
 * `xsgrkbcx!xsAllKbList.action` 返回的是**按教学班聚合**的数据（`zcs = "1,2,...,16"`）。
 * 两种都归一化到 `weeks: Set<Int>`，既省存储又便于"这门课第几周有"的查询。
 *
 * @property id Room 主键。0 表示尚未入库。
 * @property term 所属学期。
 * @property name 课程名称（`kcmc`）。
 * @property teacher 授课教师（`teaxms` / `jsxm`）。多位老师以顿号连接。
 * @property classroom 上课地点（`jxcdmc` / `jxcdmcs`）。多个场地以顿号连接。
 * @property dayOfWeek 星期几，**1=周一 … 7=周日**（ISO-8601，与 `java.time.DayOfWeek.value` 一致）。
 * @property startSection 起始节次，1..12。
 * @property sectionCount 连续节数，≥1。
 * @property weeks 上课周次集合，1..25。空集合表示"每周都不上"，通常意味着数据异常。
 * @property description 教学内容简介（`sknrjj`）/ 备注（`bjmc`）。
 * @property teachingClass 教学班名称（`jxbmc`）。用于区分同名课程的不同班。
 * @property courseCode 课程编号（`kcbh` / `kcrwdm`）。
 * @property source 来源。
 * @property colorKey 用户为该课程指定的颜色键；null 表示按课程名自动分配（见 `core-common` 的调色板）。
 * @property classDates `pkrq` 具体上课日期列表。只有 `getDataList` 接口提供。
 *   非空且与"周次×星期"推算结果不一致时，说明存在**调课/补课**，UI 应给出提示。
 */
public data class Course(
    public val id: Long = 0L,
    public val term: Term,
    public val name: String,
    public val teacher: String = "",
    public val classroom: String = "",
    public val dayOfWeek: Int,
    public val startSection: Int,
    public val sectionCount: Int,
    public val weeks: Set<Int> = emptySet(),
    public val description: String = "",
    public val teachingClass: String = "",
    public val courseCode: String = "",
    public val source: CourseSource = CourseSource.SCHOOL,
    public val colorKey: String? = null,
    public val classDates: List<LocalDate> = emptyList(),
    /** 绝对开始分钟（08:30 = 510）。-1 表示未设置，仍走节次 → 作息表换算。 */
    public val startMinute: Int = -1,
    /** 绝对结束分钟。-1 表示未设置，仍走节次 → 作息表换算。 */
    public val endMinute: Int = -1,
    /** `source == OVERRIDE` 时的作用范围；其余来源恒为 null。 */
    public val overrideScope: OverrideScope? = null,
    /** 该补丁覆盖的目标教务课程的自然键。非 OVERRIDE 行为 null。 */
    public val overrideTargetNaturalKey: String? = null,
    /** 该补丁接管的周次（THIS_WEEK / WEEK_RANGE 时写入）。 */
    public val overrideWeeks: Set<Int> = emptySet(),
) {
    init {
        require(dayOfWeek in 1..7) { "dayOfWeek 必须在 1..7，收到 $dayOfWeek" }
        require(startSection >= 1) { "startSection 必须 ≥ 1，收到 $startSection" }
        require(sectionCount >= 1) { "sectionCount 必须 ≥ 1，收到 $sectionCount" }
    }

    /** 结束节次（含）。 */
    public val endSection: Int get() = startSection + sectionCount - 1

    /**
     * 去重用的自然键。**不含** [id]、[colorKey]、[weeks]、[source]、[classDates]
     * —— 这些都会被用户修改，包含它们会让补丁在同步后匹配不到目标课程。
     * 字段：名称、教学班、课程编号、星期、起始节次、节数。
     */
    public val naturalKey: String
        get() = listOf(
            name,
            teachingClass,
            courseCode,
            dayOfWeek.toString(),
            startSection.toString(),
            sectionCount.toString(),
        ).joinToString("|")

    /** 该课程在指定周次是否上课。 */
    public fun occursInWeek(week: Int): Boolean = week in weeks

    /** 周次的紧凑显示，如 `"1-16周"`、`"1-8,11-16周"`、`"1,3,5周"`。 */
    public val weeksDisplay: String get() = formatWeekRanges(weeks)

    public companion object {
        /**
         * 把周次集合压缩成区间文本。`{1,2,3,5,6}` → `"1-3,5-6周"`。
         * 单周不写成 `1-1`。空集合返回 `"无周次"`。
         */
        public fun formatWeekRanges(weeks: Set<Int>): String {
            if (weeks.isEmpty()) return "无周次"
            val sorted = weeks.sorted()
            val sb = StringBuilder()
            var runStart = sorted.first()
            var prev = runStart
            for (w in sorted.drop(1)) {
                if (w == prev + 1) {
                    prev = w
                    continue
                }
                sb.appendRange(runStart, prev).append(',')
                runStart = w
                prev = w
            }
            sb.appendRange(runStart, prev)
            return sb.append('周').toString()
        }

        private fun StringBuilder.appendRange(from: Int, to: Int): StringBuilder =
            if (from == to) append(from) else append(from).append('-').append(to)

        /**
         * 展开形如 `"1-16"`、`"1-8,11-16"`、`"1,3,5"`、`"2-12单"`、`"1-11双"` 的周次描述。
         *
         * 研究生课表的 `ZCMC` 字段会出现单双周写法（如 `"1-16单周"`），
         * 本科生的 `zcs` 是纯数字逗号列表。这里统一处理，无法识别的片段被忽略而不抛异常。
         */
        public fun parseWeeks(raw: String?): Set<Int> {
            if (raw.isNullOrBlank()) return emptySet()
            // 只保留数字、区间/列表分隔符和单双周标记，其余（"周"、"第"、空格、单位名等）全部剥掉。
            // 实测本科生的 zcs 是纯数字列表，研究生的 ZCMC 是 "1-16周"/"1-11单周" 这类文本，
            // 而教务处历史上还出现过 "第1-16周" 的写法 —— 用白名单过滤比逐个 replace 更稳。
            val text = raw.replace(NON_WEEK_CHAR, "")
            if (text.isEmpty()) return emptySet()
            val result = LinkedHashSet<Int>()
            for (token in text.split(',', '，', ';', '；').filter { it.isNotBlank() }) {
                val odd = token.endsWith("单")
                val even = token.endsWith("双")
                val body = token.trimEnd('单', '双')
                val parts = body.split('-')
                val numbers: List<Int> = try {
                    when (parts.size) {
                        1 -> listOf(parts[0].trim().toInt())
                        2 -> {
                            val from = parts[0].trim().toInt()
                            val to = parts[1].trim().toInt()
                            if (to < from) emptyList() else (from..to).toList()
                        }
                        else -> emptyList()
                    }
                } catch (_: NumberFormatException) {
                    emptyList()
                }
                for (n in numbers) {
                    if (n < 1 || n > MAX_WEEK) continue
                    if (odd && n % 2 == 0) continue
                    if (even && n % 2 == 1) continue
                    result += n
                }
            }
            return result
        }

        /** 支持的最大周次。旧小程序硬编码 20，研究生接口按 21 周建表，这里放宽到 25。 */
        public const val MAX_WEEK: Int = 25

        /** 每天最大节次。与 [com.gdutday.core.common.SectionRunSplitter.MAX_SECTION] 对齐（13/14 节为实验课）。 */
        public const val MAX_SECTION: Int = 14

        /**
         * [parseWeeks] 的白名单过滤器：匹配所有**不该保留**的字符。
         *
         * 保留 `0-9`、`-`（区间）、`,` `，` `;` `；`（列表分隔）、`单` `双`（单双周标记）。
         */
        private val NON_WEEK_CHAR: Regex = Regex("[^0-9\\-,，;；单双]")
    }
}
