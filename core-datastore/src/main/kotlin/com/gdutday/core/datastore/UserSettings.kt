package com.gdutday.core.datastore

import com.gdutday.core.model.Campus
import com.gdutday.core.model.ScheduleFetchStrategy
import com.gdutday.core.model.SyncSourceType
import com.gdutday.core.model.Term
import kotlinx.coroutines.flow.Flow

/**
 * 用户偏好设置的完整快照。
 *
 * ## 为什么是一个不可变 data class + 单个 Flow
 *
 * 而不是几十个独立的 `Flow<Boolean>` / `Flow<Int>`：
 * 1. 设置页需要**一次性**读到全部值来渲染，几十个 Flow 要 `combine` 起来，
 *    `combine` 的参数上限是 5 个，超了就得嵌套，非常难看；
 * 2. 设置项之间有依赖（`timetableEnabled == true` 时 `customTimetable` 才有意义），
 *    放在一个类型里可以用计算属性表达，散在多个 Flow 里就没法表达；
 * 3. DataStore 本来就是"整个 Preferences 作为一个原子快照"的模型，
 *    映射成单个 data class 是最贴合的。
 *
 * 代价：改任何一个设置都会让所有观察者收到通知。设置项总共二十来个，
 * 观察者也就课表页和设置页两个，完全不构成性能问题。
 *
 * ## 字段来源对照
 *
 * 大部分字段是从旧小程序 `pages/schedule/schedule-settings.vue` 逐条搬过来的，
 * 注释里标了原始 storage key，方便对照。
 */
public data class UserSettings(

    // ---------------------------------------------------------------- 学期与校区

    /**
     * 用户手动锁定的学期；null 表示"跟随教务系统的当前学期"。
     *
     * 旧小程序没有这个概念（它只有一个全局 `currentTerm`），
     * 结果是想看下学期课表就得改系统设置。
     */
    public val selectedTerm: Term? = null,

    /**
     * 校区。决定作息表（每节课的起止时刻）。
     *
     * 教务系统的课表接口不返回校区，只有考试安排里有 `xqmc`。
     * 所以流程是：同步时若能从考试安排探测到就自动填，探测不到保持 [Campus.UNKNOWN]，
     * 由用户在这里手选。[Campus.UNKNOWN] 的作息表回退到大学城。
     */
    public val campus: Campus = Campus.UNKNOWN,

    // ---------------------------------------------------------------- 课表外观

    /**
     * 课表视图模式。旧小程序的 `scheduleViewType`（`week` / `day`）。
     */
    public val scheduleView: ScheduleView = ScheduleView.WEEK,

    /**
     * 课程块透明度，0.0~1.0。旧小程序的 `courseBlockOpacity`，默认 80（即 0.8）。
     *
     * 背景图开启时降低透明度能让底图透出来，是旧版用户最常调的一项。
     */
    public val courseBlockAlpha: Float = DEFAULT_COURSE_BLOCK_ALPHA,

    /**
     * 是否把已上完的课程置灰。旧小程序的 `isPassedCourseBlockGray`。
     */
    public val dimFinishedCourses: Boolean = true,

    /** 课程块字体颜色。旧小程序支持 `white` / `black` / `auto`。 */
    public val courseTextColor: CourseTextColor = CourseTextColor.AUTO,

    /**
     * 课表背景图（本地相册选取，存的是 content URI 的持久化授权）。
     * null 表示纯色背景。
     */
    public val backgroundImageUri: String? = null,

    /** 背景图模糊半径 dp。0 = 不模糊。 */
    public val backgroundBlurDp: Int = 0,

    /** 是否在课程块上显示老师姓名。屏幕窄的时候关掉能省一行。 */
    public val showTeacher: Boolean = true,

    /** 是否在课程块上显示教室。 */
    public val showClassroom: Boolean = true,

    /** 是否显示第 13、14 节。绝大多数人用不到，默认隐藏以节省纵向空间。 */
    public val showExtraSections: Boolean = false,

    /** 是否显示周末两列。有些专业周末完全没课。 */
    public val showWeekend: Boolean = true,

    // ---------------------------------------------------------------- 作息表

    /**
     * 是否启用自定义作息表。关闭时使用所选校区的内置作息。
     *
     * 存在的理由：四个校区的作息是硬编码常量，学校改了不会通知我们，
     * 用户至少要有自救手段。
     */
    public val customTimetableEnabled: Boolean = false,

    /**
     * 自定义作息表，24 个 `HH:mm` 字符串（12 节的起止，交替排列）。
     *
     * 长度不等于 24 或任一项解析失败时，[com.gdutday.core.common.CampusTimetable.parseCustom]
     * 会返回 null，此时静默回退到内置作息 —— **绝不因为一处输错让课表页崩溃**。
     */
    public val customTimetable: List<String> = emptyList(),

    // ---------------------------------------------------------------- 数据源

    /**
     * 课表抓取策略。默认 [ScheduleFetchStrategy.AUTO]：先试 `getDataList`（按周返回，
     * 能还原每周对应的教室与授课内容），失败或为空则回退 `xsAllKbList`。
     *
     * 暴露给用户的理由是排查与自救：如果某天课表突然空了，
     * 让用户切到另一个接口能立刻恢复，不必等我们发版。
     */
    public val fetchStrategy: ScheduleFetchStrategy = ScheduleFetchStrategy.AUTO,

    /**
     * 课表同步源。默认个人课表；选 [SyncSourceType.CLASS_SCHEDULE] 时
     * 同步改走班级课表接口（`xsbjkbcx`），**替换**而不是合并个人课表数据。
     */
    public val syncSourceType: SyncSourceType = SyncSourceType.PERSONAL,

    /** 班级课表的班级代码（`bjdm`）。仅 [syncSourceType] 为班级课表时有意义；空 = 未选择。 */
    public val classScheduleBjdm: String = "",

    /** 班级课表的班级显示名（如"计算机25(5)"），用于 UI 展示当前同步源。 */
    public val classScheduleClassName: String = "",

    /** 是否在每次冷启动时自动同步。关闭可以省流量、加快首屏。 */
    public val autoSyncOnLaunch: Boolean = true,

    /** 自动同步的最小间隔（小时）。防止用户反复重启 App 打爆教务系统。 */
    public val autoSyncIntervalHours: Int = 6,

    // ---------------------------------------------------------------- 工具

    /** 图书馆入馆二维码使用的学号。为空时回退到当前登录用户的学号。 */
    public val libraryQrStudentId: String = "",
) {

    public companion object {
        /** 与旧小程序 `courseBlockOpacity: 80` 对齐。 */
        public const val DEFAULT_COURSE_BLOCK_ALPHA: Float = 0.80f

        /** 课程块透明度的合法区间。 */
        public val ALPHA_RANGE: ClosedFloatingPointRange<Float> = 0.30f..1.00f
    }
}

