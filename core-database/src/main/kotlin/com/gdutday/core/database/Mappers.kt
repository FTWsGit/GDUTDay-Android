package com.gdutday.core.database

import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Exam
import com.gdutday.core.model.Grade
import com.gdutday.core.model.Term
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 领域模型 ↔ Room 实体的映射。
 *
 * ## 为什么手写而不是靠 Room 自动映射
 *
 * 领域模型里有三类东西 Room 处理不了：
 * 1. **强类型值对象**：[Term] 有短码/长码两种表示，[Campus]/[CourseSource] 是枚举
 * 2. **派生属性**：`Course.endSection`、`Course.weeksDisplay`、`Grade.countsTowardsGpa`
 * 3. **集合**：`weeks: Set<Int>`、`classDates: List<LocalDate>`
 *
 * 自动映射会在这些地方悄悄出错（比如把 `endSection` 当成可写字段、
 * 把 `Set` 序列化成 `toString()` 的 `[1, 2, 3]` 再解析失败）。
 * 手写多花几十行，换来的是**每条转换都有单元测试盯着**。
 *
 * ## 一条贯穿全局的原则：读库时永不抛异常
 *
 * 数据库里可能有旧版本写入的脏数据（升级、降级、中途崩溃）。
 * 所有 `fromEntity` 方法遇到解析不了的字段都**降级为默认值**，
 * 最多丢掉那一门课，绝不让整个课表页崩掉。
 */
public object Mappers {

    // ------------------------------------------------------------------ Course

    public fun Course.toEntity(updatedAt: Long = System.currentTimeMillis()): CourseEntity = CourseEntity(
        id = id,
        termCode = term.shortCode,
        name = name,
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        sectionCount = sectionCount,
        weeks = encodeWeeks(weeks),
        description = description,
        teachingClass = teachingClass,
        courseCode = courseCode,
        source = source.name,
        colorKey = colorKey,
        classDates = encodeDates(classDates),
        updatedAt = updatedAt,
    )

    /**
     * @return 反序列化失败（星期/节次越界、学期码非法）时返回 null，该行被跳过。
     */
    public fun CourseEntity.toDomain(): Course? {
        val term = Term.parse(termCode) ?: return null
        if (dayOfWeek !in 1..7) return null
        if (startSection < 1 || sectionCount < 1) return null
        return Course(
            id = id,
            term = term,
            name = name,
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            sectionCount = sectionCount,
            weeks = decodeWeeks(weeks),
            description = description,
            teachingClass = teachingClass,
            courseCode = courseCode,
            source = decodeSource(source),
            colorKey = colorKey,
            // classDates 只有在与 weeks 等长时才可用，
            // 否则 TermCalendar.samplesFrom 会算出错误的开学日期
            classDates = decodeDates(classDates).let { dates ->
                val w = decodeWeeks(weeks)
                if (dates.size == w.size) dates.sorted() else emptyList()
            },
        )
    }

    // ------------------------------------------------------------------ Exam

    public fun Exam.toEntity(): ExamEntity = ExamEntity(
        id = id,
        termCode = term.shortCode,
        courseName = courseName,
        courseCode = courseCode,
        date = date.toString(),
        startTime = startTime?.toString(),
        endTime = endTime?.toString(),
        classroom = classroom,
        campus = campus.name,
        category = category,
        arrangementType = arrangementType,
    )

    public fun ExamEntity.toDomain(): Exam? {
        val term = Term.parse(termCode) ?: return null
        val date = parseDateLenient(this.date) ?: return null
        return Exam(
            id = id,
            term = term,
            courseName = courseName,
            courseCode = courseCode,
            date = date,
            startTime = parseTimeLenient(startTime),
            endTime = parseTimeLenient(endTime),
            classroom = classroom,
            campus = decodeCampus(campus),
            category = category,
            arrangementType = arrangementType,
        )
    }

    // ------------------------------------------------------------------ Grade

