package com.gdutday.data.repository

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.database.CourseEntity
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Term
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * 验证 `observeScheduleUiState()` 的 Flow 装配：学期如何选、课程按学期切换、
 * 选中周如何透传、配色如何并入。
 *
 * 用 `flowOf` + `MutableStateFlow` 替掉 Room DAO，跑在 `runTest` 里，
 * 不需要 Robolectric，也不需要 Android 环境。
 */
class ScheduleUiStateBuilderTest {

    private val meta20251 = TermMetaEntity(
        termCode = "20251",
        xnxqdm = "202501",
        displayName = "2025-2026学年第一学期",
        isCurrent = true,
        semesterStart = "2025-09-01",
        startSource = SemesterStartSource.DERIVED.name,
    )
    private val meta20242 = TermMetaEntity(
        termCode = "20242",
        xnxqdm = "202402",
        displayName = "2024-2025学年第二学期",
        isCurrent = false,
        semesterStart = "2025-02-24",
        startSource = SemesterStartSource.KNOWN_TABLE.name,
    )

    private fun entity(
        termCode: String,
        name: String,
        weeks: String = "1,2,3",
    ): CourseEntity = CourseEntity(
        termCode = termCode,
        name = name,
        dayOfWeek = 1,
        startSection = 1,
        sectionCount = 2,
        weeks = weeks,
    )

    private fun sources(
        allMeta: Flow<List<TermMetaEntity>> = flowOf(listOf(meta20251, meta20242)),
        settings: Flow<UserSettings> = flowOf(UserSettings(campus = Campus.UNIVERSITY_CITY)),
        selectedWeek: MutableStateFlow<Int?> = MutableStateFlow(null),
        colors: Flow<Map<String, String>> = flowOf(emptyMap()),
    ): ScheduleSources = ScheduleSources(
        allTermMeta = allMeta,
        settings = settings,
        syncState = flowOf(
            SyncStateEntity(lastSyncAt = Instant.parse("2025-09-10T10:00:00Z"), lastTermCode = "20251", lastSuccess = true),
        ),
        isSyncing = flowOf(false),
        selectedWeek = selectedWeek,
        colors = colors,
        coursesFor = { code ->
            flowOf(
                when (code) {
                    "20251" -> listOf(entity("20251", "高等数学"))
                    "20242" -> listOf(entity("20242", "大学物理"))
                    else -> emptyList()
                },
            )
        },
        examsFor = { flowOf(emptyList()) },
    )

    private val fixedNow: () -> LocalDateTime = { LocalDateTime.of(2025, 9, 1, 8, 0) }

    @Test
    fun `默认选中教务当前学期并跟随今天所在周`() = runTest {
        val state = buildScheduleUiStateFlow(sources(), fixedNow).first()

        assertThat(state.term).isEqualTo(Term(2025, 1))
        assertThat(state.currentTerm).isEqualTo(Term(2025, 1))
        assertThat(state.courses.map { it.name }).containsExactly("高等数学")
        assertThat(state.selectedWeek).isEqualTo(1)
        assertThat(state.lastSync?.term).isEqualTo(Term(2025, 1))
    }

    @Test
    fun `设置里锁定的学期优先于教务当前学期`() = runTest {
        val settings = flowOf(
            UserSettings(campus = Campus.UNIVERSITY_CITY, selectedTerm = Term(2024, 2)),
        )
        val state = buildScheduleUiStateFlow(sources(settings = settings), fixedNow).first()

        assertThat(state.term).isEqualTo(Term(2024, 2))
        assertThat(state.courses.map { it.name }).containsExactly("大学物理")
        // 教务标记的当前学期不受用户选择影响
        assertThat(state.currentTerm).isEqualTo(Term(2025, 1))
    }

    @Test
    fun `内存中的选中周次直接透传到状态`() = runTest {
        val state = buildScheduleUiStateFlow(
            sources(selectedWeek = MutableStateFlow(3)),
            fixedNow,
        ).first()

        assertThat(state.selectedWeek).isEqualTo(3)
    }

    @Test
    fun `持久化的配色并入 colorAssignment`() = runTest {
        val state = buildScheduleUiStateFlow(
            sources(colors = flowOf(mapOf("高等数学" to "red"))),
            fixedNow,
        ).first()

        assertThat(state.colorAssignment["高等数学"]?.key).isEqualTo("red")
    }

    @Test
    fun `相邻周网格与选中周同源生成`() = runTest {
        val state = buildScheduleUiStateFlow(
            sources(selectedWeek = MutableStateFlow(2)),
            fixedNow,
        ).first()

        // 课程在第 1、2、3 周开课：前后两周都应有网格，且周次正确
        assertThat(state.prevWeekGrid?.week).isEqualTo(1)
        assertThat(state.nextWeekGrid?.week).isEqualTo(3)
        // 同一套 builder/配色，几何输入一致
        assertThat(state.nextWeekGrid!!.days).hasSize(7)
    }

    @Test
    fun `首周的前一周为null末周的后一周为null`() = runTest {
        val first = buildScheduleUiStateFlow(
            sources(selectedWeek = MutableStateFlow(1)),
            fixedNow,
        ).first()
        assertThat(first.prevWeekGrid).isNull()
        assertThat(first.nextWeekGrid?.week).isEqualTo(2)

        val last = buildScheduleUiStateFlow(
            sources(selectedWeek = MutableStateFlow(first.totalWeeks)),
            fixedNow,
        ).first()
        assertThat(last.nextWeekGrid).isNull()
        assertThat(last.prevWeekGrid?.week).isEqualTo(first.totalWeeks - 1)
    }

    @Test
    fun `无学期历时网格与相邻周网格为null`() = runTest {
        val state = buildScheduleUiStateFlow(
            sources(allMeta = flowOf(emptyList())),
            fixedNow,
        ).first()

        // 无学期元信息：学期历无法构建，grid 与相邻周均为 null
        assertThat(state.grid).isNull()
        assertThat(state.prevWeekGrid).isNull()
        assertThat(state.nextWeekGrid).isNull()
    }
}
