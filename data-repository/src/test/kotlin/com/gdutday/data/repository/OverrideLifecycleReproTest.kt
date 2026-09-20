package com.gdutday.data.repository

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.database.CourseColorDao
import com.gdutday.core.database.CourseColorEntity
import com.gdutday.core.database.CourseDao
import com.gdutday.core.database.CourseEntity
import com.gdutday.core.database.ExamDao
import com.gdutday.core.database.ExamEntity
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
 * 复现高级编辑的生命周期 bug：编辑 → 同步 → 检查是否出现重复课程。
 */
class OverrideLifecycleReproTest {

    private val term = Term(2025, 1)

    private class FakeCourseDao : CourseDao {
        val rows = mutableListOf<CourseEntity>()
        var nextId = 1L

        override fun observeByTerm(termCode: String): Flow<List<CourseEntity>> = flowOf(rows.filter { it.termCode == termCode })
        override fun observeByTermAndDay(termCode: String, dayOfWeek: Int): Flow<List<CourseEntity>> = flowOf(emptyList())
        override suspend fun getByTerm(termCode: String): List<CourseEntity> = rows.filter { it.termCode == termCode }
        override suspend fun getById(id: Long): CourseEntity? = rows.firstOrNull { it.id == id }
        override suspend fun distinctCourseNames(): List<String> = rows.map { it.name }.distinct()
        override suspend fun countByTerm(termCode: String): Int = rows.count { it.termCode == termCode }
        override suspend fun insertAll(courses: List<CourseEntity>): List<Long> = courses.map { upsert(it) }
        override suspend fun upsert(course: CourseEntity): Long {
            val assigned = if (course.id == 0L) nextId++ else course.id
            rows.removeAll { it.id == assigned }
            rows += course.copy(id = assigned)
            return assigned
        }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun deleteAllCustom(): Int { val n = rows.count { it.source == "CUSTOM" }; rows.removeAll { it.source == "CUSTOM" }; return n }
        override suspend fun deleteByTerm(termCode: String) { rows.removeAll { it.termCode == termCode } }
        override suspend fun deleteSchoolCourses(termCode: String) { rows.removeAll { it.termCode == termCode && it.source == "SCHOOL" } }
        override suspend fun getOverrides(termCode: String): List<CourseEntity> = rows.filter { it.termCode == termCode && it.source == "OVERRIDE" }
        override suspend fun getAllOverrides(): List<CourseEntity> = rows.filter { it.source == "OVERRIDE" }
        override suspend fun getSchoolCourses(termCode: String): List<CourseEntity> = rows.filter { it.termCode == termCode && it.source == "SCHOOL" }
        override fun observeCustomAndOverride(): Flow<List<CourseEntity>> = flowOf(rows.filter { it.source != "SCHOOL" })
        override suspend fun deleteAllOverrides(): Int { val n = rows.count { it.source == "OVERRIDE" }; rows.removeAll { it.source == "OVERRIDE" }; return n }
    }

    private class FakeExamDao : ExamDao {
        override fun observeByTerm(termCode: String): Flow<List<ExamEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<ExamEntity>> = flowOf(emptyList())
        override fun observeNextExam(today: String): Flow<ExamEntity?> = flowOf(null)
        override suspend fun getByTerm(termCode: String): List<ExamEntity> = emptyList()
        override suspend fun insertAll(exams: List<ExamEntity>) {}
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
        override suspend fun login(studentId: String, password: String, rememberPassword: Boolean): GdutSession = throw UnsupportedOperationException()
        override suspend fun loginViaJxfw(studentId: String, password: String, captcha: String, captchaToken: String, rememberPassword: Boolean): GdutSession = throw UnsupportedOperationException()
        override suspend fun fetchJxfwCaptcha(): JxfwCaptcha = throw UnsupportedOperationException()
        override val profile: Flow<StudentProfile?> = flowOf(null)
        override suspend fun isSessionValid(): Boolean = false
        override suspend fun reloginSilently(): GdutSession? = null
        override suspend fun logout(clearLocalData: Boolean) {}
    }

    private fun repository(dao: FakeCourseDao) = ScheduleRepositoryImpl(
        courseDao = dao,
        examDao = FakeExamDao(),
        termMetaDao = FakeTermMetaDao(),
        courseColorDao = FakeCourseColorDao(),
        syncStateDao = FakeSyncStateDao(),
        settingsStore = FakeSettingsStore(),
        sessionStore = FakeSessionStore(),
        authRepository = FakeAuthRepository(),
        jxfwClientFactory = { _, _ -> throw UnsupportedOperationException() },
    )

    private fun schoolCourse(name: String, day: Int, start: Int, count: Int, weeks: Set<Int>, id: Long = 0): Course =
        Course(
            id = id, term = term, name = name, teacher = "张三", classroom = "教1-101",
            dayOfWeek = day, startSection = start, sectionCount = count, weeks = weeks,
            source = CourseSource.SCHOOL,
        )

