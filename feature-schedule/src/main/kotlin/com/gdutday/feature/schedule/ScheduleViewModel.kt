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
import com.gdutday.core.model.Term
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.ScheduleRepository
import com.gdutday.data.repository.ScheduleUiState
import com.gdutday.data.repository.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

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

    private val _guessedBannerDismissed = MutableStateFlow(false)

    /** 开学日期校准横幅是否被用户关掉。仅在本次进程内有效，重开 App 会再提示。 */
    public val guessedBannerDismissed: StateFlow<Boolean> = _guessedBannerDismissed.asStateFlow()

    private val _campusBannerDismissed = MutableStateFlow(false)

    /** 校区未设置横幅是否被关掉。 */
    public val campusBannerDismissed: StateFlow<Boolean> = _campusBannerDismissed.asStateFlow()

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

    public fun dismissGuessedBanner() {
        _guessedBannerDismissed.value = true
    }

    public fun dismissCampusBanner() {
        _campusBannerDismissed.value = true
    }

    /** 下拉刷新 / 菜单同步。加急请求，用户正在等。 */
    public fun refresh() {
        syncScheduler.requestImmediateSync(expedited = true)
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
