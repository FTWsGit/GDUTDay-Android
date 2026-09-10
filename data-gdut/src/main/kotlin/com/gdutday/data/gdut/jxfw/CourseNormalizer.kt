package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Term
import com.gdutday.core.common.SectionRunSplitter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 课表接口的**原始行**，两个不同接口的字段都先归一到这个结构。
 *
 * 为什么要多这一层：`xsAllKbList` 和 `getDataList` 的字段名、粒度、格式都不一样
 * （节次一个是 `"1,2"` 一个是 `"0102"`，周次一个是 `"1,2,…,16"` 一个是单个 `3`），
 * 如果让 [CourseNormalizer] 直接面对两种格式，它会变成一团 if/else。
 * 中间加这一层之后，两个解析器各自只负责"把自家格式翻译成 RawScheduleRow"，
 * 归一化逻辑只写一遍。
 *
 * @property sectionsRaw 原始节次串，**不做任何加工**。
 * @property sectionsPaired true = `"0102"` 两位拼接格式（`getDataList` 的 `jcdm`）；
 *   false = `"1,2"` 逗号分隔格式（`xsAllKbList` 的 `jcdm2`）。
 *   显式标注而不是让 [SectionRunSplitter.parseAuto] 猜，因为 `"12"` 这种串
 *   在两种格式下含义完全不同（第 12 节 vs 第 1、2 节），猜错就是灾难。
 * @property weeks 周次集合。`getDataList` 每行只有一个周次，`xsAllKbList` 每行是全部周次。
 * @property classDate `pkrq` 上课日期。只有 `getDataList` 可能提供，
 *   是反推学期开学日期的关键输入，见 [com.gdutday.core.common.TermCalendar.deriveSemesterStart]。
 */
public data class RawScheduleRow(
    public val courseName: String,
    public val courseCode: String = "",
    public val teachingClass: String = "",
    public val classroom: String = "",
    public val teacher: String = "",
    public val dayOfWeek: Int,
    public val sectionsRaw: String = "",
    public val sectionsPaired: Boolean = false,
    public val weeks: Set<Int> = emptySet(),
    public val description: String = "",
    public val classDate: LocalDate? = null,
)

/**
 * 把 [RawScheduleRow] 归一成 [Course]。
 *
 * ## 做两件事
 *
 * ### 1. 按连续节次切分
 *
 * 一行的节次可能是 `{1,2,5,6}`（上午两节 + 下午两节），在课表网格上是两个色块，
 * 必须拆成两条 [Course]。见 [SectionRunSplitter] 的说明。
 *
 * ### 2. 按自然键聚合
 *
 * `getDataList` 是**按周炸开**的：一门 16 周的课会产生 16 行，
 * 除了 `zc`(周次) 和 `pkrq`(日期) 之外完全相同。
 * 这里把它们合并回一条 `weeks = {1..16}` 的 [Course]，
 * 顺带把 `pkrq` 收集成 [Course.classDates]。
 *
 * 聚合键 = `courseCode | teachingClass | courseName | dayOfWeek | startSection | sectionCount | classroom | teacher`。
 * 把 `classroom` 和 `teacher` 也纳入键，是因为**换教室的课**（前 8 周在 A 楼、后 8 周在 B 楼）
 * 在教务系统里是两行，合并成一条会丢掉信息。
 *
 * ## 丢弃规则
 *
 * - 课程名为空的行直接丢（教务处偶尔下发占位行）
 * - 星期不在 1..7 的行丢，并计入 [Outcome.droppedRows]
 * - 节次解析不出任何有效段的行丢，同样计入
 *
 * **绝不抛异常** —— 一行脏数据不该毁掉整张课表。所有丢弃都记录在 [Outcome] 里供诊断。
 */
public object CourseNormalizer {

    /**
     * 归一化结果。
     *
     * @property courses 归一化后的课程
     * @property droppedRows 被丢弃的行数
     * @property dropReasons 丢弃原因计数，如 `"dayOfWeek=9" -> 2`
     * @property warnings 非致命提示，会被塞进 `ScheduleSnapshot.warnings`
     */
    public data class Outcome(
        public val courses: List<Course>,
        public val droppedRows: Int = 0,
        public val dropReasons: Map<String, Int> = emptyMap(),
        public val warnings: List<String> = emptyList(),
    ) {
        public val hasDropped: Boolean get() = droppedRows > 0
    }

