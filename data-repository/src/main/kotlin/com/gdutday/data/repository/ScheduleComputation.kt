package com.gdutday.data.repository

import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.common.CourseColors
import com.gdutday.core.common.ScheduleGridBuilder
import com.gdutday.core.common.TermCalendar
import com.gdutday.core.database.CourseColorEntity
import com.gdutday.core.database.Mappers
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.Exam
import com.gdutday.core.model.ScheduleSnapshot
import com.gdutday.core.model.ScheduleSource
import com.gdutday.core.model.Term
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// ============================================================================
// 课表页的纯计算。
//
// 全部是无副作用、可离线单测的函数：输入领域对象与原始行，输出 ScheduleUiState。
// Repository 只负责把 Room 的 Flow 拼成这些输入（见 ScheduleUiStateBuilder.kt），
// 把"怎么算"和"从哪读"彻底分开。
//
// 这样做还有一个直接好处：Widget 进程不依赖 Repository 实例，也能用同样的输入
// 直接算出"今天有什么课"。旧小程序把这段逻辑散落在 .vue 模板里，是本项目要避免的。
// ============================================================================

/**
 * [buildScheduleUiState] 的输入。用 data class 收口，避免函数签名膨胀到十个参数。
 *
 * @property meta 当前**显示**学期的 `term_meta` 行。
 * @property allMeta 全部学期元信息（学期切换下拉框 + 判断教务当前学期用）。
 * @property courses 显示学期的全部课程（已从 Entity 转成领域模型、含自定义）。
 * @property exams 显示学期的全部考试。
 * @property selectedWeek 用户点选的周次；null 表示从未点过，应跟随"今天"。
 */
internal data class ScheduleInputs(
    val meta: TermMetaEntity?,
    val allMeta: List<TermMetaEntity>,
    val courses: List<Course>,
    val exams: List<Exam>,
    val settings: UserSettings,
    val syncState: SyncStateEntity?,
    val isSyncing: Boolean,
    val selectedWeek: Int?,
    val colorKeys: Map<String, String>,
    val now: LocalDateTime = LocalDateTime.now(),
)

/**
 * 把原始输入拼成 UI 直接消费的 [ScheduleUiState]。
 *
 * 之所以把全部派生字段（grid / todayBlocks / colorAssignment / calendar）都在这里算好，
 * 而不是丢给 ViewModel：见 [ScheduleUiState] 的 KDoc。这里的每一条计算都对应
 * 旧小程序模板里的一段逻辑，集中之后"课表显示不对"只需要查一个文件。
 */
