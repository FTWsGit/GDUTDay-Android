package com.gdutday.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.datastore.ScheduleView
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.SyncSourceType
import com.gdutday.core.model.Term
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.ClassCascadeMeta
import com.gdutday.data.repository.ClassCascadeOption
import com.gdutday.data.repository.ScheduleRepository
import com.gdutday.data.repository.ScheduleUiState
import com.gdutday.data.repository.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 同步源对话框的班级级联筛选状态。
 *
 * [meta] 的学院/年级/专业列表来自班级课表主页；选中学院后 [majors] 会被
 * `getFind` 结果替换（按学院过滤）。确认前一切选择只存在这里，对话框关闭即重置。
 */
public data class ClassCascadeUiState(
    public val meta: ClassCascadeMeta? = null,
    public val metaLoading: Boolean = false,
    public val majors: List<ClassCascadeOption> = emptyList(),
    public val classes: List<ClassCascadeOption> = emptyList(),
    public val classesLoading: Boolean = false,
    public val selectedCollege: String = "",
    public val selectedGrade: String = "",
    public val selectedMajor: String = "",
    public val selectedClass: ClassCascadeOption? = null,
    public val error: String? = null,
)

/** `getFind` 的 guid：返回专业列表。 */
private const val GUID_MAJOR = "xsyxdm"

/** `getFind` 的 guid：返回班级列表。 */
private const val GUID_CLASS = "rxnf"

/**
 * 课表页的 ViewModel。
 *
 * ## 职责边界：只转发，不计算
 *
 * 课表所需的全部业务量（课程、考试、周次换算、冲突并排、配色、状态判定）
 * 都由 [ScheduleRepository.observeScheduleUiState] 在后台算好，
 * 这里**一行计算都不做**。原因见 `ScheduleUiState` 的类注释：
 * 放 ViewModel 里会让 Widget 需要复制逻辑，还会拖慢旋转屏幕后的重建。
 *
 * ViewModel 只持有那些"纯粹属于这个页面、进不了数据层"的交互状态：
 * 打开了哪一个课程详情、哪条提醒横幅被用户手动关掉了。
 *
 * 周次这个看似"业务"的状态其实由 Repository 持有（`selectWeek`），
 * 因为它同时决定要构建哪一周的网格；这里只暴露一个转发方法。
 */
