package com.gdutday.data.repository

import com.gdutday.core.common.KnownSemesterStarts
import com.gdutday.core.common.TermCalendar
import com.gdutday.core.database.CourseColorDao
import com.gdutday.core.database.CourseColorEntity
import com.gdutday.core.database.CourseDao
import com.gdutday.core.database.ExamDao
import com.gdutday.core.database.Mappers
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.database.SyncStateDao
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.database.TermMetaDao
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.datastore.SessionStore
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.ScheduleFetchStrategy
import com.gdutday.core.model.Term
import com.gdutday.data.gdut.jxfw.JxfwClient
import com.gdutday.data.gdut.jxfw.JxfwExamParser
import com.gdutday.data.gdut.jxfw.JxfwTermParser
import com.gdutday.data.gdut.jxfw.ScheduleEndpoint
import com.gdutday.data.gdut.session.GdutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ScheduleRepository] 的实现，也是整个 App 的编排核心。
 *
 * ## 读路径与写路径彻底分开
 *
 * - **读**（[observeScheduleUiState]）：五个 Room Flow + 两个内存 StateFlow 直接 combine，
 *   冷启动首屏读 Room 即可出内容，不等网络。装配细节见 [buildScheduleUiStateFlow]。
 * - **写**（[sync]）：完整的多接口编排，任何一环失败都**不清空已有数据**，
 *   只把失败写进 `sync_state`。这是与旧小程序最大的体验差异 —— 教务系统维护期间，
 *   用户依然能看到上一次同步成功的课表。
 *
 * ## 学期选择的持久化位置
 *
 * [selectTerm] 写 `UserSettings.selectedTerm`（DataStore），而不是数据库的 `is_current`。
 * `is_current` 每次同步都会被服务端覆盖，只能表达"教务认为现在是哪学期"；
 * 用户想看下学期时，需要的是一个与之独立、不会被同步冲掉的偏好。
 *
 * @param jxfwClientFactory 用会话 + 课表接口策略构造教务客户端。`JxfwClient` 是无状态的
 *   （状态在 CookieJar），每次同步新建，避免复用绑定了旧 cookie 的实例。
 */
