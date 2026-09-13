package com.gdutday.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.datastore.CourseTextColor
import com.gdutday.core.datastore.CredentialStore
import com.gdutday.core.datastore.ScheduleView
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.datastore.StoredCredentials
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.Term
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.AuthRepository
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

/** 设置页的一次性用户反馈，由 Composable 负责映射成 strings.xml 文案并弹 Snackbar。 */
public sealed interface SettingsEvent {
    public data object TimetableSaved : SettingsEvent
    public data class TimetableInvalid(public val reason: TimetableInvalidReason) : SettingsEvent
    public data object ColorsReset : SettingsEvent
    public data object CustomCoursesCleared : SettingsEvent
    public data object AllDataCleared : SettingsEvent
    public data class Error(public val message: String) : SettingsEvent
}

/**
 * 设置页 ViewModel。
 *
 * 与其它 feature 一样：**只持有交互状态、只转发调用**，业务计算都在
 * Repository / [SettingsLogic] 里。它额外持有一个 [applicationContext]，
 * 用途只有两个：读版本号（诊断信息）和持久化背景图的 URI 授权。
 *
 * ## 背景图 URI 授权
 *
 * Photo Picker 返回的 URI 默认只在本次进程内可读，重启后失效。
 * [onBackgroundPicked] 里调用 `takePersistableUriPermission` 把授权写进系统，
 * 这是"选了背景图但重启后变回纯色"这个经典 bug 的正解。
 * 部分机型/Provider 不支持持久化，此时静默降级（背景图当次仍可用）。
 */