    public fun normalize(rows: List<RawScheduleRow>, term: Term): Outcome {
        if (rows.isEmpty()) return Outcome(emptyList())

        val dropReasons = LinkedHashMap<String, Int>()
        fun drop(reason: String) {
            dropReasons[reason] = (dropReasons[reason] ?: 0) + 1
        }

        // key -> 累积中的课程
        val groups = LinkedHashMap<String, GroupAccumulator>()

        for (row in rows) {
            if (row.courseName.isBlank()) {
                drop("课程名为空")
                continue
            }
            if (row.dayOfWeek !in 1..7) {
                drop("dayOfWeek=${row.dayOfWeek}")
                continue
            }
            val sections = if (row.sectionsPaired) {
                SectionRunSplitter.parsePaired(row.sectionsRaw).sections
            } else {
                SectionRunSplitter.parseCommaSeparated(row.sectionsRaw).sections
            }
            val runs = SectionRunSplitter.splitIntoRuns(sections)
            if (runs.isEmpty()) {
                drop("节次无法解析(raw='${row.sectionsRaw}')")
                continue
            }
            if (row.weeks.isEmpty()) {
                drop("周次为空(课程=${row.courseName})")
                continue
            }

            for (run in runs) {
                val key = buildString {
                    append(row.courseCode).append('|')
                    append(row.teachingClass).append('|')
                    append(row.courseName).append('|')
                    append(row.dayOfWeek).append('|')
                    append(run.start).append('|')
                    append(run.count).append('|')
                    append(row.classroom).append('|')
                    append(row.teacher)
                }
                val acc = groups.getOrPut(key) {
                    GroupAccumulator(
                        term = term,
                        name = row.courseName.trim(),
                        courseCode = row.courseCode.trim(),
                        teachingClass = row.teachingClass.trim(),
                        classroom = row.classroom.trim(),
                        teacher = row.teacher.trim(),
                        dayOfWeek = row.dayOfWeek,
                        startSection = run.start,
                        sectionCount = run.count,
                    )
                }
                acc.weeks += row.weeks
                row.classDate?.let { acc.dates += it }
                if (acc.description.isBlank() && row.description.isNotBlank()) {
                    acc.description = row.description.trim()
                }
            }
        }

        val warnings = mutableListOf<String>()
        val courses = groups.values.map { acc ->
            // classDates 必须与 weeks 等长且同序，TermCalendar.samplesFrom 才能用它反推开学日期。
            // getDataList 每周恰好一行，所以两者天然对齐；一旦不对齐就整体丢弃 dates（而不是硬凑）。
            val sortedWeeks = acc.weeks.sorted()
            val sortedDates = acc.dates.sorted()
            val usableDates = if (sortedDates.size == sortedWeeks.size) sortedDates else emptyList()
            if (sortedDates.isNotEmpty() && usableDates.isEmpty()) {
                warnings += "课程「${acc.name}」的上课日期数(${sortedDates.size})与周次数(${sortedWeeks.size})不一致，已忽略日期"
            }
            Course(
                term = acc.term,
                name = acc.name,
                teacher = acc.teacher,
                classroom = acc.classroom,
                dayOfWeek = acc.dayOfWeek,
                startSection = acc.startSection,
                sectionCount = acc.sectionCount,
                weeks = sortedWeeks.toSet(),
                description = acc.description,
                teachingClass = acc.teachingClass,
                courseCode = acc.courseCode,
                source = CourseSource.SCHOOL,
                classDates = usableDates,
            )
        }

        // 稳定排序：星期 → 起始节 → 课程名。让课表和列表的顺序不随接口返回顺序抖动。
        val sorted = courses.sortedWith(
            compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }, { it.teachingClass }),
        )

        return Outcome(
            courses = sorted,
            droppedRows = dropReasons.values.sum(),
            dropReasons = dropReasons,
            warnings = warnings.distinct(),
        )
    }

    private class GroupAccumulator(
        val term: Term,
        val name: String,
        val courseCode: String,
        val teachingClass: String,
        val classroom: String,
        val teacher: String,
        val dayOfWeek: Int,
        val startSection: Int,
        val sectionCount: Int,
    ) {
        val weeks = LinkedHashSet<Int>()
        val dates = mutableListOf<LocalDate>()
        var description: String = ""
    }

    /**
     * 解析 `pkrq` 之类的日期字段。
     *
     * 实测未确认 `getDataList` 是否真的返回 `pkrq`，格式也未确认，
     * 所以这里对常见写法全都试一遍，全部失败就返回 null（调用方会忽略该字段）。
     *
     * 支持：`2025-09-15`、`2025/09/15`、`2025-9-15`、`2025-09-15 00:00:00`、`20250915`。
     */
    public fun parseDateLenient(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // 截掉时间部分
        val datePart = s.substringBefore(' ').substringBefore('T')
        for (pattern in DATE_PATTERNS) {
            runCatching { LocalDate.parse(datePart, pattern) }.getOrNull()?.let { return it }
        }
        return null
    }

    private val DATE_PATTERNS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyyMMdd"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"),
    )

    /**
     * 归一化教室名。
     *
     * 研究生接口返回的 `JASMC` 形如 `"教5-301(专用课室)"`，旧后端做了这些替换：
     * 去掉中英文括号、去掉 `"专用课室"`、去掉空格。
     * 本科生接口的 `jxcdmc` 通常已经比较干净，但同样处理一遍不会有坏处，
     * 而且能保证"同一间教室"在课表上和搜索时字符串一致。
     */
    public fun normalizeClassroom(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        return s
            .replace("(", "").replace(")", "")
            .replace("（", "").replace("）", "")
            .replace("专用课室", "")
            .replace(" ", "").replace("\u3000", "")
            .trim()
    }

    /**
     * 归一化逗号分隔的多值字段（教师、教学班、教室）。
     *
     * `xsAllKbList` 的 `teaxms` / `jxbmc` / `jxcdmcs` 都可能是 `"张三,李四"` 这种多值。
     * 统一去空、去重、用顿号连接（中文语境比逗号更易读，且不会与分隔符本身混淆）。
     */
    public fun normalizeMultiValue(raw: String?, separator: String = "、"): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        return s.split(',', '，', ';', '；', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(separator)
    }
}
