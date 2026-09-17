package com.gdutday.data.repository

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.database.CourseColorDao
import com.gdutday.core.database.CourseColorEntity
import com.gdutday.core.database.CourseDao
import com.gdutday.core.database.CourseEntity
import com.gdutday.core.database.ExamDao
import com.gdutday.core.database.Mappers
import com.gdutday.core.database.SyncStateDao
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.database.TermMetaDao
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.datastore.SessionStore
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.GdutSession
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.Term
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [ScheduleRepositoryImpl.addCustomCourse] 的冲突语义测试。
 *
 * 新增课程 Sheet 始终 `force = true`（plan-002 方案 A）：冲突不再拦截插入，
 * 由网格的并排 / 堆叠降级呈现。这里用内存假 DAO 钉住这个行为。
 */
class AddCustomCourseConflictTest {

    private val term = Term(2025, 1)

    private class FakeCourseDao : CourseDao {
        val rows = mutableListOf<CourseEntity>()

        override fun observeByTerm(termCode: String): Flow<List<CourseEntity>> =
            flowOf(rows.filter { it.termCode == termCode })

        override fun observeByTermAndDay(termCode: String, dayOfWeek: Int): Flow<List<CourseEntity>> =
            flowOf(rows.filter { it.termCode == termCode && it.dayOfWeek == dayOfWeek })

        override suspend fun getByTerm(termCode: String): List<CourseEntity> =
            rows.filter { it.termCode == termCode }

        override suspend fun getById(id: Long): CourseEntity? = rows.firstOrNull { it.id == id }

        override suspend fun distinctCourseNames(): List<String> = rows.map { it.name }.distinct()

        override suspend fun countByTerm(termCode: String): Int =
            rows.count { it.termCode == termCode }

        override suspend fun insertAll(courses: List<CourseEntity>): List<Long> =
            courses.map { rows += it; it.id }

        override suspend fun upsert(course: CourseEntity): Long {
            rows.removeAll { it.id == course.id && course.id != 0L }
            val assigned = if (course.id == 0L) (rows.maxOfOrNull { it.id } ?: 0L) + 1 else course.id
            rows += course.copy(id = assigned)
            return assigned
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteAllCustom(): Int =
            rows.count { it.source == "CUSTOM" }.also { rows.removeAll { it.source == "CUSTOM" } }

        override suspend fun deleteByTerm(termCode: String) {
            rows.removeAll { it.termCode == termCode }
        }

        override suspend fun deleteSchoolCourses(termCode: String) {
            rows.removeAll { it.termCode == termCode && it.source == "SCHOOL" }
        }

        override suspend fun getOverrides(termCode: String): List<CourseEntity> =
            rows.filter { it.termCode == termCode && it.source == "OVERRIDE" }

        override suspend fun getAllOverrides(): List<CourseEntity> =
            rows.filter { it.source == "OVERRIDE" }

        override suspend fun getSchoolCourses(termCode: String): List<CourseEntity> =
            rows.filter { it.termCode == termCode && it.source == "SCHOOL" }

        override fun observeCustomAndOverride(): Flow<List<CourseEntity>> =
            flowOf(rows.filter { it.source == "CUSTOM" || it.source == "OVERRIDE" })

        override suspend fun deleteAllOverrides(): Int =
            rows.count { it.source == "OVERRIDE" }.also { rows.removeAll { it.source == "OVERRIDE" } }
    }

    private class FakeExamDao : ExamDao {
        override fun observeByTerm(termCode: String): Flow<List<com.gdutday.core.database.ExamEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<com.gdutday.core.database.ExamEntity>> = flowOf(emptyList())
        override fun observeNextExam(today: String): Flow<com.gdutday.core.database.ExamEntity?> = flowOf(null)
        override suspend fun getByTerm(termCode: String): List<com.gdutday.core.database.ExamEntity> = emptyList()
        override suspend fun insertAll(exams: List<com.gdutday.core.database.ExamEntity>) {}
        override suspend fun deleteByTerm(termCode: String) {}
    }

    private class FakeTermMetaDao : TermMetaDao {
        override fun observeAll(): Flow<List<TermMetaEntity>> = flowOf(emptyList())
        override fun observeCurrent(): Flow<TermMetaEntity?> = flowOf(null)
        override suspend fun getCurrent(): TermMetaEntity? = null
        override suspend fun getByCode(termCode: String): TermMetaEntity? = null
        override fun observeByCode(termCode: String): Flow<TermMetaEntity?> = flowOf(null)
        override suspend fun upsert(term: TermMetaEntity) {}
        override suspend fun upsertAll(terms: List<TermMetaEntity>) {}
        override suspend fun clearCurrent() {}
        override suspend fun markCurrent(termCode: String) {}
        override suspend fun updateSemesterStart(termCode: String, semesterStart: String, source: String, updatedAt: Long): Int = 0
    }

