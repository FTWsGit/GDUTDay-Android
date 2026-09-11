package com.gdutday.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.common.CourseColors
import com.gdutday.core.datastore.CourseTextColor
import com.gdutday.core.datastore.ScheduleView
import com.gdutday.core.datastore.StoredCredentials
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.model.Campus
import com.gdutday.core.model.ScheduleFetchStrategy
import com.gdutday.core.ui.BannerTone
import com.gdutday.core.ui.StatusBanner
import com.gdutday.core.ui.toComposeColor
import com.gdutday.data.repository.AppContainer
import java.time.LocalDate

/**
 * 设置页。
 *
 * ## ⚠ 契约文件：签名被 `app` 的 NavHost 直接调用，不要改
 *
 * 按 [UserSettings] 的字段分成六个分组，用 [LazyColumn] 顺序渲染。
 * [diagnostics] 是单独的一屏（[DiagnosticsScreen]），避免几十个设置项和一大段
 * 文本挤在一起。
 *
 * ## 几条不可动摇的规则
 *
 * - `semesterStartSource == GUESSED` 时，开学日期校准置顶 + WARNING 横幅：
 *   日期错了整个周次显示全错。
 * - 自定义作息保存前必须过 [SettingsLogic.validateCustomTimetable]，
 *   失败只提示不落盘。
 * - 透明度受 [UserSettings.ALPHA_RANGE] 钳制。
 * - 背景图存 URI 并调用 `takePersistableUriPermission`。
 * - "记住密码"关闭时真的调用 [CredentialStore.clear]。
 * - 诊断信息、退出登录均不含 cookie / 密码 / 完整 token。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onLogout: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scheduleState by viewModel.scheduleState.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showSemesterStartPicker by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmResetColors by remember { mutableStateOf(false) }
    var confirmClearCourses by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    // 退出登录后 isLoggedIn 变 false，交给 NavHost 跳转。
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) onLogout()
    }

    val eventText: String? = when (val e = event) {
        null -> null
        SettingsEvent.TimetableSaved -> stringResource(R.string.settings_timetable_saved)
        is SettingsEvent.TimetableInvalid -> when (e.reason) {
            TimetableInvalidReason.WRONG_SIZE -> stringResource(R.string.settings_timetable_invalid_size)
            TimetableInvalidReason.BAD_FORMAT -> stringResource(R.string.settings_timetable_invalid_format)
            TimetableInvalidReason.REVERSED -> stringResource(R.string.settings_timetable_invalid_reversed)
        }
        SettingsEvent.ColorsReset -> stringResource(R.string.settings_colors_reset)
        SettingsEvent.CustomCoursesCleared -> stringResource(R.string.settings_custom_cleared)
        SettingsEvent.AllDataCleared -> stringResource(R.string.settings_all_cleared)
        is SettingsEvent.Error -> e.message
    }
    LaunchedEffect(event) {
        eventText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeEvent()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onBackgroundPicked(uri)
    }

    if (showDiagnostics) {
        DiagnosticsScreen(
            diagnostics = viewModel.diagnostics(),
            onBack = { showDiagnostics = false },
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            // ---------------------------------------------------------- 置顶警告
            // 必须等 term 真正加载出来再判断：ScheduleUiState 的默认来源就是 GUESSED，
            // 不加这道闸门会在数据到达前闪一下"推测日期"的警告。
            if (scheduleState.term != null && scheduleState.semesterStartSource == SemesterStartSource.GUESSED) {
                item(key = "guessed_banner") {
                    StatusBanner(
                        message = stringResource(R.string.settings_semester_start_guessed),
                        tone = BannerTone.WARNING,
                        actionLabel = stringResource(R.string.settings_calibrate),
                        onAction = { showSemesterStartPicker = true },
                    )
                }
            }

            // ---------------------------------------------------------- 学期与校区
            item(key = "header_term") { SectionHeader(stringResource(R.string.settings_section_term_campus)) }

            item(key = "campus") {
                CampusSetting(
                    selected = settings.campus,
                    onSelect = viewModel::setCampus,
                )
            }

            if (settings.campus == Campus.PANYU) {
                item(key = "panyu_warning") {
                    StatusBanner(
                        message = stringResource(R.string.settings_campus_panyu_warning),
                        tone = BannerTone.INFO,
                    )
                }
            }

            item(key = "semester_start") {
                SemesterStartRow(
                    date = scheduleState.calendar?.semesterStart,
                    source = scheduleState.semesterStartSource,
                    enabled = true, // 校准日期永远可用：即使从未同步过，用户也应能手动设置开学日期。
                    onCalibrate = { showSemesterStartPicker = true },
                )
            }

            // ---------------------------------------------------------- 课表外观
            item(key = "header_appearance") { SectionHeader(stringResource(R.string.settings_section_appearance)) }

            item(key = "view_mode") {
                ViewModeSetting(
                    selected = settings.scheduleView,
                    onSelect = viewModel::setScheduleView,
                )
            }

            item(key = "alpha") {
                AlphaSetting(
                    alpha = settings.courseBlockAlpha,
                    onAlphaChange = viewModel::setCourseBlockAlpha,
                )
            }

            item(key = "dim_finished") {
                SwitchRow(
                    title = stringResource(R.string.settings_dim_finished),
                    checked = settings.dimFinishedCourses,
                    onCheckedChange = viewModel::setDimFinishedCourses,
                )
            }

            item(key = "text_color") {
                TextColorSetting(
                    selected = settings.courseTextColor,
                    onSelect = viewModel::setCourseTextColor,
                )
            }

            item(key = "background") {
                BackgroundSetting(
                    uri = settings.backgroundImageUri,
                    blurDp = settings.backgroundBlurDp,
                    onPick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onClear = viewModel::clearBackgroundImage,
                    onBlurChange = viewModel::setBackgroundBlur,
                )
            }

            item(key = "show_teacher") {
                SwitchRow(
                    title = stringResource(R.string.settings_show_teacher),
                    checked = settings.showTeacher,
                    onCheckedChange = viewModel::setShowTeacher,
                )
            }
            item(key = "show_classroom") {
                SwitchRow(
                    title = stringResource(R.string.settings_show_classroom),
                    checked = settings.showClassroom,
                    onCheckedChange = viewModel::setShowClassroom,
                )
            }
            item(key = "show_extra") {
                SwitchRow(
                    title = stringResource(R.string.settings_show_extra_sections),
                    checked = settings.showExtraSections,
                    onCheckedChange = viewModel::setShowExtraSections,
                )
            }
            item(key = "show_weekend") {
                SwitchRow(
                    title = stringResource(R.string.settings_show_weekend),
                    checked = settings.showWeekend,
                    onCheckedChange = viewModel::setShowWeekend,
                )
            }

            // ---------------------------------------------------------- 作息表
            item(key = "header_timetable") { SectionHeader(stringResource(R.string.settings_section_timetable)) }

            item(key = "custom_toggle") {
                SwitchRow(
                    title = stringResource(R.string.settings_custom_timetable),
                    subtitle = stringResource(R.string.settings_custom_timetable_hint),
                    checked = settings.customTimetableEnabled,
                    onCheckedChange = viewModel::setCustomTimetableEnabled,
                )
            }

            if (settings.customTimetableEnabled) {
                item(key = "custom_editor") {
                    TimetableEditor(
                        campus = settings.campus,
                        current = settings.customTimetable.ifEmpty {
                            SettingsLogic.defaultTimetable(settings.campus)
                        },
                        onSave = viewModel::saveCustomTimetable,
                        onRestore = viewModel::restoreDefaultTimetable,
                    )
                }
            }

            // ---------------------------------------------------------- 数据
            item(key = "header_data") { SectionHeader(stringResource(R.string.settings_section_data)) }

            item(key = "fetch_strategy") {
                FetchStrategySetting(
                    selected = settings.fetchStrategy,
                    onSelect = viewModel::setFetchStrategy,
                )
            }

            item(key = "auto_sync") {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_sync),
                    subtitle = stringResource(R.string.settings_auto_sync_hint),
                    checked = settings.autoSyncOnLaunch,
                    onCheckedChange = viewModel::setAutoSyncOnLaunch,
                )
            }

            item(key = "auto_sync_interval") {
                SyncIntervalSetting(
                    selected = settings.autoSyncIntervalHours,
                    enabled = settings.autoSyncOnLaunch,
                    onSelect = viewModel::setAutoSyncIntervalHours,
                )
            }

            item(key = "manual_sync") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_manual_sync)) },
                    supportingContent = {
                        Text(scheduleState.lastSync?.relativeTime() ?: stringResource(R.string.settings_never_synced))
                    },
                    trailingContent = {
                        TextButton(onClick = viewModel::requestSync, enabled = !isSyncing) {
                            Text(
                                if (isSyncing) stringResource(R.string.settings_syncing)
                                else stringResource(R.string.settings_sync_now),
                            )
                        }
                    },
                )
            }

            item(key = "reset_colors") {
                ActionRow(
                    title = stringResource(R.string.settings_reset_colors),
                    subtitle = stringResource(R.string.settings_reset_colors_hint),
                    onClick = { confirmResetColors = true },
                )
            }
            item(key = "clear_custom") {
                ActionRow(
                    title = stringResource(R.string.settings_clear_custom_courses),
                    subtitle = stringResource(R.string.settings_clear_custom_courses_hint),
                    onClick = { confirmClearCourses = true },
                )
            }
            item(key = "clear_all") {
                ActionRow(
                    title = stringResource(R.string.settings_clear_all),
                    subtitle = stringResource(R.string.settings_clear_all_hint),
                    onClick = { confirmClearAll = true },
                    isDestructive = true,
                )
            }

            // ---------------------------------------------------------- 隐私
            item(key = "header_privacy") { SectionHeader(stringResource(R.string.settings_section_privacy)) }

            item(key = "privacy_blur") {
                SwitchRow(
                    title = stringResource(R.string.settings_privacy_blur),
                    checked = settings.privacyBlurEnabled,
                    onCheckedChange = viewModel::setPrivacyBlur,
                )
            }
            item(key = "privacy_blur_widget") {
                SwitchRow(
                    title = stringResource(R.string.settings_privacy_blur_widget),
                    checked = settings.privacyBlurInWidget,
                    enabled = settings.privacyBlurEnabled,
                    onCheckedChange = viewModel::setPrivacyBlurInWidget,
                )
            }
            item(key = "remember_password") {
                RememberPasswordRow(
                    credentials = credentials,
                    onClear = viewModel::clearRememberedPassword,
                )
            }

            // ---------------------------------------------------------- 关于 / 诊断
            item(key = "header_about") { SectionHeader(stringResource(R.string.settings_section_about)) }

            item(key = "version") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text(container.applicationContext.let { appVersionName(it) }) },
                )
            }
            item(key = "licenses") {
                ActionRow(
                    title = stringResource(R.string.settings_licenses),
                    onClick = { showLicenses = true },
                )
            }
            item(key = "diagnostics") {
                ActionRow(
                    title = stringResource(R.string.settings_diagnostics),
                    subtitle = stringResource(R.string.settings_diagnostics_hint),
                    onClick = { showDiagnostics = true },
                )
            }
            item(key = "logout") {
                ActionRow(
                    title = stringResource(R.string.settings_logout),
                    onClick = { confirmLogout = true },
                    isDestructive = true,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 各种对话框
    if (showSemesterStartPicker) {
        val initial = scheduleState.calendar?.semesterStart ?: LocalDate.now()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.toEpochDay() * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showSemesterStartPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            viewModel.setSemesterStart(LocalDate.ofEpochDay(it / 86_400_000L))
                        }
                        showSemesterStartPicker = false
                    },
                ) { Text(stringResource(R.string.settings_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSemesterStartPicker = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.settings_confirm)) }
            },
            title = { Text(stringResource(R.string.settings_licenses)) },
            text = { Text(stringResource(R.string.settings_licenses_body)) },
        )
    }

    ConfirmDialog(
        visible = confirmResetColors,
        title = stringResource(R.string.settings_reset_colors),
        message = stringResource(R.string.settings_reset_colors_confirm),
        onConfirm = { viewModel.resetColors(); confirmResetColors = false },
        onDismiss = { confirmResetColors = false },
    )
    ConfirmDialog(
        visible = confirmClearCourses,
        title = stringResource(R.string.settings_clear_custom_courses),
        message = stringResource(R.string.settings_clear_custom_courses_confirm),
        onConfirm = { viewModel.clearCustomCourses(); confirmClearCourses = false },
        onDismiss = { confirmClearCourses = false },
    )
    ConfirmDialog(
        visible = confirmClearAll,
        title = stringResource(R.string.settings_clear_all),
        message = stringResource(R.string.settings_clear_all_confirm),
        onConfirm = { viewModel.logout(clearLocalData = true); confirmClearAll = false },
        onDismiss = { confirmClearAll = false },
    )
    LogoutDialog(
        visible = confirmLogout,
        onConfirm = { clearLocalData ->
            viewModel.logout(clearLocalData)
            confirmLogout = false
        },
        onDismiss = { confirmLogout = false },
    )
}

// ============================================================================
// 分组与通用行
// ============================================================================

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

@Composable
private fun ActionRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    isDestructive: Boolean = false,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = if (isDestructive) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        title = { Text(title) },
        text = { Text(message) },
    )
}

/** 退出登录确认框。带一个"同时清空本地数据"复选框。 */
@Composable
private fun LogoutDialog(
    visible: Boolean,
    onConfirm: (clearLocalData: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var clearLocal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(clearLocal) }) { Text(stringResource(R.string.settings_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        title = { Text(stringResource(R.string.settings_logout)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_logout_confirm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = clearLocal,
                        onCheckedChange = { clearLocal = it },
                    )
                    Text(stringResource(R.string.settings_logout_clear_data))
                }
            }
        },
    )
}

