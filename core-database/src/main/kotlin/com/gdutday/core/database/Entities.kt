package com.gdutday.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gdutday.core.model.CourseSource
import java.time.Instant

// ============================================================================
// Room 实体。
//
// ## 命名约定
//
// 表名用**单数蛇形**（`course` 而不是 `courses`），列名同样蛇形。
// 与领域模型（core-model）的字段名保持一一对应，映射代码见 Mappers.kt，
// 手写而不是靠 Room 的自动映射 —— 因为领域模型里有派生属性
// （Course.endSection、weeksDisplay）和强类型（Term、Set<Int>），
// 自动映射会在这些地方悄悄出错。
//
// ## 类型选择
//
// Room 不直接支持 LocalDate / Set<Int> / 枚举 / data class，
// 所以这里全部存成基础类型（String / Int / Long），转换集中在 Converters.kt。
// 好处是 schema 简单、迁移可控；代价是多一层映射代码。
// ============================================================================

/**
 * 课程表。
 *
 * 一行 = 一个 [com.gdutday.core.model.Course] = 某学期·某教学班·某星期·某段连续节次·一组周次。
 *
 * ## 索引
 *
 * - `(term_code)`：切换学期时整批换数据，这是最主要的查询
 * - `(term_code, day_of_week)`：日视图和 Widget 的"今天有什么课"
 * - `(name)`：按课程名聚合配色
 *
 * `weeks` 存成逗号分隔的字符串（`"1,2,3,…,16"`）而不是单独开一张周次关联表：
 * 周次永远跟着课程整体读写，从不单独查询，拆开只会让每次同步多几十次 INSERT。
 *
 * ## ⚠ 同步策略：删了重插，但**只删 source=SCHOOL 的**
 *
 * 用户手动加的课（[CourseSource.CUSTOM]）必须在同步中存活。
 * 见 `CourseDao.replaceSchoolCourses`。
 */
@Entity(
    tableName = "course",
    indices = [
        Index("term_code"),
        Index("term_code", "day_of_week"),
        Index("name"),
        Index("source"),
    ],
)
public data class CourseEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    public val id: Long = 0L,

    /** 学期短码，如 `"20251"`。见 [com.gdutday.core.model.Term.shortCode]。 */
    @ColumnInfo(name = "term_code")
    public val termCode: String,

    @ColumnInfo(name = "name")
    public val name: String,

    @ColumnInfo(name = "teacher", defaultValue = "")
    public val teacher: String = "",

    @ColumnInfo(name = "classroom", defaultValue = "")
    public val classroom: String = "",

    /** 1=周一 … 7=周日（ISO-8601，与 `java.time.DayOfWeek.value` 一致）。 */
    @ColumnInfo(name = "day_of_week")
    public val dayOfWeek: Int,

    @ColumnInfo(name = "start_section")
    public val startSection: Int,

    @ColumnInfo(name = "section_count")
    public val sectionCount: Int,

    /** 逗号分隔升序，如 `"1,2,3"`。 */
    @ColumnInfo(name = "weeks", defaultValue = "")
    public val weeks: String = "",

    @ColumnInfo(name = "description", defaultValue = "")
    public val description: String = "",

    @ColumnInfo(name = "teaching_class", defaultValue = "")
    public val teachingClass: String = "",

    @ColumnInfo(name = "course_code", defaultValue = "")
    public val courseCode: String = "",

    /** [CourseSource] 的名字。 */
    @ColumnInfo(name = "source", defaultValue = "SCHOOL")
    public val source: String = CourseSource.SCHOOL.name,

    /** 用户/自动分配的颜色 key，null 表示尚未分配。 */
    @ColumnInfo(name = "color_key")
    public val colorKey: String? = null,

    /** 逗号分隔的 ISO 日期，与 `weeks` **等长且同序**。长度不等表示数据不可用，读取时丢弃。 */
    @ColumnInfo(name = "class_dates", defaultValue = "")
    public val classDates: String = "",

    /** 本行最后写入时刻，用于诊断。 */
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    public val updatedAt: Long = 0L,

    /**
     * 绝对开始分钟（08:30 = 510）。-1 表示未设置，读取时仍走"节次 → 作息表"换算。
     * 用户把课程时间改成非整节边界（如 08:30–09:15）时写入。
     */
    @ColumnInfo(name = "start_minute", defaultValue = "-1")
    public val startMinute: Int = -1,

    /** 绝对结束分钟。-1 表示未设置，读取时仍走"节次 → 作息表"换算。 */
    @ColumnInfo(name = "end_minute", defaultValue = "-1")
    public val endMinute: Int = -1,

    /**
     * [CourseSource.OVERRIDE] 行的作用范围，存 [com.gdutday.core.model.OverrideScope] 的名字；
     * 非 OVERRIDE 行为 null。
     */
    @ColumnInfo(name = "override_scope")
    public val overrideScope: String? = null,

    /**
     * OVERRIDE 行覆盖的目标课程自然键（见 `Course.naturalKey`）。
     * 同步后按它把补丁重新覆盖到教务课程上；非 OVERRIDE 行为 null。
     */
    @ColumnInfo(name = "override_target_nk")
    public val overrideTargetNaturalKey: String? = null,

    /**
     * OVERRIDE 行接管的周次，逗号分隔（如 `"3,4,5"`）；非 OVERRIDE 行为 null。
     */
    @ColumnInfo(name = "override_weeks")
    public val overrideWeeks: String? = null,
)

