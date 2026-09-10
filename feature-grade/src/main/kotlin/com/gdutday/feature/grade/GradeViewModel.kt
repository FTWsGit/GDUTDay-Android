package com.gdutday.feature.grade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gdutday.core.model.GdutException
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.AuthRepository
import com.gdutday.data.repository.GradeRepository
import com.gdutday.data.repository.ScheduleRepository
import com.gdutday.data.repository.SyncInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 成绩页 ViewModel。
 *
 * ## 为什么还要读 ScheduleRepository
 *
 * 成绩页要区分"登录了但从未同步"和"同步了但本学期无成绩"两种空状态，
 * 这需要知道**上次同步时刻**。成绩同步与课表同步写的是同一张 `sync_state` 表，
 * 而唯一暴露它的读接口是 [ScheduleRepository.observeScheduleUiState]。
 * 与其为此新增一个 Repository 方法，不如复用现有能力。
 *
 * 绩点、学分、挂科数全部由 [GradeRepository.observeSummaries] 算好，
 * 这里**不做任何业务计算**，只保留"当前选中哪个学期"和"是否正在手动同步"。
 */
public class GradeViewModel(
    private val gradeRepository: GradeRepository,
    scheduleRepository: ScheduleRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    /** 按学期分组的汇总，含加权绩点。 */
    public val summaries: StateFlow<List<com.gdutday.core.model.TermGradeSummary>> =
        gradeRepository.observeSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 可选学期名，按时间倒序。 */
    public val termNames: StateFlow<List<String>> = gradeRepository.observeTermNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    public val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 上次同步信息，用于空状态判定与"上次更新"展示。 */
    public val lastSync: StateFlow<SyncInfo?> = scheduleRepository.observeScheduleUiState()
        .map { it.lastSync }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 用户选中的学期；null 表示跟随"最新学期"。 */
    private val _selectedTermName = MutableStateFlow<String?>(null)
    public val selectedTermName: StateFlow<String?> = _selectedTermName.asStateFlow()

    /** 手动同步进行中。 */
    private val _syncing = MutableStateFlow(false)
    public val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    public val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    public fun selectTerm(termName: String?) {
        _selectedTermName.value = termName
    }

    /** 手动同步成绩。失败时把 `userMessage` 交给 UI，绝不静默。 */
    public fun refresh() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                gradeRepository.sync()
            } catch (e: GdutException) {
                _errorMessage.value = e.userMessage
            } catch (e: Throwable) {
                _errorMessage.value = e.message ?: "同步成绩失败"
            } finally {
                _syncing.value = false
            }
        }
    }

    public fun consumeError() {
        _errorMessage.value = null
    }

    public companion object {
        public fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GradeViewModel(
                    gradeRepository = container.gradeRepository,
                    scheduleRepository = container.scheduleRepository,
                    authRepository = container.authRepository,
                )
            }
        }
    }
}