internal fun buildScheduleUiState(input: ScheduleInputs): ScheduleUiState {
    val term = input.meta?.let { Term.parse(it.termCode) }
    val availableTerms = input.allMeta.mapNotNull { Term.parse(it.termCode) }.sortedDescending()
    val currentTerm = input.allMeta.firstOrNull { it.isCurrent }?.let { Term.parse(it.termCode) }

    // 显示范围由数据推导，保底 20 周（与旧版一致），否则第 21 周的课会消失。
    val totalWeeks = maxOf(
        ScheduleSnapshot.MIN_DISPLAYED_WEEKS,
        input.courses.flatMap { it.weeks }.maxOrNull() ?: 0,
    )

    // 日期字段可能是历史版本写入的脏数据，解析失败时按"无日历"处理而不是崩溃。
    val startDate: LocalDate? = input.meta?.let { Mappers.parseDateLenient(it.semesterStart) }
    val source = input.meta?.let { SemesterStartSource.fromName(it.startSource) } ?: SemesterStartSource.GUESSED
    val calendar = startDate?.let { TermCalendar(it, totalWeeks) }

    val timetable = resolveTimetable(input.settings, input.settings.campus)
    val colorAssignment = CourseColors.assign(input.courses.map { it.name }, input.colorKeys)

    val today = input.now.toLocalDate()
    // todayWeek 不钳制：开学前为 0 甚至负数、结课后可能超过 totalWeeks，
    // UI 需要这个原始值来判断"还没开学 / 已经放假"。
    val todayWeekRaw = calendar?.weekOf(today) ?: 1
    val selectedWeek = (input.selectedWeek ?: todayWeekRaw).coerceIn(1, totalWeeks)

    val builder = if (calendar != null) {
        ScheduleGridBuilder(timetable, calendar, colorAssignment)
    } else {
        null
    }
    val grid = builder?.buildWeek(input.courses, input.exams, selectedWeek, input.now)
    // todayBlocks 始终按"真实今天"算，而不是选中周 —— 首页卡片与 Widget 要的是今天，
    // 与用户在周视图里翻到第 12 周无关。
    val todayBlocks = builder?.buildDay(input.courses, input.exams, today, input.now)?.blocks.orEmpty()

    val syncInfo = input.syncState?.let { state ->
        SyncInfo(
            at = state.lastSyncAt,
            term = Term.parse(state.lastTermCode),
            source = scheduleSourceFromName(state.lastScheduleSource),
            success = state.lastSuccess,
            error = state.lastError,
            courseCount = input.courses.size,
            examCount = input.exams.size,
            warnings = Mappers.decodeStrings(state.lastWarnings),
        )
    }

    return ScheduleUiState(
        isSyncing = input.isSyncing,
        // 注意：这里**不能**包含 `!isSyncing`。UI 的首屏 loading 分支是
        // `isInitialLoad && grid == null && isSyncing`，若 isInitialLoad 在同步时恒为 false，
        // 该分支永远不可达，首启（无数据 + 正在同步）会错误地显示"无数据"。
        isInitialLoad = input.courses.isEmpty() && availableTerms.isEmpty(),
        term = term,
        availableTerms = availableTerms,
        currentTerm = currentTerm,
        calendar = calendar,
        semesterStartSource = source,
        todayWeek = todayWeekRaw,
        selectedWeek = selectedWeek,
        totalWeeks = totalWeeks,
        timetable = timetable,
        campus = input.settings.campus,
        grid = grid,
        todayBlocks = todayBlocks,
        colorAssignment = colorAssignment,
        courses = input.courses,
        exams = input.exams,
        courseNames = input.courses.map { it.name }.distinct().sorted(),
        lastSync = syncInfo,
        errorMessage = input.syncState?.takeIf { !it.lastSuccess }?.lastError?.ifBlank { null },
        warnings = input.syncState?.let { Mappers.decodeStrings(it.lastWarnings) }.orEmpty(),
    )
}

/**
 * 取生效的作息表。
 *
 * 自定义作息解析失败时**静默回退**到内置表，绝不因为一处输错让课表页崩掉 ——
 * 这是 `CampusTimetable.parseCustom` 的设计意图，这里只是把回退链接上。
 */
internal fun resolveTimetable(settings: UserSettings, campus: Campus): CampusTimetable {
    if (settings.customTimetableEnabled) {
        CampusTimetable.parseCustom(campus, settings.customTimetable)?.let { return it }
    }
    return CampusTimetable.of(campus)
}

/** 把数据库里存的接口名还原成枚举。旧版本写入的未知值一律当 UNKNOWN，不抛异常。 */
internal fun scheduleSourceFromName(raw: String?): ScheduleSource =
    ScheduleSource.entries.firstOrNull { it.name == raw } ?: ScheduleSource.UNKNOWN

/**
 * 找出与 [candidate] 冲突的已有课程。
 *
 * 冲突的三个条件（缺一不可）：
 * 1. 同一天；
 * 2. 节次区间有交集 —— 用 `[start, end]` 闭区间判断，`end` 是含的；
 * 3. 周次集合有交集。
 *
 * 第 3 条最容易写错：只有周次不重叠的两门课是**合法的**（单双周选课），
 * 旧小程序在这一点上判对了，这里保留同样的语义。
 *
 * @param ignoreId 编辑场景下要排除自己的 id；新增传 0。
 */
internal fun findScheduleConflicts(
    existing: List<Course>,
    candidate: Course,
    ignoreId: Long = candidate.id,
): List<Course> = existing.filter { other ->
    other.id != ignoreId &&
        other.dayOfWeek == candidate.dayOfWeek &&
        sectionRangesOverlap(other.startSection, other.endSection, candidate.startSection, candidate.endSection) &&
        other.weeks.intersect(candidate.weeks).isNotEmpty()
}