    /** 模拟同步：与生产 doSync 一致，只整体替换 SCHOOL 行；补丁由读取路径叠加。 */
    private suspend fun simulateSync(dao: FakeCourseDao, fresh: List<Course>) {
        dao.deleteSchoolCourses(term.shortCode)
        dao.insertAll(fresh.map { with(Mappers) { it.copy(id = 0).toEntity() } })
    }

    /** UI 可见视图：与 ScheduleUiStateBuilder 一致，补丁在读取时覆盖到教务行上。 */
    private fun visible(dao: FakeCourseDao): List<Course> {
        val all = dao.rows.mapNotNull { with(Mappers) { it.toDomain() } }.sortedBy { it.id }
        return applyUserOverrides(
            school = all.filter { it.source == CourseSource.SCHOOL },
            overrides = all.filter { it.source == CourseSource.OVERRIDE },
        ) + all.filter { it.source == CourseSource.CUSTOM }
    }

    @Test
    fun `单行改老师后同步不出现重复`() = runTest {
        val dao = FakeCourseDao()
        val repo = repository(dao)
        val original = schoolCourse("大学物理实验", day = 4, start = 6, count = 2, weeks = (1..16).toSet())
        val originalId = dao.upsert(with(Mappers) { original.toEntity() })
        val stored = original.copy(id = originalId)

        val edited = stored.copy(teacher = "李四")
        val conflicts = repo.saveSchoolOverride(stored, edited, OverrideScope.WEEK_RANGE, force = true)
        assertThat(conflicts).isEmpty()
        assertThat(visible(dao).filter { it.name == "大学物理实验" }).hasSize(1)

        // 同步：教务重新给出一模一样的行
        simulateSync(dao, listOf(original))
        val after = visible(dao).filter { it.name == "大学物理实验" }
        after.forEach { println("after-sync: id=${it.id} src=${it.source} weeks=${it.weeks} teacher=${it.teacher} day=${it.dayOfWeek} start=${it.startSection}") }
        // 期望：仍然只有一个时间段的课（第4天6-7节），老师是李四
        val day4 = after.filter { it.dayOfWeek == 4 }
        assertThat(day4).hasSize(1)
        assertThat(day4.single().teacher).isEqualTo("李四")
    }

    @Test
    fun `只改第六周老师后同步不出现重复`() = runTest {
        val dao = FakeCourseDao()
        val repo = repository(dao)
        val original = schoolCourse("大学物理实验", day = 4, start = 6, count = 2, weeks = (1..16).toSet())
        dao.upsert(with(Mappers) { original.toEntity() })

        val edited = original.copy(teacher = "李四", weeks = setOf(6))
        repo.saveSchoolOverride(original, edited, OverrideScope.WEEK_RANGE, force = true)

        simulateSync(dao, listOf(original))
        val after = visible(dao).filter { it.name == "大学物理实验" && it.dayOfWeek == 4 }
        after.forEach { println("week6: id=${it.id} src=${it.source} weeks=${it.weeks} teacher=${it.teacher}") }
        assertThat(after).hasSize(2) // 原始 1-16 减第6周 + 补丁第6周
        assertThat(after.first { it.source == CourseSource.SCHOOL }.teacher).isEqualTo("张三")
        assertThat(after.first { it.source == CourseSource.OVERRIDE }.teacher).isEqualTo("李四")
    }

    @Test
    fun `改名后同步不应出现新旧两个名字`() = runTest {
        val dao = FakeCourseDao()
        val repo = repository(dao)
        val original = schoolCourse("大学物理实验", day = 4, start = 6, count = 2, weeks = (1..16).toSet())
        dao.upsert(with(Mappers) { original.toEntity() })

        val edited = original.copy(name = "大学物理实验(改)")
        repo.saveSchoolOverride(original, edited, OverrideScope.WEEK_RANGE, force = true)

        simulateSync(dao, listOf(original))
        visible(dao).forEach { println("rename: id=${it.id} src=${it.source} name=${it.name} weeks=${it.weeks}") }
        val names = visible(dao).map { it.name }.toSet()
        println("names = $names")
    }

    @Test
    fun `还原补丁后课表立刻恢复原始行`() = runTest {
        val dao = FakeCourseDao()
        val repo = repository(dao)
        val original = schoolCourse("大学物理实验", day = 4, start = 6, count = 2, weeks = (1..16).toSet())
        val originalId = dao.upsert(with(Mappers) { original.toEntity() })

        val edited = original.copy(teacher = "李四", weeks = setOf(6))
        repo.saveSchoolOverride(original.copy(id = originalId), edited, OverrideScope.WEEK_RANGE, force = true)

        val patch = visible(dao).single { it.source == CourseSource.OVERRIDE }
        repo.restoreOriginal(patch.id)

        val after = visible(dao).filter { it.name == "大学物理实验" }
        after.forEach { println("restored: id=${it.id} src=${it.source} weeks=${it.weeks} teacher=${it.teacher}") }
        assertThat(after).hasSize(1)
        assertThat(after.single().weeks).isEqualTo((1..16).toSet())
        assertThat(after.single().teacher).isEqualTo("张三")
    }
}
