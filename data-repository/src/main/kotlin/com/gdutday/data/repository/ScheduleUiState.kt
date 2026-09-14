package com.gdutday.data.repository

import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.common.CourseColor
import com.gdutday.core.common.TermCalendar
import com.gdutday.core.common.WeekGrid
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.Exam
import com.gdutday.core.model.ScheduleSource
import com.gdutday.core.model.Term
import java.time.Instant
import java.time.LocalDate

/**
 * 课表页的**完整 UI 状态**。
 *
 * ## 为什么把所有计算都放在 Repository 而不是 ViewModel
 *
 * 课表页要展示的东西需要**五个数据源联合计算**：
 * 课程（Room）+ 考试（Room）+ 学期开始日期（Room/用户设置）+ 作息表（设置/校区）
 * + 配色（Room/自动分配）。
 *
 * 如果放在 ViewModel 里，会有三个问题：
 * 1. Widget 需要同样的计算（"今天有什么课"、"下节课几点"），逻辑得复制一份；
 * 2. ViewModel 会膨胀到上千行，且每次配置变更（旋转屏幕）都要重算；
 * 3. 单元测试要挂 `Dispatchers.Main` 和 Android 环境，跑不起来。
 *
 * 放在 Repository 里，输出一个纯数据类，Widget 和 UI 共用同一份计算，
 * 测试也只是普通的 JVM 测试。
 *
 * ## 为什么字段这么多，而不是让 UI 自己算
 *
 * 因为**每一个字段都对应旧小程序里一段散落在 .vue 模板里的逻辑**。
 * 集中到这里之后，"课表显示不对"这类 bug 只需要查一个地方。
 */
public data class ScheduleUiState(

    /** 正在同步。UI 显示顶部细进度条（不是全屏 loading —— 首屏应该立刻出内容）。 */
    public val isSyncing: Boolean = false,

    /** 首次加载、数据库里还什么都没有。UI 显示空状态插画。 */
    public val isInitialLoad: Boolean = true,

    // ---------------------------------------------------------------- 学期

    /** 当前显示的学期。null 表示还没登录或还没同步过。 */
    public val term: Term? = null,

    /** 教务系统里可选的全部学期，按时间倒序。学期切换下拉框的数据源。 */
    public val availableTerms: List<Term> = emptyList(),

    /** 教务系统标记的"当前学期"。null 表示未知。 */
    public val currentTerm: Term? = null,

    // ---------------------------------------------------------------- 周次

    /** 学期历。UI 用它把周次换算成日期（周视图顶部的 "9/1 - 9/7"）。 */
    public val calendar: TermCalendar? = null,

    /** 学期开始日期的来源。[SemesterStartSource.GUESSED] 时 UI 必须提示用户去校准。 */
    public val semesterStartSource: SemesterStartSource = SemesterStartSource.GUESSED,

    /** 今天实际所处的周次（1-based，可能超出 totalWeeks）。 */
    public val todayWeek: Int = 1,

    /** 用户当前选中的周次。默认等于 [todayWeek]。 */
    public val selectedWeek: Int = 1,

    /** 周次选择器的上限。由数据推导，保底 20（见 `ScheduleSnapshot.maxWeek`）。 */
    public val totalWeeks: Int = 20,

    // ---------------------------------------------------------------- 网格

    /** 生效的作息表。UI 用它渲染左侧的节次列和时间。 */
    public val timetable: CampusTimetable? = null,

    /** 校区。[Campus.UNKNOWN] 时 UI 提示用户去设置里选。 */
    public val campus: Campus = Campus.UNKNOWN,

    /** **选中周**的课表网格。这是周视图的直接数据源。 */
    public val grid: WeekGrid? = null,

    /** **选中周前一周**的网格。周视图三页预渲染用；首周（越界）或无学期历时为 null。 */
    public val prevWeekGrid: WeekGrid? = null,

    /** **选中周后一周**的网格。周视图三页预渲染用；末周（越界）或无学期历时为 null。 */
    public val nextWeekGrid: WeekGrid? = null,

    /** **今天**的课程块，按时间升序。日视图和首页卡片用。 */
    public val todayBlocks: List<CourseBlock> = emptyList(),

    /** **明天**的课程块，按时间升序。桌面组件的"今天↔明天"切换用。 */
    public val tomorrowBlocks: List<CourseBlock> = emptyList(),

    /** 课程名 → 颜色。课程详情页的色块选择器用。 */
    public val colorAssignment: Map<String, CourseColor> = emptyMap(),

    // ---------------------------------------------------------------- 原始数据

    /** 选中学期的全部课程（含自定义）。课程管理页用。 */
    public val courses: List<Course> = emptyList(),

    /** 选中学期的全部考试。考试安排页用。 */
    public val exams: List<Exam> = emptyList(),

    /** 本学期全部课程的去重名称，按拼音/字典序。添加自定义课程时做冲突检测用。 */
    public val courseNames: List<String> = emptyList(),

    // ---------------------------------------------------------------- 状态提示

    /** 上次同步信息。UI 显示"上次更新：3 天前"。 */
    public val lastSync: SyncInfo? = null,

    /**
     * 用户可读的错误。非 null 时 UI 显示 Snackbar / 顶部横幅。
     *
     * **同步失败不清空已有数据** —— 这是与旧小程序最大的体验差异之一。
     * 旧版同步失败会把课表显示成空的，用户在教务系统维护期间完全看不到自己的课。
     */
    public val errorMessage: String? = null,

    /** 非致命警告（丢弃了几行脏数据、翻页可能没取全等）。UI 折叠在"详情"里。 */
    public val warnings: List<String> = emptyList(),
) {
    /** 是否已经登录过（有可用数据或至少有学期信息）。 */
    public val hasAnyData: Boolean get() = courses.isNotEmpty() || availableTerms.isNotEmpty()

    /** 选中的周是否是本周。UI 用来决定"回到本周"按钮要不要显示。 */
    public val isViewingCurrentWeek: Boolean get() = selectedWeek == todayWeek

    /** 今天的日期。 */
    public val today: LocalDate get() = calendar?.mondayOf(todayWeek)?.plusDays((LocalDate.now().dayOfWeek.value - 1).toLong())
        ?: LocalDate.now()
}

