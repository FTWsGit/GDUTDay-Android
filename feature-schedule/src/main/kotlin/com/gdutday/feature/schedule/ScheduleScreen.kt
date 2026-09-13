package com.gdutday.feature.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdutday.core.datastore.ScheduleView
import com.gdutday.core.model.Campus
import com.gdutday.core.model.SyncSourceType
import com.gdutday.core.ui.BannerTone
import com.gdutday.core.ui.EmptyState
import com.gdutday.core.ui.StatusBanner
import com.gdutday.data.repository.AppContainer
import java.time.LocalDate

/**
 * 课表主页面。App 的首屏。
 *
 * ## ⚠ 契约文件：签名被 NavHost 直接调用
 *
 * ## 数据来源：只收集一个 Flow
 *
 * [com.gdutday.data.repository.ScheduleRepository.observeScheduleUiState] 已经把课程、考试、
 * 学期历、作息表、配色全部算好了（见 `ScheduleUiState` 的字段注释）。
 * [ScheduleViewModel] 只转发它、持有交互状态，**不做任何业务计算**。
 *
 * KDoc 里列的 12 条要求在本文件及其协作者里的落点：
 *
 * 1. 首屏不等同步：只有"无任何本地数据且正在同步"才显示居中 loading，
 *    否则立即渲染；同步只驱动顶部 [LinearProgressIndicator] 和
 *    [PullToRefreshBox] 的刷新指示器。
 * 2. 网格布局：见 [WeekGridView]（Canvas 背景 + Layout 色块，按分钟定位）。
 * 3. 已上完置灰：[CourseBlockItem] 里叠 `finishedScrim`，受 `dimFinishedCourses` 控制。
 * 4. 正在上课高亮：同文件叠 `ongoingBorder`。
 * 5. 透明度：`courseBlockAlpha` 只作用在色块背景，不影响文字。
 * 6. 字体色：`CourseTextColor.AUTO` 走 core-ui 的 WCAG 相对亮度纯函数。
 * 7. 周次选择器：网格上横向拖动切换周次（自定义 `detectHorizontalDragGestures`），
 *    非本周时顶栏出现"回到本周"。
 * 8. 开学日期提醒：`semesterStartSource == GUESSED` 时显示可关闭横幅，点击打开日期选择器。
 * 9. 同步失败不清空：`errorMessage` 弹 Snackbar，网格数据原样保留。
 * 10. 课程详情：[CourseDetailSheet]。
 * 11. 下拉刷新：触发 `SyncScheduler.requestImmediateSync(expedited = true)`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ScheduleViewModel = viewModel(factory = ScheduleViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selectedBlock by viewModel.selectedBlock.collectAsStateWithLifecycle()
    val guessedDismissed by viewModel.guessedBannerDismissed.collectAsStateWithLifecycle()
    val campusDismissed by viewModel.campusBannerDismissed.collectAsStateWithLifecycle()
    val syncSourceDismissed by viewModel.syncSourceBannerDismissed.collectAsStateWithLifecycle()
    val dayOffset by viewModel.selectedDayOffset.collectAsStateWithLifecycle()
    val isLoggedIn by container.authRepository.isLoggedIn
        .collectAsStateWithLifecycle(initialValue = true)

    val snackbarHostState = remember { SnackbarHostState() }
    var showCalibrateDialog by remember { mutableStateOf(false) }
    var showSyncSourceDialog by remember { mutableStateOf(false) }
    var conflictListBlocks by remember { mutableStateOf<List<com.gdutday.core.common.CourseBlock>?>(null) }
    val addCourseSheetVisible by viewModel.addCourseSheetVisible.collectAsStateWithLifecycle()
    val addCourseForm by viewModel.addCourseForm.collectAsStateWithLifecycle()

    // 新增课程始终允许冲突（force = true），这里只提示一次语义，不做拦截。
    val conflictToast = stringResource(R.string.schedule_add_conflict_toast)
    LaunchedEffect(addCourseSheetVisible) {
        if (addCourseSheetVisible) {
            snackbarHostState.showSnackbar(conflictToast)
        }
    }

    // 同步失败的提示。state.errorMessage 在下次同步前一直存在，
    // 但这里以它为 key，只在值变化时弹一次，不会反复打扰。
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    // 背景图需要在最底层、铺满整个页面（含顶栏后面），所以包在最外层：
    // Scaffold / 顶栏都改透明，否则不透明底色会把图完全盖住。
    Box(Modifier.fillMaxSize()) {
        ScheduleBackground(
            uri = settings.backgroundImageUri,
            blurDp = settings.backgroundBlurDp,
        )
        Scaffold(
            modifier = modifier,
            containerColor = Color.Transparent,
            topBar = {
                ScheduleTopBar(
                    state = state,
                    settings = settings,
                    containerColor = Color.Transparent,
                    onSelectTerm = viewModel::selectTerm,
                    onBackToCurrentWeek = viewModel::backToCurrentWeek,
                    onToggleView = {
                        viewModel.setScheduleView(
                            if (settings.scheduleView == ScheduleView.WEEK) ScheduleView.DAY else ScheduleView.WEEK,
                        )
                    },
                    onSync = viewModel::refresh,
                    onAddCourse = viewModel::showAddCourseSheet,
                    onOpenSyncSource = { showSyncSourceDialog = true },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = viewModel::showAddCourseSheet,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.schedule_menu_add_course)) },
                )
            },
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = state.isSyncing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 班级课表同步源：提示当前显示的不是本人课表，避免误认为是自己的课。
                    if (settings.syncSourceType == SyncSourceType.CLASS_SCHEDULE &&
                        !syncSourceDismissed
                    ) {
                        StatusBanner(
                            message = stringResource(
                                R.string.schedule_sync_source_current_class,
                                settings.classScheduleClassName.ifBlank { settings.classScheduleBjdm },
                            ),
                            tone = BannerTone.INFO,
                            actionLabel = stringResource(R.string.schedule_menu_sync_source),
                            onAction = { showSyncSourceDialog = true },
                            onDismiss = viewModel::dismissSyncSourceBanner,
                        )
                    }
                    // 开学日期是推测的：这是全屏里最该被看见的提示，日期错了周次全错。
                    if (state.semesterStartSource.needsUserConfirmation && !guessedDismissed) {
                        StatusBanner(
                            message = stringResource(R.string.schedule_semester_start_guessed),
                            tone = BannerTone.WARNING,
                            actionLabel = stringResource(R.string.schedule_calibrate),
                            onAction = { showCalibrateDialog = true },
                            onDismiss = viewModel::dismissGuessedBanner,
                        )
                    }
                    // 校区未设置：作息表会回退到大学城，时间可能不准。
                    if (state.campus == Campus.UNKNOWN && !campusDismissed) {
                        StatusBanner(
                            message = stringResource(R.string.schedule_campus_unknown),
                            tone = BannerTone.INFO,
                            actionLabel = stringResource(R.string.schedule_campus_unknown_action),
                            onAction = onOpenSettings,
                            onDismiss = viewModel::dismissCampusBanner,
                        )
                    }
                    if (state.isSyncing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val grid = state.grid
                        when {
                            state.isInitialLoad && grid == null && state.isSyncing -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }

                            grid == null && !state.hasAnyData && !isLoggedIn -> {
                                CenteredEmpty(
                                    title = stringResource(R.string.schedule_not_logged_in_title),
                                    subtitle = stringResource(R.string.schedule_not_logged_in_subtitle),
                                )
                            }

                            grid == null -> {
                                CenteredEmpty(
                                    title = stringResource(R.string.schedule_no_data_title),
                                    subtitle = stringResource(R.string.schedule_no_data_subtitle),
                                )
                            }

                            settings.scheduleView == ScheduleView.DAY -> {
                                val selectedDate = state.today.plusDays(dayOffset.toLong())
                                // 稳定引用：onSwipeDay 是 pointerInput 的 key，
                                // 每次重组都换新引用会让正在进行的滑动手势被重启。
                                val onSwipeDay = remember(viewModel) { viewModel::selectDay }
                                // 切天无淡入淡出：保留轻微横向滑动，方向按新旧日期的先后决定。
                                AnimatedContent(
                                    targetState = selectedDate,
                                    transitionSpec = {
                                        val direction = if (targetState > initialState) 1 else -1
                                        slideInHorizontally { full -> full * direction } togetherWith
                                            slideOutHorizontally { full -> -full * direction }
                                    },
                                    label = "daySwitch",
                                ) { date ->
                                    DayScheduleView(
                                        blocks = remember(state.grid, date) {
                                            state.grid?.days?.find { it.date == date }?.blocks.orEmpty()
                                        },
                                        settings = settings,
                                        date = date,
                                        onBlockClick = viewModel::openBlock,
                                        onSwipeDay = onSwipeDay,
                                    )
                                }
                            }

                            else -> {
                                WeekGridView(
                                    grid = grid,
                                    settings = settings,
                                    today = state.today,
                                    isCurrentWeek = state.isViewingCurrentWeek,
                                    onBlockClick = viewModel::openBlock,
                                    onSwipeWeek = viewModel::selectWeek,
                                    prevWeekGrid = state.prevWeekGrid,
                                    nextWeekGrid = state.nextWeekGrid,
                                    onOpenConflictList = { conflictListBlocks = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 详情弹窗里的色块颜色要跟随改色实时刷新，所以用最新网格里的同一条目替换快照。
    val gridBlock = remember(selectedBlock, state.grid) { resolveLatestBlock(selectedBlock, state) }
    if (gridBlock != null) {
        CourseDetailSheet(
            block = gridBlock,
            settings = settings,
            onDismiss = viewModel::closeBlockDetail,
            onPickColor = { key -> viewModel.setCourseColor(gridBlock.course.name, key) },
            onDelete = { viewModel.deleteCourse(gridBlock.course.id) },
            onEdit = { viewModel.openEdit(gridBlock.course) },
        )
    }

    // 编辑弹窗：优先于详情弹窗（点"编辑"时详情已关，两者不会同时出现）。
    val editingCourse by viewModel.editingCourse.collectAsStateWithLifecycle()
    editingCourse?.let { editing ->
        CourseEditSheet(
            course = editing,
            currentWeek = state.selectedWeek,
            onDismiss = viewModel::closeEdit,
            onSave = { edited, scope -> viewModel.saveEdit(edited, scope) },
        )
    }

    // 新增课程弹窗。保存始终 force = true，冲突由网格降级呈现。
    if (addCourseSheetVisible) {
        AddCourseSheet(
            form = addCourseForm,
            currentWeek = state.selectedWeek,
            totalWeeks = state.totalWeeks,
            // Sheet 每次回调传入完整的新表单快照，直接整体替换。
            onFormChange = { newForm -> viewModel.updateAddCourseForm { newForm } },
            onDismiss = viewModel::dismissAddCourseSheet,
            onSave = { viewModel.addCourse() },
        )
    }

    // ≥3 门重叠时点 `+N` 角标弹出的完整列表；点单条进入普通课程详情。
    conflictListBlocks?.let { blocks ->
        ConflictCourseListSheet(
            blocks = blocks,
            onDismiss = { conflictListBlocks = null },
            onPick = viewModel::openBlock,
        )
    }

    // 同步源切换：选班级并确认后由 ViewModel 触发一次加急同步。
    if (showSyncSourceDialog) {
        SyncSourceDialog(
            settings = settings,
            onDismiss = { showSyncSourceDialog = false },
            onSelectPersonal = {
                showSyncSourceDialog = false
                viewModel.switchSyncSourceType(SyncSourceType.PERSONAL)
            },
            onSelectClass = { bjdm, className ->
                showSyncSourceDialog = false
                viewModel.setClassSchedule(bjdm, className)
            },
        )
    }

    if (showCalibrateDialog) {
        val term = state.term
        if (term != null) {
            val initial = state.calendar?.semesterStart ?: state.today
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = initial.toEpochDay() * 86_400_000L,
            )
            DatePickerDialog(
                onDismissRequest = { showCalibrateDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                viewModel.setSemesterStart(term, LocalDate.ofEpochDay(millis / 86_400_000L))
                            }
                            showCalibrateDialog = false
                        },
                    ) { Text(stringResource(R.string.schedule_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCalibrateDialog = false }) {
                        Text(stringResource(R.string.schedule_cancel))
                    }
                },
            ) {
                DatePicker(state = pickerState)
            }
        }
    }
}

@Composable
private fun CenteredEmpty(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = title, subtitle = subtitle)
    }
}

/**
 * 在最新网格里找回用户点开的那一条，让详情弹窗的配色/内容随数据更新。
 *
 * 只做一次扁平查找；找不到（例如课程被删）时退回原快照。
 */
private fun resolveLatestBlock(
    selected: com.gdutday.core.common.CourseBlock?,
    state: com.gdutday.data.repository.ScheduleUiState,
): com.gdutday.core.common.CourseBlock? {
    if (selected == null) return null
    val all = state.grid?.days.orEmpty().flatMap { it.blocks }
    return all.firstOrNull {
        it.course.naturalKey == selected.course.naturalKey && it.startMinute == selected.startMinute
    } ?: selected
}