    public fun Grade.toEntity(): GradeEntity = GradeEntity(
        id = id,
        termName = termName,
        termCode = term?.shortCode,
        courseName = courseName,
        courseCategory = courseCategory,
        courseSubCategory = courseSubCategory,
        studyMode = studyMode,
        scoreText = scoreText,
        score = score,
        gpa = gpa,
        credit = credit,
    )

    public fun GradeEntity.toDomain(): Grade = Grade(
        id = id,
        termName = termName,
        term = Term.parse(termCode),
        courseName = courseName,
        courseCategory = courseCategory,
        courseSubCategory = courseSubCategory,
        studyMode = studyMode,
        scoreText = scoreText,
        score = score,
        gpa = gpa,
        credit = credit,
    )

    // ------------------------------------------------------------------ TermMeta

    public fun TermMetaEntity.toTerm(): Term? = Term.parse(termCode)

    // ------------------------------------------------------------------ 编解码

    /** `setOf(3,1,2)` → `"1,2,3"`。排序是为了让两次同步的写入结果可比对。 */
    public fun encodeWeeks(weeks: Set<Int>): String =
        if (weeks.isEmpty()) "" else weeks.sorted().joinToString(",")

    /** `"1,2,3"` → `setOf(1,2,3)`。非法片段跳过。 */
    public fun decodeWeeks(raw: String): Set<Int> {
        if (raw.isBlank()) return emptySet()
        val out = LinkedHashSet<Int>()
        for (token in raw.split(',')) {
            val n = token.trim().toIntOrNull() ?: continue
            if (n in 1..Course.MAX_WEEK) out += n
        }
        return out
    }

    /** `listOf(2025-09-01, …)` → `"2025-09-01,…"`（ISO 格式）。 */
    public fun encodeDates(dates: List<LocalDate>): String =
        if (dates.isEmpty()) "" else dates.joinToString(",")

    public fun decodeDates(raw: String): List<LocalDate> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { parseDateLenient(it) }
    }

    /**
     * 宽松解析 ISO 日期。
     *
     * 数据库里可能存着 `"2025-9-1"`（旧版本写入）或带时间的 `"2025-09-01 00:00:00"`。
     */
    public fun parseDateLenient(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val datePart = s.substringBefore(' ').substringBefore('T')
        runCatching { LocalDate.parse(datePart) }.getOrNull()?.let { return it }
        val parts = datePart.split('-', '/', '.')
        if (parts.size != 3) return null
        val (y, m, d) = parts
        return runCatching { LocalDate.of(y.trim().toInt(), m.trim().toInt(), d.trim().toInt()) }.getOrNull()
    }

    /** 宽松解析 `HH:mm`（兼容 `H:mm`、带秒的 `HH:mm:ss`）。 */
    public fun parseTimeLenient(raw: String?): LocalTime? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        runCatching { LocalTime.parse(s) }.getOrNull()?.let { return it }
        val parts = s.split(':')
        if (parts.size < 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        val sec = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
        return runCatching { LocalTime.of(h, m, sec) }.getOrNull()
    }

    public fun decodeSource(raw: String?): CourseSource =
        CourseSource.entries.firstOrNull { it.name == raw } ?: CourseSource.SCHOOL

    public fun decodeCampus(raw: String?): Campus =
        Campus.entries.firstOrNull { it.name == raw } ?: Campus.UNKNOWN

    /** 逗号分隔字符串 ↔ 列表，用于 `SyncStateEntity.lastWarnings` 这类多值文本。 */
    public fun encodeStrings(values: List<String>): String =
        values.filter { it.isNotBlank() }.joinToString("\u0001")

    public fun decodeStrings(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else raw.split('\u0001').filter { it.isNotBlank() }

    /** 当前时间戳，集中一处方便测试时替换。 */
    public fun nowMillis(): Long = System.currentTimeMillis()

    public fun nowInstant(): Instant = Instant.now()
}
