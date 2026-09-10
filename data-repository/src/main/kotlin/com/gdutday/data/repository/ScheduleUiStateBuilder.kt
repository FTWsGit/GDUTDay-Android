package com.gdutday.data.repository

import com.gdutday.core.database.CourseEntity
import com.gdutday.core.database.ExamEntity
import com.gdutday.core.database.Mappers
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.datastore.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDateTime

// ============================================================================
// `observeScheduleUiState()` 的 Flow 装配。
//
// 拆成独立的 ScheduleSources + 纯函数 buildScheduleUiStateFlow，是为了让
// "五个数据源如何 combine、学期如何选择、什么时候 distinct" 这段最容易出错、
// 又最难在真机上验证的逻辑，可以用 flowOf(...) 在 JVM 测试里直接跑。
// Repository 实现只需要把 Room 的 DAO 包装成 ScheduleSources。
//
// ## Kotlin 的 combine 最多 5 个参数
//
// 最终状态需要「学期元信息 + 设置 + 课程 + 考试 + 同步状态 + 同步中标志 + 选中周 + 配色」
// 共 8 个来源，超过上限，所以按依赖关系分两层：
//   ① termSelection = combine(全部学期元信息, 设置)        —— 决定"显示哪个学期"
//   ② termData      = termSelection.flatMapLatest { 课程, 考试 }
//   ③ base          = combine(termData, 同步状态, 同步中, 选中周)   —— 4 个，刚好
//   ④ ui            = combine(base, 配色)                  —— 2 个
// 嵌套而不是"先胡乱合成一个 map"，是因为中间层有真实的语义边界（学期选择、
// 学期数据、同步状态），中间类型带名字比带泛型的三元组好排查得多。
// ============================================================================

/**
 * `observeScheduleUiState()` 需要的全部数据源。
 *
 * 之所以用「函数 `(termCode) -> Flow`」而不是直接把 DAO 传进来，是因为课程与考试
 * 是**按学期查询**的，学期本身又是上游 Flow 算出来的，只能延迟到拿到 termCode 时再订阅。
 */
internal class ScheduleSources(
    val allTermMeta: Flow<List<TermMetaEntity>>,
    val settings: Flow<UserSettings>,
    val syncState: Flow<SyncStateEntity?>,
    val isSyncing: Flow<Boolean>,
    val selectedWeek: StateFlow<Int?>,
    val colors: Flow<Map<String, String>>,
    val coursesFor: (String) -> Flow<List<CourseEntity>>,
    val examsFor: (String) -> Flow<List<ExamEntity>>,
)

/** 学期选择的中间结果。 */
private data class TermSelection(
    val allMeta: List<TermMetaEntity>,
    val displayed: TermMetaEntity?,
    val settings: UserSettings,
)

/** 某学期的课程与考试。 */
private data class TermData(
    val selection: TermSelection,
    val courses: List<CourseEntity>,
    val exams: List<ExamEntity>,
)

/** 叠加同步状态后的输入。 */
private data class BaseInputs(
    val data: TermData,
    val syncState: SyncStateEntity?,
    val isSyncing: Boolean,
    val selectedWeek: Int?,
)

/**
 * 组装课表页状态流。
 *
 * 学期选择规则：**设置里锁定的学期 > 教务系统标记的当前学期 > 元信息中最新的学期**。
 * 之所以"设置优先"，是因为它承载了用户主动切换的选择（见 `selectTerm`），
 * 而 `is_current` 每次同步都会被服务端的最新值覆盖，不能承载用户意图。
 *
 * @param now 供测试注入固定时刻。生产环境用系统时钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun buildScheduleUiStateFlow(
    sources: ScheduleSources,
    now: () -> LocalDateTime = { LocalDateTime.now() },
): Flow<ScheduleUiState> {
    val termSelection: Flow<TermSelection> = combine(
        sources.allTermMeta,
        sources.settings,
    ) { metas, settings ->
        val locked = settings.selectedTerm?.shortCode
        val displayed = metas.firstOrNull { it.termCode == locked }
            ?: metas.firstOrNull { it.isCurrent }
            ?: metas.maxByOrNull { it.termCode }
        TermSelection(metas, displayed, settings)
    }.distinctUntilChanged()

    val termData: Flow<TermData> = termSelection.flatMapLatest { selection ->
        val code = selection.displayed?.termCode
        if (code == null) {
            flowOf(TermData(selection, emptyList(), emptyList()))
        } else {
            combine(sources.coursesFor(code), sources.examsFor(code)) { courses, exams ->
                TermData(selection, courses, exams)
            }
        }
    }

    val base: Flow<BaseInputs> = combine(
        termData,
        sources.syncState,
        sources.isSyncing,
        sources.selectedWeek,
    ) { data, syncState, isSyncing, week ->
        BaseInputs(data, syncState, isSyncing, week)
    }

    return combine(base, sources.colors) { b, colors ->
        val courses = with(Mappers) { b.data.courses.mapNotNull { it.toDomain() } }
        val exams = with(Mappers) { b.data.exams.mapNotNull { it.toDomain() } }
        buildScheduleUiState(
            ScheduleInputs(
                meta = b.data.selection.displayed,
                allMeta = b.data.selection.allMeta,
                courses = courses,
                exams = exams,
                settings = b.data.selection.settings,
                syncState = b.syncState,
                isSyncing = b.isSyncing,
                selectedWeek = b.selectedWeek,
                colorKeys = colors,
                now = now(),
            ),
        )
    }.distinctUntilChanged()
}
