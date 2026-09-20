package com.gdutday.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.OverrideScope
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.ScheduleRepository
import com.gdutday.data.repository.ScheduleUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 批量编辑模板：每个字段自己一个"要不要覆盖"的开关，`null` = 保留每一行自己原来的值。
 * 这就是 [AdvancedCourseEditViewModel] 类 KDoc 里"模板只覆盖你勾了开关的那几个字段"——
 * 字段本身用可空类型表达开关状态，不用另开一组 `applyXxx: Boolean` 布尔量，
 * 因为"开关关着"和"开关开着但填了空字符串"是两回事，可空类型天然区分这两种状态。
 */
public data class OccurrenceTemplate(
    val teacher: String? = null,
    val classroom: String? = null,
    val colorKey: String? = null,
    val weeks: Set<Int>? = null,
    val dayOfWeek: Int? = null,
    val startSection: Int? = null,
    val sectionCount: Int? = null,
    /** 只在 [useRealTime] 非空时生效，两者要么都给、要么都不给。 */
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    /** true = 按 [startMinute]/[endMinute] 覆盖成绝对时间；false = 覆盖成按节次；null = 不动。 */
    val useRealTime: Boolean? = null,
) {
    public val isEmpty: Boolean
        get() = teacher == null && classroom == null && colorKey == null && weeks == null &&
            dayOfWeek == null && startSection == null && sectionCount == null && useRealTime == null
}

/**
 * "高级编辑"（按课程名批量改）的 ViewModel。
 *
 * 背景见 [CourseEditSheet] 顶部的 KDoc：那个 sheet 只编辑用户点开的一个色块，
 * 名字锁死不让改。这里反过来——按课程名把同一学期所有同名的行聚合起来
 * （不管是 [CourseSource.SCHOOL] 原始行、已经打过的 [CourseSource.OVERRIDE] 补丁，
 * 还是 [CourseSource.CUSTOM] 行），一次性重命名，逐行改教室/老师/颜色/周次/星期/节次，
 * 或者勾几行、用 [OccurrenceTemplate] 只同步某几个字段——这就是调查报告里
 * "1-8周一3-4节 + 9-16周三6-7节"那种同一门课横跨不同天/节次的情况，
 * 简单编辑弹层天生处理不了，只能在这里做。
 *
 * ## 匹配靠名字，不是新加一个 id 字段
 *
 * 教务系统本身不给"一门课"分配稳定 id（同一门课今天在这排一次课明天在那排一次课，
 * 接口给的 `dgksdm` 是"某周某天某节次的排课"粒度，见调查报告 2.3 节）。
 * 名字就是这门课的身份证——所以 [CourseEditSheet] 才要锁死名字不让在那边零散改，
 * 只允许在这里一次性把所有行改成新名字，避免同一门课的名字在不同行之间不一致，
 * 导致下次"按名字聚合"时把它拆散成两门课。
 *
 * ## 数据源不需要新的 Repository 方法
 *
 * [ScheduleUiState.courses] 本来就是"选中学期的全部课程"（含 SCHOOL/OVERRIDE/CUSTOM），
 * 直接按名字过滤即可，不用给 [ScheduleRepository] 加新接口——也就不用碰它现有的
 * 几个假实现（测试用）。
 *
 * ## 批量改字段：模板只覆盖你勾了开关的那几个字段
 *
 * 一开始做过"勾几行、表单里改哪个字段就都套用"的批量编辑，问题是表单会把
 * *没碰过*的字段也一起套用过去——比如一门课每次课老师都不一样，用户只想
 * 同步"上课时间"，结果表单里显示的教师值（不管是空的还是随手带出来的某一行的值）
 * 也被当成"要改成这个"一起写了进去，把本来就该各不相同的老师全部覆盖成一个值。
 * [OccurrenceTemplate] 用"每个字段自己一个开关，`null` = 不动这个字段"解决这个问题：
 * [applyTemplate] 只在选中的行上覆盖开关打开的字段，其余字段照抄每一行自己原来的值。
 * 单行编辑（[editOccurrence]）不用这套开关——它的表单本来就是从这一行自己的值
 * 展开的，保存回去的每个字段都确实是"这一行现在该有的值"，不存在"顺带覆盖"的问题。
 *
 * ## 教务行的补丁为什么用 [OverrideScope.WEEK_RANGE] 而不是 [OverrideScope.ALL]
 *
 * `saveSchoolOverride` 对 `scope=ALL` 有一条特殊规则：作用周次恒等于
 * `original.weeks`，**完全无视 `editedFields.weeks`**——这是配合 [ScheduleViewModel]
 * 那边"编辑当前这一周"的用法设计的（选中周本来就是 `original.weeks` 的子集之一）。
 * 这里"勾几行、在周次网格里改一个新的周次集合"如果也传 `ALL`，用户把周次改小
 * 会被静默无视，改动等于白做。`WEEK_RANGE` 走 `editedFields.weeks ∩ original.weeks`，
 * 能精确表达"这一行只有其中这几周要变"；当选中周次等于原周次时效果和 `ALL`
 * 完全一致——严格更强，没有理由用 `ALL`。
 */
