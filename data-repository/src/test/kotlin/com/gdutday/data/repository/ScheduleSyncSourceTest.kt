package com.gdutday.data.repository

import com.gdutday.core.database.CourseColorDao
import com.gdutday.core.database.CourseColorEntity
import com.gdutday.core.database.CourseDao
import com.gdutday.core.database.CourseEntity
import com.gdutday.core.database.ExamDao
import com.gdutday.core.database.ExamEntity
import com.gdutday.core.database.SyncStateDao
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.database.TermMetaDao
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.datastore.SessionStore
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.gdutday.core.model.GdutSession
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.LoginMethod
import com.gdutday.core.model.ScheduleFetchStrategy
import com.gdutday.core.model.StoredCookie
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.SyncSourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [ScheduleRepositoryImpl.doSync] 的**同步源分支**测试。
 *
 * 用 MockWebServer 走真实的 `JxfwClient`（协议层无法被 fake，它是 final 类），
 * 只验证一件事：`syncSourceType` 决定同步调用**班级课表接口**还是**个人课表接口**。
 * 解析、归一化、回退逻辑由 data-gdut 自己的单测覆盖，这里不重复。
 */
class ScheduleSyncSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var hosts: GdutHosts

    private val recorded = mutableListOf<String>()

    private val courseRows = mutableListOf<CourseEntity>()
    private val settingsState = MutableStateFlow(UserSettings(campus = Campus.UNIVERSITY_CITY))

    private companion object {
        const val HOME_PATH = "/"
        const val TERM_LIST_PATH = "/xsksap!ksapList.action"
        const val PERSONAL_DATA_LIST_PATH = "/xsgrkbcx!getDataList.action"
        const val PERSONAL_ALL_KB_LIST_PATH = "/xsgrkbcx!xsAllKbList.action"
        const val CLASS_GET_KB_RQ_PATH = "/xsbjkbcx!getKbRq.action"
        const val EXAM_DATA_LIST_PATH = "/xsksap!getDataList.action"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        hosts = GdutHosts.forTestServer("http://localhost:${server.port}")
        recorded.clear()
        courseRows.clear()
        settingsState.value = UserSettings(campus = Campus.UNIVERSITY_CITY)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                recorded += path.substringBefore('?')
                return when {
                    // ⚠ "/" 判断必须放最后：所有 path 都以 "/" 开头
                    path == "/" -> ok("首页")
                    path.startsWith(TERM_LIST_PATH) -> ok(termListHtml())
                    path.startsWith(CLASS_GET_KB_RQ_PATH) -> ok(classGetKbRqBody())
                    path.startsWith(PERSONAL_DATA_LIST_PATH) -> ok(dataListBody())
                    path.startsWith(EXAM_DATA_LIST_PATH) -> ok("""{"total":0,"rows":[]}""")
                    else -> MockResponse().setResponseCode(404).setBody("unexpected: $path")
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/html;charset=UTF-8")
        .setBody(body)

    private fun termListHtml(): String = """
        <html><body><select name="xnxqdm">
        <option value='202501' selected>2025-2026学年第一学期</option>
        </select></body></html>
    """.trimIndent()

    /** 班级课表主接口：一门课 + 一周日期。 */
    private fun classGetKbRqBody(): String =
        """[[{"kcmc":"班级高等数学","kcbh":"1001","jxbmc":"高数A-01","xnxqdm":"202501",""" +
            """"zc":"1","jcdm":"0102","xq":"1","jxcdmc":"教5-301","pkrs":"2025-09-01"}],""" +
            """[{"xqmc":"1","rq":"2025-09-01"}]]"""

    /** 个人课表 getDataList：一门课。 */
    private fun dataListBody(): String =
        """{"total":1,"rows":[{"kcmc":"个人高等数学","jxcdmc":"教5-301","teaxms":"张三",""" +
            """"xq":"1","zc":"1","jcdm":"0102"}]}"""

    private fun fakeSession() = GdutSession(
        cookies = listOf(
            StoredCookie(
                name = "JSESSIONID", value = "test", domain = hosts.jxfwHost,
                path = "/", expiresAtMillis = Long.MAX_VALUE,
                secure = false, httpOnly = true, hostOnly = true,
            ),
        ),
        profile = StudentProfile(studentId = "3120012345"),
        method = LoginMethod.UNIFIED_AUTH,
        hosts = hosts,
    )

    private class FakeCourseDao : CourseDao {
        val rows = mutableListOf<CourseEntity>()
        var replaced = 0

        override fun observeByTerm(termCode: String): Flow<List<CourseEntity>> = flowOf(rows.filter { it.termCode == termCode })
        override fun observeByTermAndDay(termCode: String, dayOfWeek: Int): Flow<List<CourseEntity>> = flowOf(emptyList())
        override suspend fun getByTerm(termCode: String): List<CourseEntity> = rows.filter { it.termCode == termCode }
        override suspend fun getById(id: Long): CourseEntity? = rows.firstOrNull { it.id == id }
        override suspend fun distinctCourseNames(): List<String> = rows.map { it.name }.distinct()
        override suspend fun countByTerm(termCode: String): Int = rows.count { it.termCode == termCode }
        override suspend fun insertAll(courses: List<CourseEntity>): List<Long> =
            courses.map { rows += it.copy(id = (rows.maxOfOrNull { r -> r.id } ?: 0L) + 1); it.id }

        override suspend fun upsert(course: CourseEntity): Long {
            rows.removeAll { it.id == course.id && course.id != 0L }
            val assigned = if (course.id == 0L) (rows.maxOfOrNull { it.id } ?: 0L) + 1 else course.id
            rows += course.copy(id = assigned)
            return assigned
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteAllCustom(): Int = 0
        override suspend fun deleteByTerm(termCode: String) {
            rows.removeAll { it.termCode == termCode }
        }

        override suspend fun deleteSchoolCourses(termCode: String) {
            rows.removeAll { it.termCode == termCode && it.source == "SCHOOL" }
        }

        override suspend fun getOverrides(termCode: String): List<CourseEntity> = emptyList()

        override suspend fun getAllOverrides(): List<CourseEntity> = emptyList()
        override suspend fun getSchoolCourses(termCode: String): List<CourseEntity> =
            rows.filter { it.termCode == termCode && it.source == "SCHOOL" }

        override fun observeCustomAndOverride(): Flow<List<CourseEntity>> = flowOf(emptyList())
        override suspend fun deleteAllOverrides(): Int = 0
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
        var state: SyncStateEntity? = null
        override fun observe(): Flow<SyncStateEntity?> = flowOf(state)
        override suspend fun get(): SyncStateEntity? = state
        override suspend fun upsert(state: SyncStateEntity) {
            this.state = state
        }

        override suspend fun clear() {
            state = null
        }
    }

    private inner class FakeSettingsStore : SettingsStore {
        override val settings: Flow<UserSettings> = settingsState
        override suspend fun update(transform: (UserSettings) -> UserSettings) {
            settingsState.value = transform(settingsState.value)
        }

        override suspend fun reset() {
            settingsState.value = UserSettings()
        }
    }

    private inner class FakeSessionStore : SessionStore {
        private val stored = GdutSession(
            cookies = listOf(
                StoredCookie(
                    name = "JSESSIONID", value = "test", domain = hosts.jxfwHost,
                    path = "/", expiresAtMillis = Long.MAX_VALUE,
                    secure = false, httpOnly = true, hostOnly = true,
                ),
            ),
            profile = StudentProfile(studentId = "3120012345"),
            method = LoginMethod.UNIFIED_AUTH,
            hosts = hosts,
        )

        override val session: Flow<GdutSession?> = flowOf(stored)
        override suspend fun current(): GdutSession? = stored
        override suspend fun save(session: GdutSession) {}
        override suspend fun clear() {}
    }

    private class FakeAuthRepository : AuthRepository {
        override val session: StateFlow<GdutSession?> = MutableStateFlow(null)
        override val isLoggedIn: Flow<Boolean> = flowOf(true)
        override suspend fun login(studentId: String, password: String, rememberPassword: Boolean): GdutSession =
            throw UnsupportedOperationException()

        override suspend fun loginViaJxfw(
            studentId: String, password: String, captcha: String, captchaToken: String, rememberPassword: Boolean,
        ): GdutSession = throw UnsupportedOperationException()

        override suspend fun fetchJxfwCaptcha(): JxfwCaptcha = throw UnsupportedOperationException()
        override val profile: Flow<StudentProfile?> = flowOf(null)
        override suspend fun isSessionValid(): Boolean = true
        override suspend fun reloginSilently(): GdutSession? = null
        override suspend fun logout(clearLocalData: Boolean) {}
    }

    private fun repository(courseDao: FakeCourseDao, syncStateDao: FakeSyncStateDao): ScheduleRepositoryImpl =
        ScheduleRepositoryImpl(
            courseDao = courseDao,
            examDao = FakeExamDao(),
            termMetaDao = FakeTermMetaDao(),
            courseColorDao = FakeCourseColorDao(),
            syncStateDao = syncStateDao,
            settingsStore = FakeSettingsStore(),
            sessionStore = FakeSessionStore(),
            authRepository = FakeAuthRepository(),
            jxfwClientFactory = { session, config ->
                com.gdutday.data.gdut.jxfw.JxfwClient(
                    OkHttpClient(),
                    session,
                    config.copy(hosts = hosts),
                )
            },
        )

    @Test
    fun `同步源为班级课表时走班级接口而不走个人接口`() = runTest {
        settingsState.value = settingsState.value.copy(
            syncSourceType = SyncSourceType.CLASS_SCHEDULE,
            classScheduleBjdm = "116523137",
            classScheduleClassName = "计算机25(5)",
        )
        val courseDao = FakeCourseDao()
        val syncStateDao = FakeSyncStateDao()
        val repo = repository(courseDao, syncStateDao)

        val info = repo.sync()

        assertThat(info.success).isTrue()
        assertThat(recorded).contains(CLASS_GET_KB_RQ_PATH)
        assertThat(recorded.none { it.startsWith(PERSONAL_DATA_LIST_PATH) }).isTrue()
        assertThat(recorded.none { it.startsWith(PERSONAL_ALL_KB_LIST_PATH) }).isTrue()
        // 班级课表数据按 SCHOOL 来源入库（replaceSchoolCourses 对它同样安全）
        assertThat(courseDao.rows.map { it.name }).containsExactly("班级高等数学")
        assertThat(courseDao.rows.single().source).isEqualTo("SCHOOL")
        // lastScheduleSource 记录的是实际命中的班级接口
        assertThat(syncStateDao.state?.lastScheduleSource).isEqualTo("CLASS_SCHEDULE_DATA_LIST")
    }

    @Test
    fun `同步源为个人课表时走个人接口而不走班级接口`() = runTest {
        settingsState.value = settingsState.value.copy(
            syncSourceType = SyncSourceType.PERSONAL,
            classScheduleBjdm = "116523137",
        )
        val courseDao = FakeCourseDao()
        val syncStateDao = FakeSyncStateDao()
        val repo = repository(courseDao, syncStateDao)

        val info = repo.sync()

        assertThat(info.success).isTrue()
        assertThat(recorded).contains(PERSONAL_DATA_LIST_PATH)
        assertThat(recorded.none { it.startsWith(CLASS_GET_KB_RQ_PATH) }).isTrue()
        assertThat(courseDao.rows.map { it.name }).containsExactly("个人高等数学")
    }

    @Test
    fun `班级课表同步源但未选班级时仍走个人接口`() = runTest {
        // bjdm 为空：没有班级可选，此时静默回退个人课表比直接失败更合理
        settingsState.value = settingsState.value.copy(
            syncSourceType = SyncSourceType.CLASS_SCHEDULE,
            classScheduleBjdm = "",
        )
        val courseDao = FakeCourseDao()
        val syncStateDao = FakeSyncStateDao()
        val repo = repository(courseDao, syncStateDao)

        val info = repo.sync()

        assertThat(info.success).isTrue()
        assertThat(recorded).contains(PERSONAL_DATA_LIST_PATH)
        assertThat(recorded.none { it.startsWith(CLASS_GET_KB_RQ_PATH) }).isTrue()
    }

    @Test
    fun `从班级课表切回个人课表后下一次同步恢复个人数据`() = runTest {
        settingsState.value = settingsState.value.copy(
            syncSourceType = SyncSourceType.CLASS_SCHEDULE,
            classScheduleBjdm = "116523137",
        )
        val courseDao = FakeCourseDao()
        val syncStateDao = FakeSyncStateDao()
        val repo = repository(courseDao, syncStateDao)

        repo.sync()
        assertThat(courseDao.rows.map { it.name }).containsExactly("班级高等数学")

        // 切回个人课表：下一次同步整体替换为个人数据
        settingsState.value = settingsState.value.copy(
            syncSourceType = SyncSourceType.PERSONAL,
        )
        repo.sync()

        assertThat(courseDao.rows.map { it.name }).containsExactly("个人高等数学")
        assertThat(syncStateDao.state?.lastScheduleSource).isEqualTo("DATA_LIST")
    }

    @Test
    fun `fetchStrategy为LOCAL_ONLY时完全不发请求`() = runTest {
        settingsState.value = settingsState.value.copy(
            syncSourceType = SyncSourceType.CLASS_SCHEDULE,
            classScheduleBjdm = "116523137",
            fetchStrategy = ScheduleFetchStrategy.LOCAL_ONLY,
        )
        val courseDao = FakeCourseDao()
        val syncStateDao = FakeSyncStateDao()
        val repo = repository(courseDao, syncStateDao)

        val info = repo.sync()

        assertThat(info.success).isTrue()
        assertThat(recorded).isEmpty()
    }
}