/**
 * 考试安排。
 *
 * 独立成表而不塞进 `course`：考试有日期、时段、校区、类别等课程没有的属性，
 * 而且渲染时才需要把它投影成课表上的色块（见 `ScheduleGridBuilder`）。
 */
@Entity(
    tableName = "exam",
    indices = [Index("term_code"), Index("date")],
)
public data class ExamEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    public val id: Long = 0L,

    @ColumnInfo(name = "term_code")
    public val termCode: String,

    @ColumnInfo(name = "course_name")
    public val courseName: String,

    @ColumnInfo(name = "course_code", defaultValue = "")
    public val courseCode: String = "",

    /** ISO 日期，如 `"2025-12-20"`。建索引，因为"最近的考试"要按日期排序取第一条。 */
    @ColumnInfo(name = "date")
    public val date: String,

    /** `HH:mm`，可空（教务处有时不给时间）。 */
    @ColumnInfo(name = "start_time")
    public val startTime: String? = null,

    @ColumnInfo(name = "end_time")
    public val endTime: String? = null,

    @ColumnInfo(name = "classroom", defaultValue = "")
    public val classroom: String = "",

    /** [com.gdutday.core.model.Campus] 的名字。 */
    @ColumnInfo(name = "campus", defaultValue = "UNKNOWN")
    public val campus: String = "UNKNOWN",

    @ColumnInfo(name = "category", defaultValue = "")
    public val category: String = "",

    @ColumnInfo(name = "arrangement_type", defaultValue = "")
    public val arrangementType: String = "",
)

/**
 * 成绩。
 *
 * 没有唯一索引：同一门课可能重修多次，产生多行同名记录，都是合法的。
 * 主键用自增 id，查询按 `term_name` 分组。
 */
@Entity(
    tableName = "grade",
    indices = [Index("term_name"), Index("term_code")],
)
public data class GradeEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    public val id: Long = 0L,

    /** 教务系统给的学期中文名，如 `"2024-2025学年第一学期"`。**这是分组键**。 */
    @ColumnInfo(name = "term_name")
    public val termName: String,

    @ColumnInfo(name = "term_code")
    public val termCode: String? = null,

    @ColumnInfo(name = "course_name")
    public val courseName: String,

    @ColumnInfo(name = "course_category", defaultValue = "")
    public val courseCategory: String = "",

    @ColumnInfo(name = "course_sub_category", defaultValue = "")
    public val courseSubCategory: String = "",

    @ColumnInfo(name = "study_mode", defaultValue = "")
    public val studyMode: String = "",

    /** 原始成绩文本。可能是 `"87"`，也可能是 `"优秀"`、`"合格"`、`""`。 */
    @ColumnInfo(name = "score_text", defaultValue = "")
    public val scoreText: String = "",

    @ColumnInfo(name = "score")
    public val score: Double? = null,

    @ColumnInfo(name = "gpa")
    public val gpa: Double? = null,

    @ColumnInfo(name = "credit")
    public val credit: Double? = null,
)

/**
 * 学期元信息。
 *
 * ## 为什么单独一张表
 *
 * 学期开始日期（`semester_start`）**不在教务系统的课表接口里**，
 * 但周次换算离不开它。它的来源有四级（见 `SemesterStartResolver`）：
 * 用户手填 > 从 `pkrq` 反推 > 内置已知表 > 粗略猜测。
 * 反推和猜测的结果需要落盘，否则每次冷启动都要重新算，
 * 而且"用户手填"必须有地方存。
 *
 * 一行一个学期，主键是学期短码。
 */