public class SettingsViewModel(
    private val applicationContext: Context,
    private val settingsStore: SettingsStore,
    private val scheduleRepository: ScheduleRepository,
    private val authRepository: AuthRepository,
    private val credentialStore: CredentialStore,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    public val settings: StateFlow<UserSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /** 课表页的完整状态，设置页只取 `term` / `calendar` / `semesterStartSource` / `lastSync`。 */
    public val scheduleState: StateFlow<ScheduleUiState> = scheduleRepository.observeScheduleUiState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    /** 已记住的凭据；null 表示没有记住密码。设置页的开关据此显示。 */
    public val credentials: StateFlow<StoredCredentials?> = credentialStore.credentials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 登录态。初值给 true —— 设置页只有在登录后才能进入，false 初值会让
     * "退出登录后跳转"的 LaunchedEffect 在打开设置页的瞬间误触发。
     */
    public val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    public val isSyncing: StateFlow<Boolean> = syncScheduler.isSyncing

    private val _events = MutableStateFlow<SettingsEvent?>(null)
    public val events: StateFlow<SettingsEvent?> = _events.asStateFlow()

    /** App 版本号，诊断信息用。读不到时回退 "unknown"，绝不让设置页崩溃。 */
    private val appVersion: String = runCatching {
        @Suppress("DEPRECATION")
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .versionName ?: "unknown"
    }.getOrDefault("unknown")

    // ---------------------------------------------------------------- 学期与校区

    public fun setCampus(campus: Campus) {
        viewModelScope.launch { settingsStore.setCampus(campus) }
    }

    /** 校准开学日期。日期错了整张课表的周次全错，所以走 Repository 的最高优先级入口。 */
    public fun setSemesterStart(date: LocalDate) {
        val term = scheduleState.value.term ?: return
        viewModelScope.launch { scheduleRepository.setSemesterStart(term, date) }
    }

    // ---------------------------------------------------------------- 课表外观

    public fun setScheduleView(view: ScheduleView) {
        viewModelScope.launch { settingsStore.setScheduleView(view) }
    }

    public fun setCourseBlockAlpha(alpha: Float) {
        viewModelScope.launch { settingsStore.setCourseBlockAlpha(SettingsLogic.clampAlpha(alpha)) }
    }

    public fun setDimFinishedCourses(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(dimFinishedCourses = enabled) } }
    }

    public fun setCourseTextColor(color: CourseTextColor) {
        viewModelScope.launch { settingsStore.update { it.copy(courseTextColor = color) } }
    }

    public fun setBackgroundBlur(dp: Int) {
        viewModelScope.launch { settingsStore.update { it.copy(backgroundBlurDp = dp.coerceAtLeast(0)) } }
    }

    public fun clearBackgroundImage() {
        viewModelScope.launch { settingsStore.update { it.copy(backgroundImageUri = null, backgroundBlurDp = 0) } }
    }

    /**
     * 处理 Photo Picker 的返回结果。
     *
     * @param uri 用户选择的图片；用户取消时为 null，直接忽略。
     */
    public fun onBackgroundPicked(uri: Uri?) {
        if (uri == null) return
        // 把一次性读授权升级为持久授权，否则重启后 URI 失效。
        runCatching {
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModelScope.launch { settingsStore.update { it.copy(backgroundImageUri = uri.toString()) } }
    }

    public fun setShowTeacher(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(showTeacher = enabled) } }
    }

    public fun setShowClassroom(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(showClassroom = enabled) } }
    }

    public fun setShowExtraSections(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(showExtraSections = enabled) } }
    }

    public fun setShowWeekend(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(showWeekend = enabled) } }
    }

    // ---------------------------------------------------------------- 作息表

    public fun setCustomTimetableEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.value
            // 首次开启时用当前校区的内置作息做种子，用户只需改要改的那几节。
            val seed = if (enabled && current.customTimetable.isEmpty()) {
                SettingsLogic.defaultTimetable(current.campus)
            } else {
                current.customTimetable
            }
            settingsStore.setCustomTimetable(enabled, seed)
        }
    }

    /** 保存自定义作息表。校验不通过时**只提示、不落盘**。 */
    public fun saveCustomTimetable(raw: List<String>) {
        val campus = settings.value.campus
        when (val result = SettingsLogic.validateCustomTimetable(campus, raw)) {
            is TimetableValidation.Valid -> {
                viewModelScope.launch {
                    settingsStore.setCustomTimetable(enabled = true, timetable = result.normalized)
                    _events.value = SettingsEvent.TimetableSaved
                }
            }

            is TimetableValidation.Invalid -> {
                _events.value = SettingsEvent.TimetableInvalid(result.reason)
            }
        }
    }

    /** 恢复当前校区的内置作息：关闭自定义并清空列表。 */
    public fun restoreDefaultTimetable() {
        viewModelScope.launch {
            settingsStore.setCustomTimetable(enabled = false, timetable = emptyList())
            _events.value = SettingsEvent.TimetableSaved
        }
    }

    // ---------------------------------------------------------------- 数据

    public fun setFetchStrategy(strategy: com.gdutday.core.model.ScheduleFetchStrategy) {
        viewModelScope.launch { settingsStore.setFetchStrategy(strategy) }
    }

    public fun setAutoSyncOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(autoSyncOnLaunch = enabled) }
            if (enabled) syncScheduler.schedulePeriodicSync() else syncScheduler.cancelPeriodicSync()
        }
    }

    public fun setAutoSyncIntervalHours(hours: Int) {
        viewModelScope.launch {
            settingsStore.update { it.copy(autoSyncIntervalHours = hours.coerceAtLeast(1)) }
            // 间隔变了要重排周期任务，ExistingPeriodicWorkPolicy.UPDATE 会保留进度。
            if (settings.value.autoSyncOnLaunch) syncScheduler.schedulePeriodicSync()
        }
    }

    /** 手动同步。走调度器而不是直接调 Repository，这样 isSyncing 状态与全局一致。 */
    public fun requestSync() {
        if (isSyncing.value) return
        syncScheduler.requestImmediateSync(expedited = true)
    }

    public fun resetColors() {
        viewModelScope.launch {
            scheduleRepository.resetColors()
            _events.value = SettingsEvent.ColorsReset
        }
    }

    public fun clearCustomCourses() {
        viewModelScope.launch {
            scheduleRepository.deleteAllCustomCourses()
            _events.value = SettingsEvent.CustomCoursesCleared
        }
    }

    // ---------------------------------------------------------------- 我的课程

    /**
     * 用户添加 / 修改过的全部课程（CUSTOM + OVERRIDE），按学期分组。
     * "我添加/修改的课程"页面的唯一数据源。
     */
    public val customAndOverrideCourses: StateFlow<Map<Term, List<Course>>> =
        scheduleRepository.observeCustomAndOverrideCourses()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 删除一条自定义课程或补丁。补丁删除后还原为教务版本。 */
    public fun deleteCourse(id: Long) {
        viewModelScope.launch { scheduleRepository.deleteCourse(id) }
    }

    /** 还原一条教务补丁：删除补丁行，把被接管的周次还给教务课程。 */
    public fun restoreOriginal(overrideId: Long) {
        viewModelScope.launch { scheduleRepository.restoreOriginal(overrideId) }
    }

    /** 判断补丁是否仍匹配得到教务课程（false = 未生效，UI 标灰提示）。 */
    public suspend fun isOverrideEffective(override: Course): Boolean =
        scheduleRepository.isOverrideEffective(override)

    // ---------------------------------------------------------------- 工具

    /** 保存图书馆二维码学号。存储层会过滤非数字字符。 */
    public fun setLibraryQrStudentId(studentId: String) {
        viewModelScope.launch { settingsStore.setLibraryQrStudentId(studentId) }
    }

    // ---------------------------------------------------------------- 隐私

    /** 关闭"记住密码"时必须真的擦除本机凭据，不能只是改个开关。 */
    public fun clearRememberedPassword() {
        viewModelScope.launch { credentialStore.clear() }
    }

    // ---------------------------------------------------------------- 关于 / 退出

    /** 生成可一键复制的诊断信息。内容全部脱敏，见 [SettingsLogic.buildDiagnostics]。 */
    public fun diagnostics(): String {
        val state = scheduleState.value
        return SettingsLogic.buildDiagnostics(
            sessionSafe = authRepository.session.value?.toSafeString(),
            syncInfo = state.lastSync,
            timetable = state.timetable,
            semesterStart = state.calendar?.semesterStart,
            startSource = state.semesterStartSource,
            appVersion = appVersion,
        )
    }

    public fun effectiveTimetable(): CampusTimetable =
        CampusTimetable.parseCustom(settings.value.campus, settings.value.customTimetable)
            ?: CampusTimetable.of(settings.value.campus)

    public fun logout(clearLocalData: Boolean) {
        viewModelScope.launch { authRepository.logout(clearLocalData = clearLocalData) }
    }

    public fun consumeEvent() {
        _events.value = null
    }

    public companion object {
        public fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    applicationContext = container.applicationContext,
                    settingsStore = container.settingsStore,
                    scheduleRepository = container.scheduleRepository,
                    authRepository = container.authRepository,
                    credentialStore = container.credentialStore,
                    syncScheduler = container.syncScheduler,
                )
            }
        }
    }
}
