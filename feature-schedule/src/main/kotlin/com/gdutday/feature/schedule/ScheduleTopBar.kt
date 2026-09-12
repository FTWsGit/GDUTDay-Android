package com.gdutday.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
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
    containerColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier,
) {
    var termMenuOpen by remember { mutableStateOf(false) }
    var actionMenuOpen by remember { mutableStateOf(false) }

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
                    DropdownMenu(expanded = termMenuOpen, onDismissRequest = { termMenuOpen = false }) {
                        state.availableTerms.forEach { term ->
                            DropdownMenuItem(
                                text = { Text(term.displayName) },
                                onClick = {
                                    termMenuOpen = false
                                    onSelectTerm(term)
                                },
                            )
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