    private class FakeCourseColorDao : CourseColorDao {
        override suspend fun getAll(): List<CourseColorEntity> = emptyList()
        override suspend fun colorKeyOf(courseName: String): String? = null
        override suspend fun upsert(entry: CourseColorEntity) {}
        override suspend fun insertIfAbsent(entries: List<CourseColorEntity>) {}
        override suspend fun delete(courseName: String) {}
        override suspend fun deleteAutoAssigned(): Int = 0
    }

    private class FakeSyncStateDao : SyncStateDao {
        override fun observe(): Flow<SyncStateEntity?> = flowOf(null)
        override suspend fun get(): SyncStateEntity? = null
        override suspend fun upsert(state: SyncStateEntity) {}
        override suspend fun clear() {}
    }

    private class FakeSettingsStore : SettingsStore {
        override val settings: Flow<UserSettings> = flowOf(UserSettings())
        override suspend fun update(transform: (UserSettings) -> UserSettings) {}
        override suspend fun reset() {}
    }

    private class FakeSessionStore : SessionStore {
        override val session: Flow<GdutSession?> = flowOf(null)
        override suspend fun current(): GdutSession? = null
        override suspend fun save(session: GdutSession) {}
        override suspend fun clear() {}
    }

    private class FakeAuthRepository : AuthRepository {
        override val session: StateFlow<GdutSession?> = MutableStateFlow(null)
        override val isLoggedIn: Flow<Boolean> = flowOf(false)
        override suspend fun login(studentId: String, password: String, rememberPassword: Boolean): GdutSession =
            throw UnsupportedOperationException()

        override suspend fun loginViaJxfw(
            studentId: String, password: String, captcha: String, captchaToken: String, rememberPassword: Boolean,
        ): GdutSession = throw UnsupportedOperationException()

        override suspend fun fetchJxfwCaptcha(): JxfwCaptcha = throw UnsupportedOperationException()
        override val profile: Flow<StudentProfile?> = flowOf(null)
        override suspend fun isSessionValid(): Boolean = false
        override suspend fun reloginSilently(): GdutSession? = null
        override suspend fun logout(clearLocalData: Boolean) {}
    }

    private fun repository(courseDao: FakeCourseDao) = ScheduleRepositoryImpl(
        courseDao = courseDao,
        examDao = FakeExamDao(),
        termMetaDao = FakeTermMetaDao(),
        courseColorDao = FakeCourseColorDao(),
        syncStateDao = FakeSyncStateDao(),
        settingsStore = FakeSettingsStore(),
        sessionStore = FakeSessionStore(),
        authRepository = FakeAuthRepository(),
        jxfwClientFactory = { _, _ -> throw UnsupportedOperationException() },
    )

    private fun course(name: String, day: Int = 1, start: Int = 1, count: Int = 2, weeks: Set<Int> = (1..16).toSet()) =
        Course(
            id = 0L, term = term, name = name, dayOfWeek = day,
            startSection = start, sectionCount = count, weeks = weeks,
        )

    @Test
    fun `force为false时冲突阻止插入`() = runTest {
        val dao = FakeCourseDao()
        dao.upsert(with(Mappers) { course("高数").toEntity() })
        val repo = repository(dao)

        val conflicts = repo.addCustomCourse(course("社团活动"), force = false)

        assertThat(conflicts).hasSize(1)
        assertThat(dao.rows.map { it.name }).containsExactly("高数")
    }

    @Test
    fun `force为true时允许冲突并插入`() = runTest {
        val dao = FakeCourseDao()
        dao.upsert(with(Mappers) { course("高数").toEntity() })
        val repo = repository(dao)

        val conflicts = repo.addCustomCourse(course("社团活动"), force = true)

        assertThat(conflicts).isEmpty()
        assertThat(dao.rows.map { it.name }).containsExactly("高数", "社团活动")
        assertThat(dao.rows.first { it.name == "社团活动" }.source)
            .isEqualTo(CourseSource.CUSTOM.name)
    }

    @Test
    fun `force为true时无冲突也正常插入`() = runTest {
        val dao = FakeCourseDao()
        val repo = repository(dao)

        val conflicts = repo.addCustomCourse(course("自习"), force = true)

        assertThat(conflicts).isEmpty()
        assertThat(dao.rows).hasSize(1)
    }

