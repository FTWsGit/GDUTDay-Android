package com.gdutday.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gdutday.core.datastore.ScheduleView
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Term
import com.gdutday.data.repository.ScheduleUiState
import java.time.LocalDate

/**
 * 课表顶栏。
 *
 * 包含：学期名（可下拉切换）、当前周次、可选周次范围、[UserSettings.scheduleView] 切换、
 * 手动同步，以及"回到本周"按钮（只在浏览别的周次时出现，
 * 否则它会是一个永远没用的按钮，白白占位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTopBar(
    state: ScheduleUiState,
    settings: UserSettings,
    onSelectTerm: (Term) -> Unit,
    onBackToCurrentWeek: () -> Unit,
    onToggleView: () -> Unit,
    onSync: () -> Unit,
    onAddCourse: () -> Unit,
    onOpenSyncSource: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier,
) {
    var termMenuOpen by remember { mutableStateOf(false) }
    var actionMenuOpen by remember { mutableStateOf(false) }
    // 学期下拉的第二级：当前展开的学年起始年；null 表示停在一级（只显示学年列表）。
    var expandedTermYear by remember { mutableStateOf<Int?>(null) }
    val yearGroups = remember(state.availableTerms, state.term) {
        buildYearTermGroups(state.availableTerms, state.term, LocalDate.now())
    }

    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
        ),
        title = {
            Column {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = state.availableTerms.isNotEmpty()) {
                            termMenuOpen = true
                        },
                    ) {
                        Text(
                            text = state.term?.displayName ?: stringResource(R.string.schedule_term_unknown),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        if (state.availableTerms.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = termMenuOpen,
                        onDismissRequest = {
                            termMenuOpen = false
                            expandedTermYear = null
                        },
                    ) {
                        val expanded = expandedTermYear
                        if (expanded != null) {
                            // 第二级：某个学年下的学期。第一项返回学年列表。
                            DropdownMenuItem(
                                text = { Text(formatAcademicYear(expanded)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                                onClick = { expandedTermYear = null },
                            )
                            yearGroups.firstOrNull { it.year == expanded }?.terms?.forEach { term ->
                                DropdownMenuItem(
                                    text = { Text(semesterLabel(term)) },
                                    onClick = {
                                        termMenuOpen = false
                                        expandedTermYear = null
                                        onSelectTerm(term)
                                    },
                                )
                            }
                        } else {
                            // 第一级：只列学年（过去 4 年 ~ 未来 2 年），点开才看学期。
                            yearGroups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(formatAcademicYear(group.year)) },
                                    trailingIcon = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                                    onClick = { expandedTermYear = group.year },
                                )
                            }
                        }
                    }
                }
                Text(
                    text = weekLabel(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            if (!state.isViewingCurrentWeek) {
                TextButton(onClick = onBackToCurrentWeek) {
                    Text(stringResource(R.string.schedule_back_to_current_week))
                }
            }
            Box {
                IconButton(onClick = { actionMenuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.schedule_more))
                }
                DropdownMenu(expanded = actionMenuOpen, onDismissRequest = { actionMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_menu_add_course)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            actionMenuOpen = false
                            onAddCourse()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (settings.scheduleView == ScheduleView.WEEK) {
                                    stringResource(R.string.schedule_switch_to_day)
                                } else {
                                    stringResource(R.string.schedule_switch_to_week)
                                },
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (settings.scheduleView == ScheduleView.WEEK) {
                                    Icons.Filled.ViewDay
                                } else {
                                    Icons.Filled.ViewWeek
                                },
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            actionMenuOpen = false
                            onToggleView()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_menu_sync)) },
                        leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                        onClick = {
                            actionMenuOpen = false
                            onSync()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_menu_sync_source)) },
                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                        onClick = {
                            actionMenuOpen = false
                            onOpenSyncSource()
                        },
                    )
                }
            }
        },
    )
}

/** `第 12 周（11/17 - 11/23）`。周历缺失时只显示周次。 */
@Composable
private fun weekLabel(state: ScheduleUiState): String {
    val week = state.selectedWeek
    val calendar = state.calendar
        ?: return stringResource(R.string.schedule_week_plain, week)
    val monday = calendar.mondayOf(week)
    val sunday = calendar.sundayOf(week)
    return stringResource(
        R.string.schedule_week_with_date,
        week, monday.monthValue, monday.dayOfMonth, sunday.monthValue, sunday.dayOfMonth,
    )
}

/** 学期下拉的一项分组：一个学年起始年下的全部学期。 */
internal data class YearTermGroup(
    val year: Int,
    val terms: List<Term>,
)

/**
 * 把学期列表过滤到"当前学年往前 4 年、往后 2 年"的窗口，再按学年降序分组。
 *
 * 教务系统会返回几十年甚至上百年的学期，全部平铺在下拉里没法选。这里只保留
 * 一个现实可选的窗口。用户当前选中的学期若在窗口外（比如刚切到很久以前的课表），
 * 仍然保留 —— 否则下拉里看不到当前项，选了就选不回来。
 *
 * @param currentTerm 当前选中的学期；null 表示还没有选中任何学期。
 * @param today 参照日期。学年按国内惯例从 9 月开始：9 月后属今年，9 月前属去年。
 */
internal fun buildYearTermGroups(
    terms: List<Term>,
    currentTerm: Term?,
    today: LocalDate,
): List<YearTermGroup> {
    val currentAcademicYear = if (today.monthValue >= 9) today.year else today.year - 1
    val window = (currentAcademicYear - 4)..(currentAcademicYear + 2)
    return terms
        .filter { it.year in window || it == currentTerm }
        .sortedWith(compareByDescending<Term> { it.year }.thenByDescending { it.semester })
        .groupBy { it.year }
        .entries
        .sortedByDescending { it.key }
        .map { (year, yearTerms) -> YearTermGroup(year, yearTerms) }
}

/** `2025-2026学年`。 */
internal fun formatAcademicYear(year: Int): String = "$year-${year + 1}学年"

/** `第一学期` / `第二学期`。第二级里学年已在返回项上显示，这里只写学期。 */
internal fun semesterLabel(term: Term): String = if (term.semester == 1) "第一学期" else "第二学期"