@Entity(tableName = "term_meta")
public data class TermMetaEntity(
    /** 学期短码，如 `"20251"`。 */
    @PrimaryKey
    @ColumnInfo(name = "term_code")
    public val termCode: String,

    /** 学期长码，如 `"202501"`。冗余存储，免得每次查询都要换算。 */
    @ColumnInfo(name = "xnxqdm", defaultValue = "")
    public val xnxqdm: String = "",

    /** 教务系统给的中文名，如 `"2025-2026学年第一学期"`。 */
    @ColumnInfo(name = "display_name", defaultValue = "")
    public val displayName: String = "",

    /** 是否教务系统标记的当前学期。 */
    @ColumnInfo(name = "is_current", defaultValue = "0")
    public val isCurrent: Boolean = false,

    /** 第 1 周的周一，ISO 日期。 */
    @ColumnInfo(name = "semester_start")
    public val semesterStart: String,

    /**
     * `semester_start` 的来源，见 [SemesterStartSource]。
     * 存下来是为了在 UI 上告诉用户"这个日期是自动推算的，可能不准，建议校准"。
     */
    @ColumnInfo(name = "start_source", defaultValue = "GUESSED")
    public val startSource: String = SemesterStartSource.GUESSED.name,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    public val updatedAt: Long = 0L,
)

/** 学期开始日期的来源，可信度从高到低。 */
public enum class SemesterStartSource(
    /** UI 上要不要提示用户"这个日期可能不准，请去设置里校准"。 */
    public val needsUserConfirmation: Boolean,
) {
    /** 用户在设置里手填的。最高可信度。 */
    USER(false),

    /** 从课表的 `pkrq`（具体上课日期）+ `zc`（周次）反推。很可靠。 */
    DERIVED(false),

    /** 命中 [com.gdutday.core.common.KnownSemesterStarts] 内置表。 */
    KNOWN_TABLE(false),

    /** 粗略猜测（第一学期 9 月第一个周一 / 第二学期 2 月下旬）。**误差可达两周**。 */
    GUESSED(true),
    ;

    public companion object {
        public fun fromName(raw: String?): SemesterStartSource =
            entries.firstOrNull { it.name == raw } ?: GUESSED
    }
}

/**
 * 课程名 → 颜色 key 的持久映射。
 *
 * ## 为什么需要这张表
 *
 * 自动配色是"课程名排序后按位次分配"（见 [com.gdutday.core.common.CourseColors]）。
 * 如果不持久化，每次同步后新增一门排序靠前的课，它后面所有课的颜色都会顺移 ——
 * 用户的课表会莫名其妙变色。
 *
 * 存下来之后：**只有全新的课程名才参与分配**，老课程的颜色永久固定，
 * 用户手动改过的颜色也不会被同步覆盖。
 */
@Entity(tableName = "course_color")
public data class CourseColorEntity(
    @PrimaryKey
    @ColumnInfo(name = "course_name")
    public val courseName: String,

    @ColumnInfo(name = "color_key")
    public val colorKey: String,

    /** true 表示用户手动指定过，自动配色不应改动它。 */
    @ColumnInfo(name = "is_user_chosen", defaultValue = "0")
    public val isUserChosen: Boolean = false,
)

/**
 * 同步状态。
 *
 * 只有一行（`id = 1`），记录上次同步结果。用于：
 * - 首屏显示"上次更新：3 天前"
 * - 判断是否需要自动刷新（旧小程序用 `countTimes[20]` 数组记"本周是否已刷"，
 *   那个方案在跨学期时会失效；这里直接记时间戳）
 * - Widget 上标注数据新鲜度
 */
@Entity(tableName = "sync_state")
public data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    public val id: Int = SINGLETON_ID,

    @ColumnInfo(name = "last_sync_at")
    public val lastSyncAt: Instant? = null,

    @ColumnInfo(name = "last_term_code")
    public val lastTermCode: String? = null,

    /** 上次同步是否成功。失败时 UI 要显示"数据可能过期"。 */
    @ColumnInfo(name = "last_success", defaultValue = "1")
    public val lastSuccess: Boolean = true,

    /** 上次失败的用户可读原因。 */
    @ColumnInfo(name = "last_error", defaultValue = "")
    public val lastError: String = "",

    /** 实际命中的课表接口，写进"关于 → 诊断信息"。 */
    @ColumnInfo(name = "last_schedule_source", defaultValue = "")
    public val lastScheduleSource: String = "",

    /** 上次同步产生的非致命警告（丢弃了几行、翻页是否取全等）。 */
    @ColumnInfo(name = "last_warnings", defaultValue = "")
    public val lastWarnings: String = "",
) {
    public companion object {
        public const val SINGLETON_ID: Int = 1
    }
}


