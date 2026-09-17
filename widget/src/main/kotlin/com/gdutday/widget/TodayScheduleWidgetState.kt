package com.gdutday.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.glance.state.GlanceStateDefinition
import com.gdutday.core.common.BlockStatus
import com.gdutday.data.repository.ScheduleUiState
import kotlinx.coroutines.flow.combine
import java.io.File
import java.time.LocalDate

/**
 * "今日课程"插件要展示的最终数据。
 *
 * 刻意做成一个**已经算好、可直接渲染**的快照，而不是把 `ScheduleUiState` 直接塞进来：
 * 1. Glance 的 Compose 每次重组都会跑样式判断，数据越接近最终形态，重组越便宜；
 * 2. 尺寸、日期格式化这些逻辑与 Glance 无关，可以在纯 JVM 里单测；
 * 3. 快照是不可变 data class，与上一次比较即可判断"是否需要重画"。
 */
public data class TodayScheduleWidgetState(
    public val phase: TodayPhase,
    /** 表头："9月10日 周三 · 第2周"。 */
    public val dateLine: String,
    public val rows: List<WidgetCourseRow>,
    /** 当前展示的是哪一天：0 = 今天，1 = 明天。右侧竖条的文案据此反转。 */
    public val dayOffset: Int = 0,
) {
    public companion object {
        /** 初始/读取中占位。 */
        public val Loading: TodayScheduleWidgetState =
            TodayScheduleWidgetState(TodayPhase.LOADING, "", emptyList())
    }
}

/**
 * 空状态四分类。
 *
 * `ALL_FINISHED` 与 `NO_CLASS` 必须分开：前者是"今天本来有课，都上完了"，
 * 用户看到会觉得踏实；后者是"今天就没排课"。两者文案不同。
 */
public enum class TodayPhase {
    LOADING,
    NOT_LOGGED_IN,
    NO_CLASS,
    ALL_FINISHED,
    HAS_CLASS,
}

/** 列表里的一行课程。 */
public data class WidgetCourseRow(
    public val startClock: String,
    public val endClock: String,
    public val name: String,
    public val teacher: String,
    public val classroom: String,
    public val colorArgb: Int,
    public val status: BlockStatus,
)

/**
 * `ScheduleUiState` + 登录态 → 插件快照 的纯映射。
 *
 * 没有 Android 依赖，唯一的外部输入是 `today`，测试可固化。
 */
public object TodayScheduleMapper {

    /**
     * @param ui 课表状态；null 表示仓库还没给出第一帧 → 加载中
     * @param loggedIn 是否已登录。未登录且没有任何本地数据时才显示"未登录"
     * @param today 今天。由调用方传入，避免在映射里读墙钟
     * @param dayOffset 0 = 今天，1 = 明天。决定取 [ScheduleUiState.todayBlocks] 还是
     *   [ScheduleUiState.tomorrowBlocks]，以及表头日期。
     */
    public fun map(
        ui: ScheduleUiState?,
        loggedIn: Boolean,
        today: LocalDate,
        dayOffset: Int = 0,
    ): TodayScheduleWidgetState {
        if (ui == null) return TodayScheduleWidgetState.Loading
        val target = today.plusDays(dayOffset.toLong())
        val dateLine = buildDateLine(ui, target)

        // 有本地数据（换账号、断网）时即使 isLoggedIn=false 也照常显示，不要因为
        // 会话过期把用户已有的课表藏起来 —— 与 ScheduleUiState 的错误处理原则一致。
        if (!loggedIn && ui.courses.isEmpty() && ui.availableTerms.isEmpty()) {
            return TodayScheduleWidgetState(TodayPhase.NOT_LOGGED_IN, dateLine, emptyList(), dayOffset)
        }

        val blocks = if (dayOffset == 0) ui.todayBlocks else ui.tomorrowBlocks
        if (blocks.isEmpty()) {
            return TodayScheduleWidgetState(TodayPhase.NO_CLASS, dateLine, emptyList(), dayOffset)
        }

        // 已经上完的课不再占位：插件高度是按格数算的，位置有限，宝贵的行数要留给
        // "接下来要上的课"。全部上完时 [pending] 为空，此时仍要给出 ALL_FINISHED ——
        // "今天有课，都上完了" 和 "今天没课" 是两种完全不同的信息，不能合并。
        val pending = blocks.filter { it.status != BlockStatus.FINISHED }
        if (pending.isEmpty()) {
            return TodayScheduleWidgetState(TodayPhase.ALL_FINISHED, dateLine, emptyList(), dayOffset)
        }

        val rows = pending.map { block ->
            WidgetCourseRow(
                startClock = block.startClock,
                endClock = block.endClock,
                name = block.course.name,
                teacher = block.course.teacher,
                classroom = block.course.classroom,
                colorArgb = block.color.argb,
                status = block.status,
            )
        }
        return TodayScheduleWidgetState(TodayPhase.HAS_CLASS, dateLine, rows, dayOffset)
    }

    /**
     * "9月10日 周三 · 第2周"。周次不在合法区间（放假/未开学）时省略周次。
     *
     * [date] 可以是明天：周次优先按学期历算该日期属于第几周，明天跨到下一周时
     * 表头也要跟着变；没有学期历时回退到 `ui.todayWeek`。
     */
    internal fun buildDateLine(ui: ScheduleUiState, date: LocalDate): String {
        val week = ui.calendar?.weekOf(date) ?: ui.todayWeek
        val weekPart = if (week in 1..ui.totalWeeks) " · 第${week}周" else ""
        return "${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date.dayOfWeek.value)}$weekPart"
    }

    internal fun weekdayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> ""
    }
}

/**
 * 把 Room / DataStore 的 Flow 喂给 Glance 的状态定义。
 *
 * ## 这是 Glance 相对 RemoteViews 的核心优势
 *
 * 传统 RemoteViews 想更新插件，必须由 App 主动发广播/设 Alarm，再在 receiver 里读数据、
 * 重建整个 RemoteViews。这里把数据流接到 `GlanceStateDefinition` 上，Glance 每次
 * `update()` 都会通过 `getDataStore(...).data.first()` 重新订阅这段 combine，
 * 于是"插件显示的内容"与"数据库里的真相"永远不会脱节，且不需要任何常驻的
 * 广播接收器或定时器。
 *
 * 数据变化的**触发**由 [WidgetUpdateManager] 负责（同步完成 / 跨天 / 手动点刷新）；
 * 这里负责的是"触发之后读到的一定是最新数据"。
 */
internal class TodayScheduleWidgetStateDefinition : GlanceStateDefinition<TodayScheduleWidgetState> {

    /**
     * Glance 在删除插件实例时会 `delete()` 这个文件。我们的状态不落盘，
     * 这里只给一个不会污染数据的路径（cache 目录，系统可随时清理）。
     */
    override fun getLocation(context: Context, fileKey: String): File = File(context.cacheDir, fileKey)

    override suspend fun getDataStore(
        context: Context,
        fileKey: String,
    ): DataStore<TodayScheduleWidgetState> {
        val container = context.widgetContainer()
        return FlowDataStore {
            combine(
                container.scheduleRepository.observeScheduleUiState(),
                container.authRepository.isLoggedIn,
            ) { ui, loggedIn ->
                // 今天/明天的选择由用户在插件上切换并落盘，每次 update() 重新读取；
                // 切换动作本身会触发 updateToday()，所以这里同步读即可，不需要再开一条 Flow。
                TodayScheduleMapper.map(ui, loggedIn, LocalDate.now(), WidgetDayPreference.read(context))
            }
        }
    }
}
