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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdutday.core.model.Exam
import com.gdutday.core.model.Grade
import com.gdutday.core.model.Term
import com.gdutday.core.model.TermGradeSummary
import com.gdutday.core.ui.EmptyState
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.SyncInfo
import java.time.LocalDate

/**
 * 考试与成绩页。
 *
 * 把"考试安排"和"成绩查询"放在同一个入口下：顶部一级 Tab 切换
 * [GradeTab.EXAMS]（考试安排）与 [GradeTab.GRADES]（成绩）。
 * 底部导航条目本身改名为"考试"。
 *
 * ## ⚠ 契约文件：签名被 `app` 的 NavHost 直接调用，不要改
 *
 * ## 数据来源
 *
 * - 成绩：[com.gdutday.data.repository.GradeRepository.observeSummaries]；
 *   加权绩点/总学分/挂科数由 [TermGradeSummary] 算好。
 * - 考试：[com.gdutday.data.repository.GradeRepository.observeExams]；
 *   考试数据由**课表同步**落库（`ScheduleRepository.sync`），本页只读。
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
    val exams by viewModel.exams.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val selectedTerm by viewModel.selectedTermName.collectAsStateWithLifecycle()
    val overallSummary by viewModel.overallSummary.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(GradeTab.EXAMS.ordinal) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.grade_title)) },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        val onRefresh = if (tab == GradeTab.EXAMS.ordinal) {
                            viewModel::refreshExams
                        } else {
                            viewModel::refresh
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.grade_sync))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            if (!isLoggedIn) {
                CenteredEmpty(
                    title = stringResource(R.string.grade_empty_not_logged_in),
                    subtitle = stringResource(R.string.grade_empty_not_logged_in_subtitle),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = tab.coerceIn(0, 1),
                    ) {
                        Tab(
                            selected = tab == GradeTab.EXAMS.ordinal,
                            onClick = { tab = GradeTab.EXAMS.ordinal },
                            text = { Text(stringResource(R.string.grade_tab_exams)) },
                        )
                        Tab(
                            selected = tab == GradeTab.GRADES.ordinal,
                            onClick = { tab = GradeTab.GRADES.ordinal },
                            text = { Text(stringResource(R.string.grade_tab_grades)) },
                        )
                    }
                    when (tab) {
                        GradeTab.EXAMS.ordinal -> ExamSection(
                            exams = exams,
                            lastSync = lastSync,
                            onSync = viewModel::refreshExams,
                        )
                        else -> GradeSection(
                            summaries = summaries,
                            termNames = termNames,
                            selectedTerm = selectedTerm,
                            onSelectTerm = viewModel::selectTerm,
                            overallSummary = overallSummary,
                            lastSync = lastSync,
                            onSync = viewModel::refresh,
                        )
                    }
                }
            }
        }
    }
}

/** 一级 Tab：考试安排 / 成绩。 */
private enum class GradeTab { EXAMS, GRADES }

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

// ---------------------------------------------------------------- 考试安排

@Composable
private fun ExamSection(
    exams: List<Exam>,
    lastSync: SyncInfo?,
    onSync: () -> Unit,
) {
    if (exams.isEmpty()) {
        CenteredEmpty(
            title = stringResource(R.string.grade_exam_empty_no_exams),
            subtitle = stringResource(R.string.grade_exam_empty_no_exams_subtitle),
            actionLabel = stringResource(R.string.grade_exam_sync),
            onAction = onSync,
        )
        return
    }
    val today = remember { LocalDate.now() }
    val groups = remember(exams) { ExamLogic.groupByTerm(exams) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp,
        ),
    ) {
        lastSync?.let {
            item {
                Text(
                    text = stringResource(R.string.grade_last_sync, it.relativeTime()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groups.forEach { group ->
            item(key = "term_${group.term.shortCode}") {
                Text(
                    text = group.term.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(group.exams, key = { "${group.term.shortCode}_${it.date}_${it.courseName}" }) { exam ->
                ExamRow(exam = exam, today = today)
            }
        }
    }
}

@Composable
private fun ExamRow(exam: Exam, today: LocalDate) {
    val past = ExamLogic.isPast(exam, today)
    val relative = ExamLogic.relativeDays(exam, today)
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (past) scheme.surfaceVariant else scheme.primaryContainer.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = exam.courseName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (past) scheme.onSurfaceVariant else scheme.onSurface,
                    )
                    if (relative == stringResource(R.string.grade_exam_today)) {
                        ExamBadge(text = relative, tone = ExamBadgeTone.TODAY)
                    } else if (!past) {
                        ExamBadge(text = relative, tone = ExamBadgeTone.UPCOMING)
                    }
                }
                val meta = buildList {
                    add(ExamLogic.dateLabel(exam.date))
                    add(exam.timeDisplay)
                    if (exam.classroom.isNotBlank()) add(exam.classroom)
                    if (exam.category.isNotBlank()) add(exam.category)
                }
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private enum class ExamBadgeTone { TODAY, UPCOMING }

@Composable
private fun ExamBadge(text: String, tone: ExamBadgeTone) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        ExamBadgeTone.TODAY -> scheme.errorContainer to scheme.onErrorContainer
        ExamBadgeTone.UPCOMING -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

// ---------------------------------------------------------------- 成绩

@Composable
private fun GradeSection(
    summaries: List<TermGradeSummary>,
    termNames: List<String>,
    selectedTerm: String?,
    onSelectTerm: (String?) -> Unit,
    overallSummary: TermGradeSummary?,
    lastSync: SyncInfo?,
    onSync: () -> Unit,
) {
    when {
        summaries.isEmpty() && lastSync?.at == null -> {
            CenteredEmpty(
                title = stringResource(R.string.grade_empty_not_synced),
                subtitle = stringResource(R.string.grade_empty_not_synced_subtitle),
                actionLabel = stringResource(R.string.grade_sync),
                onAction = onSync,
            )
        }

        summaries.isEmpty() -> {
            CenteredEmpty(
                title = stringResource(R.string.grade_empty_no_grades),
                subtitle = stringResource(R.string.grade_empty_no_grades_subtitle),
                actionLabel = stringResource(R.string.grade_sync),
                onAction = onSync,
            )
        }

        else -> {
            // 默认学期 = 时间序最新的学期（Term.parse + Comparable）。
            val effectiveTerm = selectedTerm
                ?: summaries.mapNotNull { it.term }.maxOrNull()?.let { latest ->
                    termNames.firstOrNull { name -> Term.parse(name) == latest }
                }
                ?: termNames.firstOrNull()
            // "全部" Tab 用跨学期总览；其它 Tab 取对应学期汇总。
            val summary = when (effectiveTerm) {
                "全部" -> overallSummary ?: summaries.first()
                else -> summaries.firstOrNull { it.termName == effectiveTerm }
                    ?: summaries.first()
            }
            Column(Modifier.fillMaxSize()) {
                ScrollableTabRow(
                    selectedTabIndex = termNames.indexOf(summary.termName).coerceAtLeast(0),
                    edgePadding = 12.dp,
                ) {
                    termNames.forEach { name ->
                        Tab(
                            selected = name == summary.termName,
                            onClick = { onSelectTerm(name) },
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