public class AdvancedCourseEditViewModel(
    private val repository: ScheduleRepository,
    initialCourseName: String,
) : ViewModel() {

    private val _courseName = MutableStateFlow(initialCourseName)
    public val courseName: StateFlow<String> = _courseName.asStateFlow()

    // Eagerly，不是 WhileSubscribed：这个 VM 只在高级编辑页打开期间存活，
    // renameAll/editOccurrence 等操作会同步读 occurrences.value——用 WhileSubscribed
    // 会让 upstream 在还没有人 collect 之前一直不启动，.value 读到的永远是初始空列表。
    private val state: StateFlow<ScheduleUiState> = repository.observeScheduleUiState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleUiState())

    /** 这门课在本学期的全部出现，按星期/节次排序，UI 逐行展示、点开逐行编辑。 */
    public val occurrences: StateFlow<List<Course>> =
        combine(state, _courseName) { s, name -> s.courses.filter { it.name == name } }
            .map { it.sortedWith(compareBy({ c -> c.dayOfWeek }, { c -> c.startSection })) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 周次网格的上限，跟课表页共用同一份推导结果。 */
    public val totalWeeks: StateFlow<Int> = state
        .map { it.totalWeeks }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 20)

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 当前勾选、准备被 [applyTemplate] 批量套用的行。 */
    public val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    public fun toggleSelected(id: Long) {
        _selectedIds.value = if (id in _selectedIds.value) _selectedIds.value - id else _selectedIds.value + id
    }

    public fun selectAll() {
        _selectedIds.value = occurrences.value.map { it.id }.toSet()
    }

    public fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * 保存对**这一行**的编辑。名字/学期/来源/id 恒回填自本地找到的那一行——
     * 名字只能通过 [renameAll] 整门课一起改，其它三个字段是这一行在数据库里的身份，
     * 不该被随便传进来的 [edited] 覆盖。
     */
    public fun editOccurrence(id: Long, edited: Course) {
        val original = occurrences.value.firstOrNull { it.id == id } ?: return
        val safe = edited.copy(id = original.id, term = original.term, source = original.source, name = original.name)
        viewModelScope.launch { applyEdit(original, safe) }
    }

    /**
     * 把 [template] 里"开着开关"的那几个字段套用到当前勾选的每一行，其余字段
     * 保留每一行自己原来的值——不会出现"改时间顺带把各不相同的老师都拍成一个值"
     * 这种事，见类 KDoc。全空模板、或者没勾任何行时是无操作。
     */
    public fun applyTemplate(template: OccurrenceTemplate) {
        val ids = _selectedIds.value
        if (ids.isEmpty() || template.isEmpty) return
        val targets = occurrences.value.filter { it.id in ids }
        viewModelScope.launch {
            targets.forEach { course ->
                val edited = course.copy(
                    teacher = template.teacher ?: course.teacher,
                    classroom = template.classroom ?: course.classroom,
                    colorKey = template.colorKey ?: course.colorKey,
                    weeks = template.weeks ?: course.weeks,
                    dayOfWeek = template.dayOfWeek ?: course.dayOfWeek,
                    startSection = template.startSection ?: course.startSection,
                    sectionCount = template.sectionCount ?: course.sectionCount,
                    startMinute = when (template.useRealTime) {
                        true -> template.startMinute ?: course.startMinute
                        false -> -1
                        null -> course.startMinute
                    },
                    endMinute = when (template.useRealTime) {
                        true -> template.endMinute ?: course.endMinute
                        false -> -1
                        null -> course.endMinute
                    },
                )
                applyEdit(course, edited)
            }
            clearSelection()
        }
    }

    /**
     * 把这门课在本学期出现过的**每一行**都改成 [newName]。
     * 故意不接受"只改选中的几行"——半改半不改会让同一门课同时存在两个名字，
     * 下次按名字聚合就找不全了（见类 KDoc）。
     */
    public fun renameAll(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == _courseName.value) return
        val targets = occurrences.value
        viewModelScope.launch {
            targets.forEach { course -> applyEdit(course, course.copy(name = trimmed)) }
            _courseName.value = trimmed
        }
    }

    /** SCHOOL 行落补丁，OVERRIDE/CUSTOM 行直接 upsert —— 理由见类 KDoc。 */
    private suspend fun applyEdit(original: Course, edited: Course) {
        if (original.source == CourseSource.SCHOOL) {
            repository.saveSchoolOverride(original, edited, OverrideScope.WEEK_RANGE, force = true)
        } else {
            repository.updateCustomCourse(edited, force = true)
        }
    }

    /**
     * 删除一行。SCHOOL 行走这里等于"我不想再看到这门课的这一段"，OVERRIDE 行走这里
     * 是**删补丁本身**（周次不会退回教务原始状态，想还原用 [restore]）。
     */
    public fun delete(id: Long) {
        viewModelScope.launch { repository.deleteCourse(id) }
    }

    /** 还原一条 OVERRIDE 补丁：删补丁，把接管的周次退回教务原始版本。 */
    public fun restore(id: Long) {
        viewModelScope.launch { repository.restoreOriginal(id) }
    }

    /**
     * 新增一次上课时间，作为这门课的新 [CourseSource.CUSTOM] 行。[days] 允许多选，
     * 一次建出好几个星期的同款排课——"先建成一样的，后面有差异再单独用简单编辑改"，
     * 见调查结论对"新建课程能不能批量"的取舍。
     */
    public fun addOccurrences(
        days: Set<Int>,
        startSection: Int,
        sectionCount: Int,
        weeks: Set<Int>,
        classroom: String,
        teacher: String,
        colorKey: String,
    ) {
        val term = state.value.term ?: return
        if (days.isEmpty() || weeks.isEmpty()) return
        val name = _courseName.value
        viewModelScope.launch {
            days.forEach { day ->
                repository.addCustomCourse(
                    Course(
                        term = term,
                        name = name,
                        teacher = teacher,
                        classroom = classroom,
                        dayOfWeek = day,
                        startSection = startSection,
                        sectionCount = sectionCount,
                        weeks = weeks,
                        source = CourseSource.CUSTOM,
                        colorKey = colorKey,
                    ),
                    force = true,
                )
            }
        }
    }

    public companion object {

        /** 手写工厂：从 [AppContainer] 取依赖，理由同 [ScheduleViewModel.factory]。 */
        public fun factory(container: AppContainer, courseName: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AdvancedCourseEditViewModel(repository = container.scheduleRepository, initialCourseName = courseName)
            }
        }
    }
}
