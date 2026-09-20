package com.gdutday.feature.schedule

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.Term
import com.gdutday.data.repository.ScheduleRepository
import com.gdutday.data.repository.ScheduleUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * [AdvancedCourseEditViewModel] 的行为测试：
 * - 按名字聚合出同一门课在所有天/节次的行，不相关的同名以外课程不会混进来；
 * - 整门课改名对每一行分流到对应的仓库方法（SCHOOL → 补丁，其它 → 直接更新）；
 * - 逐行编辑会把 id/学期/来源/名字强制回填成数据库里那一行原有的值；
 * - 模板批量套用只覆盖开着开关的字段，关着的字段每行保留自己原来的值；
 * - 新增上课时间支持一次选多个星期批量建行；
 * - 删除/还原原样转发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedCourseEditViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val term = Term(2025, 1)

    private class FakeScheduleRepository(initialState: ScheduleUiState) : ScheduleRepository {
        val stateFlow = MutableStateFlow(initialState)

        val savedOverrides = mutableListOf<Triple<Course, Course, OverrideScope>>()
        val updatedCustoms = mutableListOf<Course>()
        val addedCustoms = mutableListOf<Course>()
        val deletedIds = mutableListOf<Long>()
        val restoredIds = mutableListOf<Long>()

        override fun observeScheduleUiState(): Flow<ScheduleUiState> = stateFlow
        override fun observeNextClass(): Flow<com.gdutday.data.repository.NextClass?> = emptyFlow()
        override suspend fun sync(term: Term?) = throw UnsupportedOperationException()
        override suspend fun selectTerm(term: Term) {}
        override suspend fun selectWeek(week: Int) {}

        override suspend fun addCustomCourse(course: Course, force: Boolean): List<Course> {
            addedCustoms += course
            return emptyList()
        }

        override suspend fun updateCustomCourse(course: Course, force: Boolean): List<Course> {
            updatedCustoms += course
            return emptyList()
        }

        override suspend fun saveSchoolOverride(
            original: Course,
            editedFields: Course,
            scope: OverrideScope,
            force: Boolean,
        ): List<Course> {
            savedOverrides += Triple(original, editedFields, scope)
            return emptyList()
        }

        override suspend fun deleteCourse(id: Long) {
            deletedIds += id
        }

        override suspend fun deleteAllCustomCourses() {}

        override suspend fun restoreOriginal(overrideId: Long) {
            restoredIds += overrideId
        }

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

    private fun schoolCourse(id: Long, day: Int, startSection: Int, name: String = "大学物理(2)") = Course(
        id = id, term = term, name = name, dayOfWeek = day,
        startSection = startSection, sectionCount = 2, weeks = (1..16).toSet(),
        source = CourseSource.SCHOOL,
    )

    private fun customCourse(id: Long, day: Int, name: String = "吃饭课") = Course(
        id = id, term = term, name = name, dayOfWeek = day,
        startSection = 11, sectionCount = 2, weeks = (1..16).toSet(),
        source = CourseSource.CUSTOM,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `按名字聚合出这门课在所有天节次的行 不混入同名以外的课`() = runTest(dispatcher) {
        val monday = schoolCourse(id = 1L, day = 1, startSection = 3)
        val wednesday = schoolCourse(id = 2L, day = 3, startSection = 6)
        val unrelated = schoolCourse(id = 3L, day = 1, startSection = 3, name = "线性代数")
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(wednesday, monday, unrelated)))

        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        // Eagerly 共享，但 stateIn 内部靠协程搬运初始值——测试用的 StandardTestDispatcher
        // 不会同步跑这个协程，读 .value 之前仍要先把队列排空。
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.occurrences.value.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `整门课改名对每一行分流到对应的仓库方法`() = runTest(dispatcher) {
        val school = schoolCourse(id = 1L, day = 1, startSection = 3)
        val override = schoolCourse(id = 2L, day = 3, startSection = 6).copy(source = CourseSource.OVERRIDE)
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(school, override)))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        vm.renameAll("水课")
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.savedOverrides).hasSize(1)
        assertThat(repo.savedOverrides.single().second.name).isEqualTo("水课")
        assertThat(repo.savedOverrides.single().third).isEqualTo(OverrideScope.WEEK_RANGE)
        assertThat(repo.updatedCustoms).hasSize(1)
        assertThat(repo.updatedCustoms.single().name).isEqualTo("水课")
        assertThat(vm.courseName.value).isEqualTo("水课")
    }

    @Test
    fun `改名为空白或和现在一样时不发起任何调用`() = runTest(dispatcher) {
        val school = schoolCourse(id = 1L, day = 1, startSection = 3)
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(school)))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        vm.renameAll("   ")
        vm.renameAll("大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.savedOverrides).isEmpty()
        assertThat(repo.updatedCustoms).isEmpty()
    }

    @Test
    fun `逐行编辑教务行落补丁 id学期来源名字恒回填`() = runTest(dispatcher) {
        val school = schoolCourse(id = 1L, day = 1, startSection = 3)
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(school)))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        // 故意乱填 id/name/source——不管 UI 传了什么，这几个身份字段都必须以本地这一行为准。
        val tampered = school.copy(
            id = 999L, name = "睡觉课", source = CourseSource.CUSTOM,
            classroom = "教5-301", weeks = setOf(2),
        )
        vm.editOccurrence(1L, tampered)
        dispatcher.scheduler.advanceUntilIdle()

        val call = repo.savedOverrides.single()
        assertThat(call.first.id).isEqualTo(1L)
        assertThat(call.second.id).isEqualTo(1L)
        assertThat(call.second.term).isEqualTo(term)
        assertThat(call.second.name).isEqualTo("大学物理(2)")
        assertThat(call.second.classroom).isEqualTo("教5-301")
        assertThat(call.second.weeks).containsExactly(2)
        assertThat(call.third).isEqualTo(OverrideScope.WEEK_RANGE)
        assertThat(repo.updatedCustoms).isEmpty()
    }

    @Test
    fun `逐行编辑自定义或补丁行直接更新不落补丁`() = runTest(dispatcher) {
        val custom = customCourse(id = 5L, day = 2)
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(custom)))
        val vm = AdvancedCourseEditViewModel(repo, "吃饭课")
        dispatcher.scheduler.advanceUntilIdle()

        vm.editOccurrence(5L, custom.copy(classroom = "饭堂二楼"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.updatedCustoms.single().classroom).isEqualTo("饭堂二楼")
        assertThat(repo.savedOverrides).isEmpty()
    }

    @Test
    fun `找不到对应行时逐行编辑是无操作`() = runTest(dispatcher) {
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = emptyList()))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        vm.editOccurrence(404L, schoolCourse(id = 404L, day = 1, startSection = 1))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.savedOverrides).isEmpty()
        assertThat(repo.updatedCustoms).isEmpty()
    }

    @Test
    fun `新增上课时间按多选的星期各建一行custom`() = runTest(dispatcher) {
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = emptyList()))
        val vm = AdvancedCourseEditViewModel(repo, "吃饭课")
        dispatcher.scheduler.advanceUntilIdle()

        vm.addOccurrences(
            days = setOf(1, 2), startSection = 11, sectionCount = 2,
            weeks = (1..16).toSet(), classroom = "饭堂", teacher = "", colorKey = "red",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.addedCustoms).hasSize(2)
        assertThat(repo.addedCustoms.map { it.dayOfWeek }).containsExactly(1, 2)
        assertThat(repo.addedCustoms.all { it.name == "吃饭课" && it.source == CourseSource.CUSTOM }).isTrue()
    }

    @Test
    fun `新增上课时间不选星期或不选周次时不发起调用`() = runTest(dispatcher) {
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = emptyList()))
        val vm = AdvancedCourseEditViewModel(repo, "吃饭课")
        dispatcher.scheduler.advanceUntilIdle()

        vm.addOccurrences(
            days = emptySet(), startSection = 11, sectionCount = 2,
            weeks = (1..16).toSet(), classroom = "饭堂", teacher = "", colorKey = "red",
        )
        vm.addOccurrences(
            days = setOf(1), startSection = 11, sectionCount = 2,
            weeks = emptySet(), classroom = "饭堂", teacher = "", colorKey = "red",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.addedCustoms).isEmpty()
    }

    @Test
    fun `模板只覆盖开着开关的字段 关着的字段每行保留自己原来的值`() = runTest(dispatcher) {
        // 两行老师不一样，模拟"这门课每次课老师都不一样"的场景。
        val monday = schoolCourse(id = 1L, day = 1, startSection = 3).copy(teacher = "张三")
        val wednesday = schoolCourse(id = 2L, day = 3, startSection = 6).copy(teacher = "李四")
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(monday, wednesday)))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelected(1L)
        vm.toggleSelected(2L)
        // 模板只勾了"上课时间"，教师字段的开关没打开——按用户反馈的场景，
        // 这里必须不覆盖老师，两行的老师应该还是各自原来的值。
        vm.applyTemplate(OccurrenceTemplate(useRealTime = false, startSection = 5, sectionCount = 2))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.savedOverrides).hasSize(2)
        val byId = repo.savedOverrides.associateBy { it.first.id }
        assertThat(byId.getValue(1L).second.teacher).isEqualTo("张三")
        assertThat(byId.getValue(1L).second.startSection).isEqualTo(5)
        assertThat(byId.getValue(2L).second.teacher).isEqualTo("李四")
        assertThat(byId.getValue(2L).second.startSection).isEqualTo(5)
    }

    @Test
    fun `模板套用后清空选中集合`() = runTest(dispatcher) {
        val monday = schoolCourse(id = 1L, day = 1, startSection = 3)
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(monday)))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelected(1L)
        vm.applyTemplate(OccurrenceTemplate(classroom = "教5-301"))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.selectedIds.value).isEmpty()
    }

    @Test
    fun `没有勾选任何行或模板全空时套用是无操作`() = runTest(dispatcher) {
        val monday = schoolCourse(id = 1L, day = 1, startSection = 3)
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(monday)))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        // 没勾选任何行。
        vm.applyTemplate(OccurrenceTemplate(classroom = "教5-301"))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(repo.savedOverrides).isEmpty()

        // 勾选了但模板全空（没有任何开关打开）。
        vm.toggleSelected(1L)
        vm.applyTemplate(OccurrenceTemplate())
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(repo.savedOverrides).isEmpty()
    }

    @Test
    fun `全选和取消全选`() = runTest(dispatcher) {
        val monday = schoolCourse(id = 1L, day = 1, startSection = 3)
        val wednesday = schoolCourse(id = 2L, day = 3, startSection = 6)
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = listOf(monday, wednesday)))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        vm.selectAll()
        assertThat(vm.selectedIds.value).containsExactly(1L, 2L)

        vm.clearSelection()
        assertThat(vm.selectedIds.value).isEmpty()
    }

    @Test
    fun `删除和还原原样转发到仓库对应方法`() = runTest(dispatcher) {
        val repo = FakeScheduleRepository(ScheduleUiState(term = term, courses = emptyList()))
        val vm = AdvancedCourseEditViewModel(repo, "大学物理(2)")
        dispatcher.scheduler.advanceUntilIdle()

        vm.delete(7L)
        vm.restore(8L)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.deletedIds).containsExactly(7L)
        assertThat(repo.restoredIds).containsExactly(8L)
    }
}
