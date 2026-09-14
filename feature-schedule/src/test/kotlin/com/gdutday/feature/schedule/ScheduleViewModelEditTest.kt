package com.gdutday.feature.schedule

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.Term
import com.gdutday.data.repository.ScheduleRepository
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
 * [ScheduleViewModel] 编辑事件的分流测试。
 *
 * 只验一件事：编辑保存按课程来源走对路径 ——
 * 教务课程 → `saveSchoolOverride`（生成补丁）；其它 → `updateCustomCourse`（直接更新）。
 * Repository 是假实现，只记录调用，不需要 Robolectric。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelEditTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeScheduleRepository : ScheduleRepository {
        override fun observeScheduleUiState(): Flow<com.gdutday.data.repository.ScheduleUiState> = flowOf()
        override fun observeNextClass(): Flow<com.gdutday.data.repository.NextClass?> = flowOf()
        override suspend fun sync(term: Term?) = throw UnsupportedOperationException()
        override suspend fun selectTerm(term: Term) {}
        override suspend fun selectWeek(week: Int) {}

        var savedOverride: Triple<Course, Course, OverrideScope>? = null
        var updatedCustom: Course? = null

        override suspend fun addCustomCourse(course: Course, force: Boolean): List<Course> = emptyList()

        override suspend fun updateCustomCourse(course: Course): List<Course> {
            updatedCustom = course
            return emptyList()
        }

        override suspend fun saveSchoolOverride(
            original: Course,
            editedFields: Course,
            scope: OverrideScope,
        ): List<Course> {
            savedOverride = Triple(original, editedFields, scope)
            return emptyList()
        }

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

    private val term = Term(2025, 1)

    private fun schoolCourse() = Course(
        id = 1L, term = term, name = "高等数学", dayOfWeek = 1,
        startSection = 1, sectionCount = 2, weeks = (1..16).toSet(),
        source = CourseSource.SCHOOL,
    )

    private fun customCourse() = Course(
        id = 2L, term = term, name = "社团活动", dayOfWeek = 6,
        startSection = 10, sectionCount = 2, weeks = setOf(1),
        source = CourseSource.CUSTOM,
    )

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
    fun `编辑教务课程生成补丁调用`() = runTest(dispatcher) {
        val vm = viewModel()
        val original = schoolCourse()
        vm.openEdit(original)
        assertThat(vm.editingCourse.value).isEqualTo(original)

        val edited = original.copy(classroom = "教5-301", weeks = setOf(3))
        vm.saveEdit(edited, OverrideScope.THIS_WEEK)
        dispatcher.scheduler.advanceUntilIdle()

        val call = repository.savedOverride
        assertThat(call).isNotNull()
        assertThat(call!!.first.id).isEqualTo(1L)
        assertThat(call.second.classroom).isEqualTo("教5-301")
        assertThat(call.third).isEqualTo(OverrideScope.THIS_WEEK)
        // 保存后编辑面板关闭
        assertThat(vm.editingCourse.value).isNull()
        assertThat(repository.updatedCustom).isNull()
    }

    @Test
    fun `编辑自定义课程直接更新不生成补丁`() = runTest(dispatcher) {
        val vm = viewModel()
        val original = customCourse()
        vm.openEdit(original)

        val edited = original.copy(teacher = "王五")
        vm.saveEdit(edited, OverrideScope.ALL)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repository.updatedCustom).isEqualTo(edited)
        assertThat(repository.savedOverride).isNull()
        assertThat(vm.editingCourse.value).isNull()
    }

    @Test
    fun `关闭编辑面板清空状态`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openEdit(schoolCourse())
        assertThat(vm.editingCourse.value).isNotNull()

        vm.closeEdit()
        assertThat(vm.editingCourse.value).isNull()
    }

    @Test
    fun `没有打开编辑时保存是无操作`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.saveEdit(schoolCourse(), OverrideScope.ALL)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(repository.savedOverride).isNull()
        assertThat(repository.updatedCustom).isNull()
    }
}