/**
 * 上次同步的结果摘要。
 *
 * @property at 同步完成时刻。null 表示从未同步过。
 * @property term 同步的是哪个学期。
 * @property source 实际命中的课表接口，写进"关于 → 诊断信息"。
 * @property success 是否成功。失败时 [error] 有值，但**旧数据仍然保留**。
 * @property courseCount 同步到多少门课。0 且 success=true 时 UI 要提示"本学期暂无课表"。
 */
public data class SyncInfo(
    public val at: Instant? = null,
    public val term: Term? = null,
    public val source: ScheduleSource = ScheduleSource.UNKNOWN,
    public val success: Boolean = true,
    public val error: String = "",
    public val courseCount: Int = 0,
    public val examCount: Int = 0,
    public val warnings: List<String> = emptyList(),
) {
    /** "3 天前" / "刚刚" / "5 小时前"。UI 直接显示，不需要再写一遍相对时间逻辑。 */
    public fun relativeTime(now: Instant = Instant.now()): String {
        val t = at ?: return "从未同步"
        val seconds = java.time.Duration.between(t, now).seconds
        return when {
            seconds < 60 -> "刚刚"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            seconds < 86400 -> "${seconds / 3600} 小时前"
            seconds < 86400 * 30 -> "${seconds / 86400} 天前"
            else -> "${seconds / (86400 * 30)} 个月前"
        }
    }
}

/**
 * "下一节课"信息。首页卡片和 Widget 的核心内容。
 *
 * @property block 课程块
 * @property date 上课日期（可能是明天、下周）
 * @property week 上课周次
 * @property startsInMinutes 距现在多少分钟。**负数表示正在上**（此时 [isOngoing] 为 true）。
 * @property isOngoing 是否正在上课
 * @property minutesRemaining 若正在上课，还剩多少分钟下课
 */
public data class NextClass(
    public val block: CourseBlock,
    public val date: LocalDate,
    public val week: Int,
    public val startsInMinutes: Long,
    public val isOngoing: Boolean = false,
    public val minutesRemaining: Long = 0L,
) {
    /** "还有 25 分钟" / "正在进行，还有 30 分钟下课" / "3 天后"。 */
    public val countdownText: String
        get() = when {
            isOngoing -> "进行中 · 还有 $minutesRemaining 分钟下课"
            startsInMinutes < 60 -> "还有 $startsInMinutes 分钟"
            startsInMinutes < 24 * 60 -> "还有 ${startsInMinutes / 60} 小时"
            else -> "还有 ${startsInMinutes / (24 * 60)} 天"
        }

    /** "教5-301 · 08:30"。 */
    public val locationText: String
        get() = listOf(block.course.classroom, block.startClock)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}