// ============================================================================
// 各设置分组
// ============================================================================

@Composable
private fun ChoiceChips(
    label: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, (text, selected) ->
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(index) },
                    label = { Text(text) },
                )
            }
        }
    }
}

@Composable
private fun CampusSetting(selected: Campus, onSelect: (Campus) -> Unit) {
    // 注意：不能把 stringResource 写进 buildList 的 lambda —— 那不是 @Composable 上下文。
    val autoLabel = stringResource(R.string.settings_campus_auto)
    val campuses = Campus.entries.filter { it != Campus.UNKNOWN }
    val options = buildList {
        add(autoLabel to (selected == Campus.UNKNOWN))
        campuses.forEach { add(it.displayName to (selected == it)) }
    }
    ChoiceChips(
        label = stringResource(R.string.settings_campus),
        options = options,
        onSelect = { index ->
            onSelect(if (index == 0) Campus.UNKNOWN else campuses[index - 1])
        },
    )
}

@Composable
private fun SemesterStartRow(
    date: LocalDate?,
    source: SemesterStartSource,
    enabled: Boolean,
    onCalibrate: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_semester_start)) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.settings_semester_start_value,
                    date?.let(::formatDate) ?: "--",
                    semesterStartSourceLabel(source),
                ),
            )
        },
        trailingContent = {
            TextButton(onClick = onCalibrate, enabled = enabled) {
                Text(stringResource(R.string.settings_calibrate))
            }
        },
    )
}