public class ScheduleRepositoryImpl(
    private val courseDao: CourseDao,
    private val examDao: ExamDao,
    private val termMetaDao: TermMetaDao,
    private val courseColorDao: CourseColorDao,
    private val syncStateDao: SyncStateDao,
    private val settingsStore: SettingsStore,
    private val sessionStore: SessionStore,
    private val authRepository: AuthRepository,
    private val jxfwClientFactory: (GdutSession, ScheduleEndpoint) -> JxfwClient,
) : ScheduleRepository {

    /** 选中的周次。纯内存：每次打开 App 回到"本周"比记住上次翻到第几周更符合直觉。 */
    private val selectedWeek = MutableStateFlow<Int?>(null)

    private val syncing = MutableStateFlow(false)

    /** 当前是否有同步在跑。暴露给 [SyncScheduler] 与 UI，避免重复触发。 */
    public val isSyncing: StateFlow<Boolean> get() = syncing

    /** 课程名 → 颜色 key 的内存缓存。初始为空，首次收集 UI 时从 Room 读一次。 */
    private val colorKeys = MutableStateFlow<Map<String, String>>(emptyMap())
    private val colorsLoaded = AtomicBoolean(false)

    /** 串行化同步，避免用户连点刷新 / Worker 与手动同步同时跑导致重复写库。 */
    private val syncMutex = Mutex()

    override fun observeScheduleUiState(): Flow<ScheduleUiState> {
        val colors = flow {
            ensureColorsLoaded()
            emitAll(colorKeys)
        }
        return buildScheduleUiStateFlow(
            ScheduleSources(
                allTermMeta = termMetaDao.observeAll(),
                settings = settingsStore.settings,
                syncState = syncStateDao.observe(),
                isSyncing = syncing,
                selectedWeek = selectedWeek,
                colors = colors,
                coursesFor = { code -> courseDao.observeByTerm(code) },
                examsFor = { code -> examDao.observeByTerm(code) },
            ),
        )
    }

    override fun observeNextClass(): Flow<NextClass?> =
        observeScheduleUiState()
            .map { state -> computeNextClass(state, LocalDateTime.now()) }
            .distinctUntilChanged()

    // ------------------------------------------------------------------ 同步

    override suspend fun sync(term: Term?): SyncInfo = syncMutex.withLock {
        // 整个同步流程（OkHttp 阻塞调用 + Room 写入）必须离开调用方线程：
        // viewModelScope 默认在主线程收集，直接在这里发同步请求会抛
        // NetworkOnMainThreadException，被下面的 catch 包成一句没有信息量的"同步失败"
        //（成绩页"同步考试安排"按钮曾因此 100% 失败，而走 WorkManager 的课表刷新正常）。
        // 与 GradeRepositoryImpl.sync 的 withContext(IO) 同理。
        withContext(Dispatchers.IO) {
            syncing.value = true
            try {
                val info = doSync(term)
                // 通知监听者（桌面插件刷新等）。放在数据落库**之后**，
                // 这样监听者读到的一定是最新数据。
                // 它自己会吞掉监听者的异常，不会因为插件刷新失败而让同步报错。
                SyncListeners.notifyCompleted(info)
                info
            } catch (e: GdutException) {
                recordFailure(e.userMessage)
                // 失败也要通知：插件上的"上次更新"时间、"正在同步"指示都需要收敛。
                SyncListeners.notifyCompleted(
                    SyncInfo(at = java.time.Instant.now(), term = term, success = false, error = e.userMessage),
                )
                throw e
            } catch (e: Exception) {
                // OkHttp/Room 之外的非预期异常也要记进 sync_state，否则 UI 上会显示
                // "上次同步成功"，与用户实际遇到的失败矛盾。
                val wrapped = GdutException.Local("同步失败", e.message ?: e.javaClass.simpleName, e)
                recordFailure(wrapped.userMessage)
                SyncListeners.notifyCompleted(
                    SyncInfo(at = java.time.Instant.now(), term = term, success = false, error = wrapped.userMessage),
                )
                throw wrapped
            } finally {
                syncing.value = false
            }
        }
    }

    private suspend fun doSync(requestedTerm: Term?): SyncInfo {
        val settings = settingsStore.settings.first()

        // 「仅本地」策略：完全不联网，只刷新时间戳，用户明确选择了离线。
        if (settings.fetchStrategy == ScheduleFetchStrategy.LOCAL_ONLY) {
            val now = Instant.now()
            val prev = syncStateDao.get()
            syncStateDao.upsert(
                (prev ?: SyncStateEntity()).copy(lastSyncAt = now, lastSuccess = true, lastError = ""),
            )
            return SyncInfo(at = now, term = requestedTerm, success = true)
        }

        var session = sessionStore.current()
            ?: throw GdutException.SessionExpired("本地没有可用会话，请先登录")
        var client = newClient(session, settings.fetchStrategy)

        if (!client.isSessionValid()) {
            session = authRepository.reloginSilently()
                ?: throw GdutException.SessionExpired("登录状态已失效，且没有可用于静默重登的凭据")
            client = newClient(session, settings.fetchStrategy)
        }

        val termList: JxfwTermParser.TermList = client.fetchTermList()
        val targetTerm = requestedTerm
            ?: settings.selectedTerm
            ?: termList.current
            ?: termList.terms.firstOrNull()
            ?: throw GdutException.Local("教务系统没有返回任何学期")

        persistTermMetas(termList, targetTerm)

        val fetch = client.fetchSchedule(targetTerm)
        val warnings = fetch.warnings.toMutableList()

        // 考试不是关键路径：拿不到时保留上一次的考试数据（不写 exam 表即可），
        // 只记一条 warning。会话失效例外 —— 那说明整个会话都不可用，必须上抛。
        var examFetchFailed = false
        val examOutcome: JxfwExamParser.Outcome = try {
            client.fetchExams(targetTerm)
        } catch (e: GdutException.SessionExpired) {
            throw e
        } catch (e: Exception) {
            warnings += "考试安排获取失败：${e.message ?: e.javaClass.simpleName}"
            // 失败时用空 Outcome 继续走后面的开学日期/校区逻辑，但**绝不**据此清空 exam 表：
            // 接口抖动一次就删掉整学期考试，与上面的注释相悖。
            examFetchFailed = true
            JxfwExamParser.Outcome(emptyList())
        }

        // 开学日期：四级优先级，用户手填永远最高。
        val existingMeta = termMetaDao.getByCode(targetTerm.shortCode)
        val resolved = SemesterStartResolver.resolve(targetTerm, existingMeta, fetch.courses)
        val updatedAt = Instant.now().toEpochMilli()
        val startEntity = existingMeta?.copy(
            semesterStart = resolved.date.toString(),
            startSource = resolved.source.name,
            updatedAt = updatedAt,
        ) ?: TermMetaEntity(
            termCode = targetTerm.shortCode,
            xnxqdm = targetTerm.xnxqdm,
            displayName = termList.nameOf(targetTerm),
            semesterStart = resolved.date.toString(),
            startSource = resolved.source.name,
            updatedAt = updatedAt,
        )
        termMetaDao.upsert(startEntity)

        // 校区只能从考试安排的 xqmc 探测；探测不到就保持用户当前选择（默认 UNKNOWN）。
        val detectedCampus = examOutcome.campusHint
        if (detectedCampus != Campus.UNKNOWN && detectedCampus != settings.campus) {
            settingsStore.setCampus(detectedCampus)
        }

        // 配色：老课程名原样保留颜色，只有全新课程参与分配并落盘。
        val plan = CourseColorPolicy.plan(
            courseNames = fetch.courses.map { it.name }.distinct(),
            existing = courseColorDao.getAll(),
        )
        if (plan.toPersist.isNotEmpty()) courseColorDao.insertIfAbsent(plan.toPersist)

        val schoolCourses = fetch.courses.map { it.copy(colorKey = plan.assignment[it.name]) }
        // 关键：replaceSchoolCourses 是"只删 source=SCHOOL、保留 CUSTOM"的事务，
        // 用户的社团/实验课不会因为一次同步消失。
        courseDao.replaceSchoolCourses(
            targetTerm.shortCode,
            with(Mappers) { schoolCourses.map { it.toEntity() } },
        )
        // 同步把教务课程整体换成了新版，用户的补丁必须重新覆盖上去，
        // 否则"改过的教室/时间"会被这一次同步悄悄还原。
        applyUserOverrides(targetTerm)
        // 只有成功拿到考试数据才替换；失败时保留上一次的考试安排（存量数据不动）。
        if (!examFetchFailed) {
            examDao.replaceByTerm(
                targetTerm.shortCode,
                with(Mappers) { examOutcome.exams.map { it.toEntity() } },
            )
        }
        refreshColors()

        // 服务端会轮换 JSESSIONID，不回写的话下次冷启动就得重新登录。
        sessionStore.save(session.copy(cookies = client.currentCookies()))

        val now = Instant.now()
        syncStateDao.upsert(
            SyncStateEntity(
                lastSyncAt = now,
                lastTermCode = targetTerm.shortCode,
                lastSuccess = true,
                lastError = "",
                lastScheduleSource = fetch.endpoint.name,
                lastWarnings = Mappers.encodeStrings(warnings),
            ),
        )

        return SyncInfo(
            at = now,
            term = targetTerm,
            source = scheduleSourceFromName(fetch.endpoint.name),
            success = true,
            courseCount = schoolCourses.size,
            examCount = examOutcome.exams.size,
            warnings = warnings,
        )
    }

    /**
     * 把所有可选学期落盘，并标记教务系统的当前学期。
     *
     * 非目标学期没有课表数据可供反推，只能退到内置表 / 猜测；真正同步到该学期时
     * [SemesterStartResolver] 会用反推值覆盖它（除非用户手填过）。
     */
    private suspend fun persistTermMetas(termList: JxfwTermParser.TermList, target: Term) {
        val updatedAt = Instant.now().toEpochMilli()
        val entities = termList.terms.map { t ->
            val existing = termMetaDao.getByCode(t.shortCode)
            val known = KnownSemesterStarts.startOf(t)
            val start = existing?.let { Mappers.parseDateLenient(it.semesterStart) }
                ?: known
                ?: TermCalendar.guessSemesterStart(t.year, t.semester)
            // 已存在行的来源优先保留（可能是 USER），否则按来源判定。
            val source = existing?.startSource?.let { SemesterStartSource.fromName(it) }
                ?: if (known != null) SemesterStartSource.KNOWN_TABLE else SemesterStartSource.GUESSED
            TermMetaEntity(
                termCode = t.shortCode,
                xnxqdm = t.xnxqdm,
                displayName = termList.nameOf(t),
                isCurrent = t == termList.current,
                semesterStart = start.toString(),
                startSource = source.name,
                updatedAt = updatedAt,
            )
        }
        termMetaDao.upsertAll(entities)

        // 兜底：目标学期不在下拉框里时补一行，保证它有 meta 行，UI 不会因为缺行而拿不到日历。
        if (entities.none { it.termCode == target.shortCode }) {
            termMetaDao.upsert(
                TermMetaEntity(
                    termCode = target.shortCode,
                    xnxqdm = target.xnxqdm,
                    displayName = target.displayName,
                    semesterStart = TermCalendar.guessSemesterStart(target.year, target.semester).toString(),
                    startSource = SemesterStartSource.GUESSED.name,
                    updatedAt = updatedAt,
                ),
            )
        }
        termList.current?.let { termMetaDao.setCurrentTerm(it.shortCode) }
    }

    private fun newClient(session: GdutSession, strategy: ScheduleFetchStrategy): JxfwClient {
        val endpoint = when (strategy) {
            ScheduleFetchStrategy.AUTO -> ScheduleEndpoint.AUTO
            ScheduleFetchStrategy.ONLY_ALL_KB_LIST -> ScheduleEndpoint.ALL_KB_LIST
            ScheduleFetchStrategy.ONLY_DATA_LIST -> ScheduleEndpoint.DATA_LIST
            ScheduleFetchStrategy.LOCAL_ONLY -> ScheduleEndpoint.AUTO
        }
        return jxfwClientFactory(session, endpoint)
    }

    /**
     * 记录失败但**不动课程/考试表**。`sync_state` 写入本身也可能失败（数据库损坏），
     * 用 runCatching 吞掉，避免"记录失败"的动作掩盖原始异常。
     */
    private suspend fun recordFailure(message: String) {
        runCatching {
            val prev = syncStateDao.get()
            syncStateDao.upsert(
                (prev ?: SyncStateEntity()).copy(
                    lastSyncAt = Instant.now(),
                    lastSuccess = false,
                    // 成绩与课表共用 sync_state 单行，并发时 lastError 后写胜：
                    // 带上来源前缀，避免错误信息张冠李戴（诊断页能看到是谁失败的）。
                    lastError = "课表同步失败：$message",
                ),
            )
        }
    }

    // ------------------------------------------------------------------ 内存状态

    override suspend fun selectTerm(term: Term) {
        settingsStore.setSelectedTerm(term)
    }

    override suspend fun selectWeek(week: Int) {
        selectedWeek.value = week.coerceAtLeast(1)
    }

    // ------------------------------------------------------------------ 自定义课程

    override suspend fun addCustomCourse(course: Course, force: Boolean): List<Course> {
        val existing = existingCourses(course.term)
        val conflicts = findScheduleConflicts(existing, course)
        // 非空 = 没添加；由 UI 决定提示还是 force 重试。force 时返回空列表表示"已添加"。
        if (conflicts.isNotEmpty() && !force) return conflicts
        courseDao.upsert(with(Mappers) { course.copy(source = CourseSource.CUSTOM).toEntity() })
        return emptyList()
    }

    override suspend fun updateCustomCourse(course: Course): List<Course> {
        // updateCustomCourse 同时承接 CUSTOM 与 OVERRIDE 行的直接编辑：
        // OVERRIDE 行的用户改色/改备注不需要重新走一遍补丁生成。
        val existing = existingCourses(course.term)
        val conflicts = findScheduleConflicts(existing, course)
        if (conflicts.isNotEmpty()) return conflicts
        val source = if (course.source == CourseSource.OVERRIDE) CourseSource.OVERRIDE else CourseSource.CUSTOM
        courseDao.upsert(with(Mappers) { course.copy(source = source).toEntity() })
        return emptyList()
    }

    override suspend fun saveSchoolOverride(
        original: Course,
        editedFields: Course,
        scope: OverrideScope,
    ): List<Course> {
        require(original.source == CourseSource.SCHOOL) {
            "saveSchoolOverride 只接受教务课程，收到 ${original.source}"
        }
        val all = existingCourses(original.term)
        val conflicts = findScheduleConflicts(all, editedFields)
        if (conflicts.isNotEmpty()) return conflicts

        // 作用周次 = 按范围从原课程里拆出来的那部分；scope=ALL 时是全部周次。
        val affectedWeeks: Set<Int> = when (scope) {
            OverrideScope.ALL -> original.weeks
            else -> editedFields.weeks intersect original.weeks
        }
        if (affectedWeeks.isEmpty()) return emptyList()

        // 原课程被作用周次之外的部分保留原样；全被覆盖时整行删除。
        val remaining = original.weeks - affectedWeeks
        if (remaining.isEmpty()) {
            courseDao.deleteById(original.id)
        } else {
            courseDao.upsert(with(Mappers) { original.copy(weeks = remaining).toEntity() })
        }

        val patch = editedFields.copy(
            id = 0L, // 补丁是新行，不复用教务课程的 id
            source = CourseSource.OVERRIDE,
            weeks = affectedWeeks,
            overrideScope = scope,
            overrideTargetNaturalKey = original.naturalKey,
            overrideWeeks = affectedWeeks,
        )
        courseDao.upsert(with(Mappers) { patch.toEntity() })
        return emptyList()
    }

    override suspend fun deleteCourse(id: Long) {
        courseDao.deleteById(id)
    }

    override suspend fun deleteAllCustomCourses() {
        courseDao.deleteAllCustom()
        // 补丁也是"用户自己弄出来的课"，清空自定义课程时必须一并清掉，
        // 否则下次同步它们又会把教务课程拆一遍，看起来像没删干净。
        courseDao.deleteAllOverrides()
    }

    override suspend fun restoreOriginal(overrideId: Long) {
        val entity = courseDao.getById(overrideId) ?: return
        val patch = with(Mappers) { entity.toDomain() } ?: run {
            courseDao.deleteById(overrideId)
            return
        }
        if (patch.source != CourseSource.OVERRIDE) return

        courseDao.deleteById(overrideId)
        val term = patch.term
        // 还原 = 把被补丁接管的周次还给教务课程。教务行可能已经没有周次可用
        // （ALL 范围的补丁把原行删了），此时只能等下次同步重建教务版本。
        val school = courseDao.getSchoolCourses(term.shortCode)
        val target = school.firstOrNull { with(Mappers) { it.toDomain() }?.naturalKey == patch.overrideTargetNaturalKey }
            ?: return
        val domain = with(Mappers) { target.toDomain() } ?: return
        courseDao.upsert(with(Mappers) { domain.copy(weeks = domain.weeks + patch.overrideWeeks).toEntity() })
    }

    override fun observeCustomAndOverrideCourses(): Flow<Map<Term, List<Course>>> =
        courseDao.observeCustomAndOverride().map { entities ->
            entities.mapNotNull { with(Mappers) { it.toDomain() } }
                .groupBy { it.term }
        }

    override suspend fun isOverrideEffective(override: Course): Boolean {
        if (override.source != CourseSource.OVERRIDE) return true
        val nk = override.overrideTargetNaturalKey ?: return false
        return courseDao.getSchoolCourses(override.term.shortCode).any {
            with(Mappers) { it.toDomain() }?.naturalKey == nk
        }
    }

    override suspend fun setCourseColor(courseName: String, colorKey: String) {
        courseColorDao.upsert(
            CourseColorEntity(
                courseName = courseName,
                colorKey = colorKey,
                isUserChosen = true,
            ),
        )
        refreshColors()
    }

    override suspend fun resetColors() {
        courseColorDao.deleteAutoAssigned()
        val plan = CourseColorPolicy.plan(courseDao.distinctCourseNames(), courseColorDao.getAll())
        if (plan.toPersist.isNotEmpty()) courseColorDao.insertIfAbsent(plan.toPersist)
        refreshColors()
    }

    // ------------------------------------------------------------------ 学期开始日期

    override suspend fun setSemesterStart(term: Term, startDate: LocalDate) {
        val updatedAt = Instant.now().toEpochMilli()
        val existing = termMetaDao.getByCode(term.shortCode)
        if (existing == null) {
            termMetaDao.upsert(
                TermMetaEntity(
                    termCode = term.shortCode,
                    xnxqdm = term.xnxqdm,
                    displayName = term.displayName,
                    semesterStart = startDate.toString(),
                    startSource = SemesterStartSource.USER.name,
                    updatedAt = updatedAt,
                ),
            )
        } else {
            // 只更新日期与来源，不动 is_current / display_name。
            termMetaDao.updateSemesterStart(
                termCode = term.shortCode,
                semesterStart = startDate.toString(),
                source = SemesterStartSource.USER.name,
                updatedAt = updatedAt,
            )
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 把用户的 [CourseSource.OVERRIDE] 补丁重新覆盖到同步来的教务课程上。
     *
     * 必须在 `replaceSchoolCourses` **之后**调用：同步刚把教务课程整体换掉，
     * 补丁此刻才有新目标可匹配。匹配键是 `override_target_nk`（原课程的自然键）。
     *
     * 匹配不到（教务改了课、调了节次等）的补丁**保留在库里**不删除 ——
     * 那是用户的数据，只能由用户在设置页里删除；UI 通过
     * [isOverrideEffective] 把它标成"未生效"。
     *
     * 周次拆分语义（见 plan-003 3.3）：
     * - ALL：整行替换原教务课程；
     * - THIS_WEEK / WEEK_RANGE：原课程让出被覆盖的周次，补丁接管这些周次。
     */
    private suspend fun applyUserOverrides(term: Term) {
        val overrides = courseDao.getOverrides(term.shortCode)
        if (overrides.isEmpty()) return
        val school = courseDao.getSchoolCourses(term.shortCode)
            .mapNotNull { with(Mappers) { it.toDomain() } }
        val patches = overrides.mapNotNull { with(Mappers) { it.toDomain() } }
        // 拆分语义集中在 applyUserOverrides 纯函数里（可单测）；这里只负责落库。
        val desired = applyUserOverrides(school, patches)
        val existingIds = school.map { it.id }.toSet()
        for (course in desired) {
            if (course.id in existingIds && course in school) continue
            // 补丁行 upsert 保留原 id；教务行被删/被改时这里分别对应 INSERT/UPDATE。
            courseDao.upsert(with(Mappers) { course.toEntity() })
        }
        // 教务行若在 desired 里消失（被 ALL 补丁整行替换 / 周次被拆空），删除它。
        val desiredIds = desired.filter { it.id in existingIds }.map { it.id }.toSet()
        for (id in existingIds - desiredIds) courseDao.deleteById(id)
    }

    private suspend fun existingCourses(term: Term): List<Course> =
        courseDao.getByTerm(term.shortCode).mapNotNull { with(Mappers) { it.toDomain() } }

    private suspend fun ensureColorsLoaded() {
        if (colorsLoaded.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) {
                colorKeys.value = courseColorDao.getAll().associate { it.courseName to it.colorKey }
            }
        }
    }

    private suspend fun refreshColors() {
        withContext(Dispatchers.IO) {
            colorKeys.value = courseColorDao.getAll().associate { it.courseName to it.colorKey }
        }
        colorsLoaded.set(true)
    }
}