public class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    /** 唯一的课表业务状态流。 */
    public val uiState: StateFlow<ScheduleUiState> = repository.observeScheduleUiState()
        // 5 秒的订阅延迟：横竖屏切换会短暂退订，给一点缓冲避免重算整张网格。
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    /** 外观设置。与业务状态分开，但同样只是转发。 */
    public val settings: StateFlow<UserSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _selectedBlock = MutableStateFlow<CourseBlock?>(null)

    /** 当前弹出的课程详情。null 表示没有弹窗。 */
    public val selectedBlock: StateFlow<CourseBlock?> = _selectedBlock.asStateFlow()

    private val _editingCourse = MutableStateFlow<Course?>(null)

    /** 正在编辑的课程（详情页点了"编辑"）。非 null 时弹出 [CourseEditSheet]。 */
    public val editingCourse: StateFlow<Course?> = _editingCourse.asStateFlow()

    private val _addCourseSheetVisible = MutableStateFlow(false)

    /** "新增课程" Sheet 是否打开。 */
    public val addCourseSheetVisible: StateFlow<Boolean> = _addCourseSheetVisible.asStateFlow()

    private val _addCourseForm = MutableStateFlow(AddCourseForm())

    /** 新增课程的表单快照。Sheet 每次输入都整体替换。 */
    public val addCourseForm: StateFlow<AddCourseForm> = _addCourseForm.asStateFlow()

    private val _guessedBannerDismissed = MutableStateFlow(false)

    /** 开学日期校准横幅是否被用户关掉。仅在本次进程内有效，重开 App 会再提示。 */
    public val guessedBannerDismissed: StateFlow<Boolean> = _guessedBannerDismissed.asStateFlow()

    private val _campusBannerDismissed = MutableStateFlow(false)

    /** 校区未设置横幅是否被关掉。 */
    public val campusBannerDismissed: StateFlow<Boolean> = _campusBannerDismissed.asStateFlow()

    // ---------------------------------------------------------------------- 日视图切换日期
    private val _selectedDayOffset = MutableStateFlow(0)

    /** 日视图当前选中日期相对今天的天数偏移（默认 0 = 今天）。负数表示过去，正数表示未来。 */
    public val selectedDayOffset: StateFlow<Int> = _selectedDayOffset.asStateFlow()

    /**
     * 左右滑动切天。如果目标日期跨入另一个周次，同时把周次也切过去，让网格数据同步更新。
     */
    public fun selectDay(delta: Int) {
        val newOffset = _selectedDayOffset.value + delta
        _selectedDayOffset.value = newOffset
        // 如果选中的日期不在当前展示的周里，自动切换周次让网格包含它
        val calendar = uiState.value.calendar ?: return
        val selectedDate = uiState.value.today.plusDays(newOffset.toLong())
        val targetWeek = calendar.weekOf(selectedDate)
        if (targetWeek != uiState.value.selectedWeek && targetWeek in 1..uiState.value.totalWeeks) {
            selectWeek(targetWeek)
        }
    }

    /** 回到今天的快捷入口。 */
    public fun backToToday() {
        _selectedDayOffset.value = 0
    }

    public fun selectWeek(week: Int) {
        val clamped = ScheduleGridMath.clampWeek(week, uiState.value.totalWeeks)
        viewModelScope.launch { repository.selectWeek(clamped) }
    }

    /** "回到本周"。 */
    public fun backToCurrentWeek() {
        selectWeek(uiState.value.todayWeek)
    }

    public fun selectTerm(term: Term) {
        viewModelScope.launch { repository.selectTerm(term) }
    }

    public fun openBlock(block: CourseBlock) {
        _selectedBlock.value = block
    }

    public fun closeBlockDetail() {
        _selectedBlock.value = null
    }

    /** 详情页点"编辑"：关掉详情，弹出编辑 Sheet。 */
    public fun openEdit(course: Course) {
        _selectedBlock.value = null
        _editingCourse.value = course
    }

    public fun closeEdit() {
        _editingCourse.value = null
    }

    /**
     * 保存编辑。CUSTOM 行直接更新；SCHOOL 行生成 OVERRIDE 补丁。
     * [scope] 只对教务课程有意义，自定义课程一律直接 upsert。
     *
     * 始终 `force = true`：这个 App 的课表网格本来就支持同一时段多门课并排渲染
     * （见 [com.gdutday.core.common.ConflictCluster]），冲突是被设计支持的正常状态，
     * 不该在保存编辑这一步被当成错误静默拒绝——之前这里没传 force，
     * `saveSchoolOverride`/`updateCustomCourse` 返回的冲突列表也没人读，
     * 结果是编辑只要撞上任何一门课（哪怕只是"全部周次"里某一周撞了一次）就整体不生效，
     * 界面上却什么提示都没有，看起来像"点了保存但没反应"。
     */
    public fun saveEdit(course: Course, scope: OverrideScope) {
        val original = _editingCourse.value ?: return
        viewModelScope.launch {
            when (original.source) {
                CourseSource.SCHOOL -> repository.saveSchoolOverride(original, course, scope, force = true)
                else -> repository.updateCustomCourse(course, force = true)
            }
            _editingCourse.value = null
        }
    }

    public fun dismissGuessedBanner() {
        _guessedBannerDismissed.value = true
    }

    public fun showAddCourseSheet() {
        _addCourseForm.value = AddCourseForm()
        _addCourseSheetVisible.value = true
    }

    public fun dismissAddCourseSheet() {
        _addCourseSheetVisible.value = false
    }

    public fun updateAddCourseForm(transform: (AddCourseForm) -> AddCourseForm) {
        _addCourseForm.value = transform(_addCourseForm.value)
    }

    /**
     * 保存新增课程。始终 `force = true`：自定义课程允许与已有课程冲突，
     * 冲突由网格的并排 / 堆叠降级呈现（见 `ConflictCluster`）。
     */
    public fun addCourse() {
        viewModelScope.launch {
            // 不读 uiState.value：stateIn(WhileSubscribed) 在无人订阅时保持初始值，
            // 学期/周次可能还没被算出来。直接从 Repository 拿一次当前状态；
            // 状态流为空（从未同步）时按无学期处理，不做任何写入。
            val state = runCatching { repository.observeScheduleUiState().first() }.getOrNull()
                ?: ScheduleUiState()
            val term = state.term ?: return@launch
            val course = _addCourseForm.value.toCourse(term, state.selectedWeek, state.totalWeeks)
            repository.addCustomCourse(course, force = true)
            _addCourseSheetVisible.value = false
        }
    }

    public fun dismissCampusBanner() {
        _campusBannerDismissed.value = true
    }

    // ---------------------------------------------------------------------- 班级级联筛选（同步源对话框）

    private val _classCascade = MutableStateFlow(ClassCascadeUiState())

    /** 同步源对话框的班级级联筛选状态。对话框关闭时重置。 */
    public val classCascade: StateFlow<ClassCascadeUiState> = _classCascade.asStateFlow()

    /**
     * 加载级联的静态选项（学院 / 年级 / 专业）与初始班级列表。
     * 对话框打开时调用一次；重复调用无副作用。
     */
    public fun loadClassCascade() {
        if (_classCascade.value.metaLoading || _classCascade.value.meta != null) return
        viewModelScope.launch {
            _classCascade.value = _classCascade.value.copy(metaLoading = true, error = null)
            try {
                val meta = repository.fetchClassCascadeMeta()
                _classCascade.value = _classCascade.value.copy(metaLoading = false, meta = meta)
                refreshCascadeMajors()
                refreshCascadeClasses()
            } catch (e: GdutException) {
                _classCascade.value = _classCascade.value.copy(metaLoading = false, error = e.userMessage)
            }
        }
    }

    /** 选学院。清空专业选择（服务端按学院返回专业列表），并联动刷新班级。 */
    public fun selectCascadeCollege(code: String) {
        if (_classCascade.value.selectedCollege == code) return
        _classCascade.value = _classCascade.value.copy(selectedCollege = code, selectedMajor = "", selectedClass = null)
        refreshCascadeMajors()
        refreshCascadeClasses()
    }

    /** 选年级。清空班级选择并刷新班级列表。 */
    public fun selectCascadeGrade(code: String) {
        if (_classCascade.value.selectedGrade == code) return
        _classCascade.value = _classCascade.value.copy(selectedGrade = code, selectedClass = null)
        refreshCascadeClasses()
    }

    /** 选专业。清空班级选择并刷新班级列表。 */
    public fun selectCascadeMajor(code: String) {
        if (_classCascade.value.selectedMajor == code) return
        _classCascade.value = _classCascade.value.copy(selectedMajor = code, selectedClass = null)
        refreshCascadeClasses()
    }

    /** 对话框内点选班级。只更新本地选择，确认时才落盘。 */
    public fun selectCascadeClass(option: ClassCascadeOption) {
        _classCascade.value = _classCascade.value.copy(selectedClass = option)
    }

    /** 对话框关闭时重置级联状态，下次打开重新加载。 */
    public fun resetClassCascade() {
        _classCascade.value = ClassCascadeUiState()
    }

    /** 专业列表按学院过滤（guid=xsyxdm）。学院为空 = 全量（来自主页 meta）。 */
    private fun refreshCascadeMajors() {
        val college = _classCascade.value.selectedCollege
        if (college.isBlank()) {
            _classCascade.value = _classCascade.value.copy(majors = _classCascade.value.meta?.majors.orEmpty())
            return
        }
        viewModelScope.launch {
            try {
                val majors = repository.fetchClassCascade(guid = GUID_MAJOR, collegeCode = college)
                _classCascade.value = _classCascade.value.copy(majors = majors)
            } catch (_: GdutException) {
                // 专业列表拉取失败不阻塞主流程：班级列表仍可按学院+年级过滤
            }
        }
    }

    /** 班级列表按当前过滤条件查询（guid=rxnf，条件全空 = 全校，服务端 7000+ 行也可接受）。 */
    private fun refreshCascadeClasses() {
        val s = _classCascade.value
        viewModelScope.launch {
            _classCascade.value = s.copy(classesLoading = true)
            try {
                val classes = repository.fetchClassCascade(
                    guid = GUID_CLASS,
                    grade = s.selectedGrade,
                    collegeCode = s.selectedCollege,
                    majorCode = s.selectedMajor,
                )
                _classCascade.value = _classCascade.value.copy(classesLoading = false, classes = classes)
            } catch (e: GdutException) {
                _classCascade.value = _classCascade.value.copy(classesLoading = false, error = e.userMessage)
            }
        }
    }

    /** 下拉刷新 / 菜单同步。加急请求，用户正在等。 */
    public fun refresh() {
        syncScheduler.requestImmediateSync(expedited = true)
    }

    /**
     * 切换课表同步源（个人课表 / 班级课表）。
     *
     * 切换后立即触发一次加急同步：用户切源就是想马上看到另一份数据。
     */
    public fun switchSyncSourceType(type: SyncSourceType) {
        viewModelScope.launch {
            settingsStore.setSyncSourceType(type)
            syncScheduler.requestImmediateSync(expedited = true)
        }
    }

    /**
     * 设置班级课表的班级（bjdm + 显示名）并触发同步。
     * 用于"同步源"对话框：填完班级代码保存后马上拉取该班级的课表。
     */
    public fun setClassSchedule(bjdm: String, className: String) {
        viewModelScope.launch {
            settingsStore.setClassSchedule(bjdm, className)
            if (bjdm.isNotBlank()) {
                settingsStore.setSyncSourceType(SyncSourceType.CLASS_SCHEDULE)
                syncScheduler.requestImmediateSync(expedited = true)
            }
        }
    }

    public fun setScheduleView(view: ScheduleView) {
        viewModelScope.launch { settingsStore.setScheduleView(view) }
    }

    public fun setCourseColor(courseName: String, colorKey: String) {
        viewModelScope.launch { repository.setCourseColor(courseName, colorKey) }
    }

    public fun deleteCourse(id: Long) {
        viewModelScope.launch {
            repository.deleteCourse(id)
            _selectedBlock.value = null
        }
    }

    /** 校准开学日期。日期错了整张课表的周次全错，所以这是一个高优先级操作。 */
    public fun setSemesterStart(term: Term, date: LocalDate) {
        viewModelScope.launch { repository.setSemesterStart(term, date) }
    }

    public companion object {

        /**
         * 手写工厂：从 [AppContainer] 取依赖。
         *
         * 本项目不用 Hilt（见 `AppContainer` 的注释），所以 ViewModel 的构造
         * 需要一个显式的 `ViewModelProvider.Factory`。用 `viewModelFactory {}`
         * 而不是匿名 `Factory` 对象，是为了让"缺依赖"在编译期而不是运行时暴露。
         */
        public fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ScheduleViewModel(
                    repository = container.scheduleRepository,
                    settingsStore = container.settingsStore,
                    syncScheduler = container.syncScheduler,
                )
            }
        }
    }
}