    @Test
    fun `无冲突时force为false也直接插入`() = runTest {
        val dao = FakeCourseDao()
        val repo = repository(dao)

        val conflicts = repo.addCustomCourse(course("自习"), force = false)

        assertThat(conflicts).isEmpty()
        assertThat(dao.rows).hasSize(1)
    }

    @Test
    fun `清空自定义课程会把补丁接管的周次还给教务课程而不是直接删掉`() = runTest {
        // 回归：编辑了物理课第 5 周会生成一条 OVERRIDE 补丁，同时把教务原行的第 5 周挖走。
        // 之前"清空自定义课程"直接删掉补丁行，导致第 5 周的物理课凭空消失，
        // 而不是像单条"还原"那样把周次还给教务原行。
        val dao = FakeCourseDao()
        val original = course("物理", weeks = (1..16).toSet())
        dao.upsert(
            with(Mappers) {
                original.copy(weeks = original.weeks - 5, source = CourseSource.SCHOOL).toEntity()
            },
        )
        dao.upsert(
            with(Mappers) {
                original.copy(
                    classroom = "梯形教室",
                    weeks = setOf(5),
                    source = CourseSource.OVERRIDE,
                    overrideScope = OverrideScope.THIS_WEEK,
                    overrideTargetNaturalKey = original.naturalKey,
                    overrideWeeks = setOf(5),
                ).toEntity()
            },
        )
        val repo = repository(dao)

        repo.deleteAllCustomCourses()

        val remaining = dao.rows.mapNotNull { with(Mappers) { it.toDomain() } }
        assertThat(remaining).hasSize(1)
        val restored = remaining.single()
        assertThat(restored.source).isEqualTo(CourseSource.SCHOOL)
        assertThat(restored.weeks).isEqualTo((1..16).toSet())
    }

    @Test
    fun `编辑自定义课程force为true时冲突不会拦截保存`() = runTest {
        // 回归：ScheduleViewModel#saveEdit 之前没传 force，updateCustomCourse 因为
        // 撞上已有课程返回非空冲突列表后编辑就静默不生效，界面上却没有任何提示。
        val dao = FakeCourseDao()
        dao.upsert(with(Mappers) { course("高数").toEntity() })
        val custom = course("社团活动", day = 1, start = 1, count = 2).copy(source = CourseSource.CUSTOM)
        val addedId = dao.upsert(with(Mappers) { custom.toEntity() })
        val repo = repository(dao)

        // 编辑后依然和"高数"撞在同一天同一节次——force=false 应该被拒绝。
        val edited = custom.copy(id = addedId, teacher = "王五")
        val blocked = repo.updateCustomCourse(edited, force = false)
        assertThat(blocked).isNotEmpty()
        assertThat(dao.rows.first { it.id == addedId }.teacher).isEmpty()

        val conflicts = repo.updateCustomCourse(edited, force = true)
        assertThat(conflicts).isEmpty()
        assertThat(dao.rows.first { it.id == addedId }.teacher).isEqualTo("王五")
    }

    @Test
    fun `保存教务课程补丁force为true时冲突不会拦截保存`() = runTest {
        // 同上，覆盖 saveSchoolOverride 这一侧——"选择全部周次却不生效"的报告
        // 根因就是这里默认不 force，编辑撞上任何一门课就整体不保存。
        val dao = FakeCourseDao()
        val physics = course("物理", day = 1, start = 3, count = 2, weeks = (1..16).toSet())
            .copy(source = CourseSource.SCHOOL)
        val physicsId = dao.upsert(with(Mappers) { physics.toEntity() })
        // 同一天同一节次的另一门课，用来制造冲突。
        dao.upsert(with(Mappers) { course("英语", day = 1, start = 3, count = 2).toEntity() })
        val repo = repository(dao)

        val original = physics.copy(id = physicsId)
        val edited = original.copy(classroom = "教5-301", weeks = (1..16).toSet())

        val blocked = repo.saveSchoolOverride(original, edited, OverrideScope.ALL, force = false)
        assertThat(blocked).isNotEmpty()
        assertThat(dao.rows.none { it.source == "OVERRIDE" }).isTrue()

        val conflicts = repo.saveSchoolOverride(original, edited, OverrideScope.ALL, force = true)
        assertThat(conflicts).isEmpty()
        val override = dao.rows.mapNotNull { with(Mappers) { it.toDomain() } }
            .single { it.source == CourseSource.OVERRIDE }
        assertThat(override.classroom).isEqualTo("教5-301")
        assertThat(override.weeks).isEqualTo((1..16).toSet())
    }
}