@Composable
private fun semesterStartSourceLabel(source: SemesterStartSource): String = when (source) {
    SemesterStartSource.USER -> stringResource(R.string.settings_source_user)
    SemesterStartSource.DERIVED -> stringResource(R.string.settings_source_derived)
    SemesterStartSource.KNOWN_TABLE -> stringResource(R.string.settings_source_known)
    SemesterStartSource.GUESSED -> stringResource(R.string.settings_source_guessed)
}

@Composable
private fun ViewModeSetting(selected: ScheduleView, onSelect: (ScheduleView) -> Unit) {
    val options = ScheduleView.entries.map { it.displayName to (it == selected) }
    ChoiceChips(
        label = stringResource(R.string.settings_view_mode),
        options = options,
        onSelect = { onSelect(ScheduleView.entries[it]) },
    )
}

@Composable
private fun TextColorSetting(selected: CourseTextColor, onSelect: (CourseTextColor) -> Unit) {
    val options = CourseTextColor.entries.map { it.displayName to (it == selected) }
    ChoiceChips(
        label = stringResource(R.string.settings_text_color),
        options = options,
        onSelect = { onSelect(CourseTextColor.entries[it]) },
    )
}

@Composable
private fun AlphaSetting(alpha: Float, onAlphaChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.settings_alpha), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = alpha,
                onValueChange = onAlphaChange,
                valueRange = UserSettings.ALPHA_RANGE,
                modifier = Modifier.weight(1f),
            )
            // 实时预览：用一个默认调色板色块展示当前透明度。
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(width = 56.dp, height = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CourseColors.DEFAULT.toComposeColor().copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun BackgroundSetting(
    uri: String?,
    blurDp: Int,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onBlurChange: (Int) -> Unit,
) {
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_background_image)) },
            supportingContent = {
                Text(
                    text = uri ?: stringResource(R.string.settings_background_none),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row {
                    TextButton(onClick = onPick) { Text(stringResource(R.string.settings_background_pick)) }
                    if (uri != null) {
                        TextButton(onClick = onClear) { Text(stringResource(R.string.settings_background_clear)) }
                    }
                }
            },
        )
        if (uri != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_background_blur, blurDp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = blurDp.toFloat(),
                    onValueChange = { onBlurChange(it.toInt()) },
                    valueRange = 0f..25f,
                )
            }
        }
    }
}

