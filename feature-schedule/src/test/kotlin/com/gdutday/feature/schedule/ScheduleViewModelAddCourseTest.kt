package com.gdutday.feature.schedule

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.Term
import com.gdutday.data.repository.ScheduleRepository
import com.gdutday.data.repository.ScheduleUiState
import com.gdutday.data.repository.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * [ScheduleViewModel] 新增课程路径的测试。
 *
 * 新增 Sheet 保存时必须走 `addCustomCourse(force = true)`（plan-002 方案 A）：
 * 冲突不再拦截，由网格降级呈现。Repository 是假实现，只记录调用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelAddCourseTest {

    private val dispatcher = StandardTestDispatcher()

    private inner class FakeScheduleRepository : ScheduleRepository {
        override fun observeScheduleUiState(): Flow<ScheduleUiState> = flowOf(uiState)
        override fun observeNextClass(): Flow<com.gdutday.data.repository.NextClass?> = flowOf()
        override suspend fun sync(term: Term?) = throw UnsupportedOperationException()
        override suspend fun selectTerm(term: Term) {}
        override suspend fun selectWeek(week: Int) {}

        var addedCourse: Course? = null
        var addedForce: Boolean? = null

        override suspend fun addCustomCourse(course: Course, force: Boolean): List<Course> {
            addedCourse = course
            addedForce = force
            return emptyList()
        }

        override suspend fun updateCustomCourse(course: Course, force: Boolean): List<Course> = emptyList()
        override suspend fun saveSchoolOverride(
            original: Course,
            editedFields: Course,
            scope: OverrideScope,
            force: Boolean,
        ): List<Course> = emptyList()

        override suspend fun deleteCourse(id: Long) {}
        override suspend fun deleteAllCustomCourses() {}
        override suspend fun restoreOriginal(overrideId: Long) {}
        override fun observeCustomAndOverrideCourses(): Flow<Map<Term, List<Course>>> = emptyFlow()
        override suspend fun isOverrideEffective(override: Course): Boolean = true
        override suspend fun setCourseColor(courseName: String, colorKey: String) {}
        override suspend fun resetColors() {}
        override suspend fun setSemesterStart(term: Term, startDate: LocalDate) {}
        override suspend fun fetchClassCascade(
            guid: String,
            grade: String,
            collegeCode: String,
            majorCode: String,
        ): List<com.gdutday.data.repository.ClassCascadeOption> = emptyList()
        override suspend fun fetchClassCascadeMeta(): com.gdutday.data.repository.ClassCascadeMeta =
            throw UnsupportedOperationException()
    }

    private class FakeSettingsStore : SettingsStore {
        override val settings: Flow<UserSettings> = flowOf(UserSettings())
        override suspend fun update(transform: (UserSettings) -> UserSettings) {}
        override suspend fun reset() {}
    }

    private class FakeSyncScheduler : SyncScheduler {
        override fun schedulePeriodicSync() {}
        override fun cancelPeriodicSync() {}
        override fun requestImmediateSync(expedited: Boolean) {}
        override val isSyncing: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private lateinit var repository: FakeScheduleRepository

    /** Fake 默认没有学期。需要走保存路径的测试把它改成有学期的状态。 */
    private var uiState: ScheduleUiState = ScheduleUiState()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeScheduleRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ScheduleViewModel(repository, FakeSettingsStore(), FakeSyncScheduler())

    @Test
    fun `addCourse调用repository的force为true`() = runTest(dispatcher) {
        val term = Term(2025, 1)
        uiState = ScheduleUiState(term = term, selectedWeek = 3, totalWeeks = 20)
        val vm = viewModel()
        vm.showAddCourseSheet()
        vm.updateAddCourseForm { it.copy(name = "社团活动", dayOfWeek = 6) }
        vm.addCourse()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repository.addedCourse).isNotNull()
        assertThat(repository.addedCourse!!.name).isEqualTo("社团活动")
        assertThat(repository.addedCourse!!.term).isEqualTo(term)
        assertThat(repository.addedCourse!!.weeks).containsExactly(3)
        assertThat(repository.addedForce).isTrue()
        // 保存后 Sheet 关闭
        assertThat(vm.addCourseSheetVisible.value).isFalse()
    }

    @Test
    fun `保存的课程来源是CUSTOM且清空补丁字段`() = runTest(dispatcher) {
        uiState = ScheduleUiState(term = Term(2025, 1), selectedWeek = 2, totalWeeks = 20)
        val vm = viewModel()
        vm.showAddCourseSheet()
        vm.updateAddCourseForm { it.copy(name = " 晚自习 ") }
        vm.addCourse()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = repository.addedCourse!!
        assertThat(saved.source).isEqualTo(CourseSource.CUSTOM)
        assertThat(saved.name).isEqualTo("晚自习")
        assertThat(saved.id).isEqualTo(0L)
        assertThat(saved.overrideScope).isNull()
        assertThat(saved.overrideTargetNaturalKey).isNull()
        assertThat(saved.overrideWeeks).isEmpty()
    }

    @Test
    fun `weekMode转换正确`() {
        val base = AddCourseForm(name = "X")

        assertThat(base.copy(weekMode = WeekMode.CURRENT, singleWeek = 3).weeks(currentWeek = 5, totalWeeks = 20))
            .containsExactly(5)
        assertThat(base.copy(weekMode = WeekMode.SINGLE, singleWeek = 7).weeks(currentWeek = 5, totalWeeks = 20))
            .containsExactly(7)
        assertThat(base.copy(weekMode = WeekMode.RANGE, rangeStart = 3, rangeEnd = 5).weeks(currentWeek = 5, totalWeeks = 20))
            .containsExactly(3, 4, 5)
        assertThat(base.copy(weekMode = WeekMode.ALL).weeks(currentWeek = 5, totalWeeks = 20))
            .isEqualTo((1..20).toSet())
    }

    @Test
    fun `范围起止倒置时周次为空表单无效`() {
        val form = AddCourseForm(name = "X", weekMode = WeekMode.RANGE, rangeStart = 6, rangeEnd = 3)
        assertThat(form.weeks(currentWeek = 5, totalWeeks = 20)).isEmpty()
        assertThat(form.isValid(currentWeek = 5, totalWeeks = 20)).isFalse()
    }

    @Test
    fun `无学期时addCourse是无操作`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showAddCourseSheet()
        vm.updateAddCourseForm { it.copy(name = "X") }
        vm.addCourse()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repository.addedCourse).isNull()
    }

    @Test
    fun `打开新增面板会重置表单并展示`() {
        val vm = viewModel()
        vm.showAddCourseSheet()
        assertThat(vm.addCourseSheetVisible.value).isTrue()
        assertThat(vm.addCourseForm.value).isEqualTo(AddCourseForm())

        vm.updateAddCourseForm { it.copy(name = "临时") }
        vm.dismissAddCourseSheet()
        assertThat(vm.addCourseSheetVisible.value).isFalse()
    }

    @Test
    fun `按具体时间填写时写入绝对分钟`() {
        val term = Term(2025, 1)
        val course = AddCourseForm(
            name = "实验", useRealTime = true, startTime = "19:00", endTime = "20:30",
        ).toCourse(term, currentWeek = 3, totalWeeks = 20)

        assertThat(course.startMinute).isEqualTo(19 * 60)
        assertThat(course.endMinute).isEqualTo(20 * 60 + 30)
        assertThat(course.weeks).containsExactly(3)
    }
}