/** 两个闭区间 `[aStart, aEnd]` 与 `[bStart, bEnd]` 是否相交。 */
internal fun sectionRangesOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
    maxOf(aStart, bStart) <= minOf(aEnd, bEnd)

/**
 * 计算"下一节课"。
 *
 * 从本周开始向后扫描最多三周，取第一个有未结束色块的周，并在该周内取开始最早的那个。
 * 扫描三周是为了覆盖"本周已无课、下周一有课"和"下周整周没课"两种情况；
 * 仍然没有就返回 null（UI 显示空状态）。扫描用的 [ScheduleGridBuilder.buildWeek]
 * 会自动处理冲突分列，所以 Widget 拿到的 `columnCount` 与课表页完全一致。
 *
 * @param now 参照时刻，测试时可固定。
 */
internal fun computeNextClass(state: ScheduleUiState, now: LocalDateTime): NextClass? {
    val calendar = state.calendar ?: return null
    val timetable = state.timetable ?: return null
    val builder = ScheduleGridBuilder(timetable, calendar, state.colorAssignment)

    val today = now.toLocalDate()
    // 开学前 weekOf 可能 ≤ 0；从第 1 周开始找，避免负周次导致 buildWeek 生成错误日期。
    val startWeek = maxOf(1, calendar.weekOf(today))
    for (week in startWeek until startWeek + 3) {
        var best: NextClass? = null
        var bestStart: LocalDateTime? = null
        for (day in builder.buildWeek(state.courses, state.exams, week, now).days) {
            for (block in day.blocks) {
                val start = day.date.atStartOfDay().plusMinutes(block.startMinute.toLong())
                val end = day.date.atStartOfDay().plusMinutes(block.endMinute.toLong())
                if (!end.isAfter(now)) continue
                val ongoing = !now.isBefore(start) && now.isBefore(end)
                val candidate = NextClass(
                    block = block,
                    date = day.date,
                    week = week,
                    startsInMinutes = ChronoUnit.MINUTES.between(now, start),
                    isOngoing = ongoing,
                    minutesRemaining = if (ongoing) ChronoUnit.MINUTES.between(now, end) else 0L,
                )
                if (bestStart == null || start.isBefore(bestStart)) {
                    best = candidate
                    bestStart = start
                }
            }
        }
        if (best != null) return best
    }
    return null
}

/**
 * 配色持久化策略。
 *
 * ## 为什么单独抽出来
 *
 * 自动配色是"课程名排序后按位次分配"。如果不落盘，新增一门排序靠前的课会让
 * 它后面所有课顺移，用户课表莫名其妙变色（旧实现的经典 bug，见 [CourseColors]）。
 *
 * 策略：
 * - 已持久化的课程名（无论自动还是用户手选）**原样保留**；
 * - 只有**全新的**课程名才参与分配，并作为 `isUserChosen = false` 落盘；
 * - 用户手选的条目永远不会被自动流程改写。
 *
 * 返回的 `assignment` 是完整的 `课程名 → 颜色 key`，直接用于 [ScheduleUiState.colorAssignment]。
 */
internal object CourseColorPolicy {

    internal data class Plan(
        /** 完整分配结果，含已存在的条目。 */
        val assignment: Map<String, String>,
        /** 仅本次需要新增落盘的行；已存在的一律不重复写。 */
        val toPersist: List<CourseColorEntity>,
    )

    fun plan(courseNames: Collection<String>, existing: List<CourseColorEntity>): Plan {
        val existingMap = existing.associate { it.courseName to it.colorKey }
        val assignment = CourseColors.assign(courseNames, existingMap).mapValues { it.value.key }
        val known = existing.map { it.courseName }.toSet()
        val toPersist = assignment
            .filterKeys { it !in known }
            .map { (name, key) -> CourseColorEntity(courseName = name, colorKey = key, isUserChosen = false) }
        return Plan(assignment, toPersist)
    }
}