@Composable
private fun FetchStrategySetting(selected: ScheduleFetchStrategy, onSelect: (ScheduleFetchStrategy) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.settings_fetch_strategy),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ScheduleFetchStrategy.entries.forEach { strategy ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(strategy) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = strategy == selected, onClick = { onSelect(strategy) })
                Column(Modifier.padding(start = 8.dp)) {
                    Text(strategy.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = strategy.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncIntervalSetting(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    if (!enabled) return
    // 取到带 %1$d 占位符的模板再自行格式化，避免在 map lambda 里调用 @Composable。
    val template = stringResource(R.string.settings_interval_hours)
    val options = SettingsLogic.SYNC_INTERVAL_OPTIONS.map { hours ->
        String.format(template, hours) to (hours == selected)
    }
    ChoiceChips(
        label = stringResource(R.string.settings_auto_sync_interval),
        options = options,
        onSelect = { onSelect(SettingsLogic.SYNC_INTERVAL_OPTIONS[it]) },
    )
}

@Composable
private fun RememberPasswordRow(credentials: StoredCredentials?, onClear: () -> Unit) {
    val subtitle = if (credentials != null) {
        stringResource(R.string.settings_remember_password_on, credentials.username)
    } else {
        stringResource(R.string.settings_remember_password_off)
    }
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_remember_password)) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Switch(
                    checked = credentials != null,
                    // 从设置页无法"凭空"记住密码（需要用户在登录页输入）。
                    // 这里的开关只承担"关闭并擦除"的职责。
                    enabled = credentials != null,
                    onCheckedChange = { checked -> if (!checked) onClear() },
                )
            },
        )
        if (credentials != null) {
            Text(
                text = stringResource(R.string.settings_remember_password_risk),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 12 节起止时间编辑器。
 *
 * 草稿保存在本地 mutableStateList，只有点"保存"才会走校验并落盘；
 * "恢复默认"直接关闭自定义。时间选择器用 Material3 [TimePicker]。
 */
@Composable
private fun TimetableEditor(
    campus: Campus,
    current: List<String>,
    onSave: (List<String>) -> Unit,
    onRestore: () -> Unit,
) {
    val draft = remember(current, campus) {
        mutableStateListOf<String>().apply {
            // 旧版存量数据只有 12 节（24 项）：缺失的 13/14 节用校区默认值补齐，避免显示 "--:--"。
            val padded = if (current.size == 24) current + SettingsLogic.defaultTimetable(campus).drop(24) else current
            addAll(padded.take(CampusTimetable.SECTIONS_PER_DAY * 2))
        }
    }
    // 点击某一节时记录 (节次下标, 起点/终点)，用于弹时间选择器。
    var picking by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        repeat(CampusTimetable.SECTIONS_PER_DAY) { section ->
            val startIndex = section * 2
            val endIndex = startIndex + 1
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_section_number, section + 1),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(56.dp),
                )
                OutlinedButton(
                    onClick = { picking = startIndex to true },
                    modifier = Modifier.weight(1f),
                ) { Text(draft.getOrElse(startIndex) { "--:--" }) }
                Text(" - ", modifier = Modifier.padding(horizontal = 6.dp))
                OutlinedButton(
                    onClick = { picking = endIndex to false },
                    modifier = Modifier.weight(1f),
                ) { Text(draft.getOrElse(endIndex) { "--:--" }) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onRestore) { Text(stringResource(R.string.settings_timetable_restore)) }
            TextButton(onClick = { onSave(draft.toList()) }) { Text(stringResource(R.string.settings_timetable_save)) }
        }
    }

    picking?.let { (index, _) ->
        val initial = runCatching {
            java.time.LocalTime.parse(draft.getOrElse(index) { "08:00" })
        }.getOrDefault(java.time.LocalTime.of(8, 0))
        TimePickerDialog(
            initial = initial,
            onConfirm = { time ->
                if (index in draft.indices) draft[index] = SettingsLogic.formatTime(time)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** 自绘的时间选择 Dialog。AlertDialog 容不下 TimePicker，用 [androidx.compose.ui.window.Dialog]。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: java.time.LocalTime,
    onConfirm: (java.time.LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
                    TextButton(onClick = { onConfirm(java.time.LocalTime.of(state.hour, state.minute)) }) {
                        Text(stringResource(R.string.settings_confirm))
                    }
                }
            }
        }
    }
}

// ============================================================================
// 小工具
// ============================================================================

private fun formatDate(date: LocalDate): String =
    "${date.year}-${date.monthValue.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"

@Suppress("DEPRECATION")
private fun appVersionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}.getOrDefault("unknown")
