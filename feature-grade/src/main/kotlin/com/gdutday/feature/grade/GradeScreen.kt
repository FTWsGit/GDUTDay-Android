package com.gdutday.feature.grade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdutday.core.model.Grade
import com.gdutday.core.model.Term
import com.gdutday.core.model.TermGradeSummary
import com.gdutday.core.ui.EmptyState
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.SyncInfo

/**
 * 成绩与绩点页。
 *
 * ## ⚠ 契约文件：签名被 `app` 的 NavHost 直接调用，不要改
 *
 * ## KDoc 七条要求的落点
 *
 * 1. 数据来源是 [com.gdutday.data.repository.GradeRepository.observeSummaries]；
 *    加权绩点/总学分/挂科数由 [TermGradeSummary] 算好，本页只展示。
 * 2. 学期切换用 [ScrollableTabRow]，默认最新（`termNames.first()`）。
 * 3. 绩点趋势图见 [GpaTrendChart]（Canvas 手绘，无第三方图表库）。
 * 4. 等级制成绩：走 [GradeLogic.scoreDisplay]，`score == null` 时显示 `scoreText`。
 * 5. 挂科（`score < 60`）用 `colorScheme.error`。
 * 6. 空状态区分没登录 / 登录未同步 / 同步了但无成绩三种。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val viewModel: GradeViewModel = viewModel(factory = GradeViewModel.factory(container))
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val termNames by viewModel.termNames.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val selectedTerm by viewModel.selectedTermName.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.grade_title)) },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.grade_sync))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                summaries.isEmpty() && !isLoggedIn -> {
                    CenteredEmpty(
                        title = stringResource(R.string.grade_empty_not_logged_in),
                        subtitle = stringResource(R.string.grade_empty_not_logged_in_subtitle),
                    )
                }

                summaries.isEmpty() && lastSync?.at == null -> {
                    CenteredEmpty(
                        title = stringResource(R.string.grade_empty_not_synced),
                        subtitle = stringResource(R.string.grade_empty_not_synced_subtitle),
                        actionLabel = stringResource(R.string.grade_sync),
                        onAction = viewModel::refresh,
                    )
                }

                summaries.isEmpty() -> {
                    CenteredEmpty(
                        title = stringResource(R.string.grade_empty_no_grades),
                        subtitle = stringResource(R.string.grade_empty_no_grades_subtitle),
                        actionLabel = stringResource(R.string.grade_sync),
                        onAction = viewModel::refresh,
                    )
                }

                else -> {
                    // 默认学期 = 时间序最新的学期（Term.parse + Comparable），
                    // 而不是 termNames.first() 的字典序巧合——"2025-2026..." 这类名字
                    // 字典序恰巧与时间序同向，但"2024-2025学年第二学期"会排错。
                    val effectiveTerm = selectedTerm
                        ?: summaries.mapNotNull { it.term }.maxOrNull()?.let { latest ->
                            termNames.firstOrNull { name -> Term.parse(name) == latest }
                        }
                        ?: termNames.firstOrNull()
                    val summary = summaries.firstOrNull { it.termName == effectiveTerm }
                        ?: summaries.first()
                    Column(Modifier.fillMaxSize()) {
                        ScrollableTabRow(
                            selectedTabIndex = termNames.indexOf(summary.termName).coerceAtLeast(0),
                            edgePadding = 12.dp,
                        ) {
                            termNames.forEach { name ->
                                Tab(
                                    selected = name == summary.termName,
                                    onClick = { viewModel.selectTerm(name) },
                                    text = { Text(name) },
                                )
                            }
                        }
                        GradeList(
                            summary = summary,
                            allSummaries = summaries,
                            lastSync = lastSync,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredEmpty(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = title, subtitle = subtitle, actionLabel = actionLabel, onAction = onAction)
    }
}

@Composable
private fun GradeList(
    summary: TermGradeSummary,
    allSummaries: List<TermGradeSummary>,
    lastSync: SyncInfo?,
) {
    val trendPoints = remember(allSummaries) { GradeLogic.trendPoints(allSummaries) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp,
        ),
    ) {
        item { SummaryCard(summary) }

        item {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    text = stringResource(R.string.grade_trend_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (trendPoints.isEmpty()) {
                    Text(
                        text = stringResource(R.string.grade_trend_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    GpaTrendChart(points = trendPoints, modifier = Modifier.padding(top = 8.dp))
                }
                lastSync?.let {
                    Text(
                        text = stringResource(R.string.grade_last_sync, it.relativeTime()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = stringResource(R.string.grade_col_course),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                HeaderCell(stringResource(R.string.grade_col_credit), 52.dp)
                HeaderCell(stringResource(R.string.grade_col_score), 60.dp)
                HeaderCell(stringResource(R.string.grade_col_gpa), 50.dp)
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }

        items(summary.grades, key = { it.id }) { grade ->
            GradeRow(grade = grade)
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun SummaryCard(summary: TermGradeSummary) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCell(
                label = stringResource(R.string.grade_summary_gpa),
                value = summary.weightedGpa?.let(GradeLogic::formatGpa) ?: "--",
                valueColor = MaterialTheme.colorScheme.primary,
            )
            StatCell(
                label = stringResource(R.string.grade_summary_credit),
                value = GradeLogic.formatCredit(summary.totalCredit),
            )
            StatCell(
                label = stringResource(R.string.grade_summary_failed),
                value = summary.failedCount.toString(),
                valueColor = if (summary.failedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GradeRow(
    grade: Grade,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(grade.courseName, style = MaterialTheme.typography.bodyMedium)
            val meta = listOf(grade.studyMode, grade.courseCategory)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = grade.credit?.let(GradeLogic::formatCredit) ?: "--",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = GradeLogic.scoreDisplay(grade),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (GradeLogic.isFailed(grade)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = grade.gpa?.let(GradeLogic::formatGpa) ?: "--",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(50.dp),
        )
    }
}