/** 课表视图模式。 */
public enum class ScheduleView(public val displayName: String) {
    /** 周视图：7 列 × N 节的网格。 */
    WEEK("周视图"),

    /** 日视图：只显示今天/指定某天的日程列表。 */
    DAY("日视图"),

    ;

    public companion object {
        public fun fromName(raw: String?): ScheduleView =
            entries.firstOrNull { it.name == raw } ?: WEEK
    }
}

/** 课程块上的字体颜色。 */
public enum class CourseTextColor(public val displayName: String) {
    /** 按背景色亮度自动选黑或白（WCAG 相对亮度公式）。 */
    AUTO("自动"),
    WHITE("白色"),
    BLACK("黑色"),
    ;

    public companion object {
        public fun fromName(raw: String?): CourseTextColor =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

/**
 * 偏好设置读写接口。实现见 `DataStoreSettingsStore`。
 *
 * 所有方法都是 `suspend`（DataStore 本身就是），除了 [settings] 是 Flow。
 */
public interface SettingsStore {

    /** 设置快照流。首次订阅时 DataStore 会读一次磁盘。 */
    public val settings: Flow<UserSettings>

    /**
     * 原子地更新设置。
     *
     * 必须用 `update {}` 而不是"读出来改完再写回去"—— 后者在并发修改时会丢更新
     * （比如用户同时改了透明度和视图模式）。
     */
    public suspend fun update(transform: (UserSettings) -> UserSettings)

    /** 恢复全部默认值。 */
    public suspend fun reset()

    // -------------------------------------------------------- 常用便捷方法
    // 这些默认实现基于 update{}，实现类不需要重写。
    // 提供它们是为了让 ViewModel 里写 `settings.setCampus(x)` 而不是
    // `settings.update { it.copy(campus = x) }` —— 前者在调用点更能表达意图。

    public suspend fun setCampus(campus: Campus) = update { it.copy(campus = campus) }

    public suspend fun setSelectedTerm(term: Term?) = update { it.copy(selectedTerm = term) }

    public suspend fun setScheduleView(view: ScheduleView) = update { it.copy(scheduleView = view) }

    /** 透明度会被钳制到 [UserSettings.ALPHA_RANGE]，避免用户拖到 0 之后课程块完全消失找不回来。 */
    public suspend fun setCourseBlockAlpha(alpha: Float) =
        update { it.copy(courseBlockAlpha = alpha.coerceIn(UserSettings.ALPHA_RANGE)) }

    public suspend fun setCustomTimetable(enabled: Boolean, timetable: List<String>) = update {
        it.copy(customTimetableEnabled = enabled, customTimetable = timetable)
    }

    public suspend fun setFetchStrategy(strategy: ScheduleFetchStrategy) = update { it.copy(fetchStrategy = strategy) }

    /** 切换课表同步源（个人课表 / 班级课表）。 */
    public suspend fun setSyncSourceType(type: SyncSourceType) = update { it.copy(syncSourceType = type) }

    /**
     * 设置班级课表的班级。
     *
     * 同时写 bjdm 与显示名：只有代码没有名字的话，UI 上的"当前同步源"就只能显示一串数字。
     */
    public suspend fun setClassSchedule(bjdm: String, className: String) =
        update { it.copy(classScheduleBjdm = bjdm.trim(), classScheduleClassName = className.trim()) }

    /**
     * 设置图书馆二维码学号。
     *
     * 虽然 issue 说"不需要格式校验"，但学号二维码内容应仅为数字；
     * 这里只做过滤（丢掉非数字字符），不拒绝非数字输入。
     */
    public suspend fun setLibraryQrStudentId(studentId: String) =
        update { it.copy(libraryQrStudentId = studentId.filter { c -> c.isDigit() }) }
}
