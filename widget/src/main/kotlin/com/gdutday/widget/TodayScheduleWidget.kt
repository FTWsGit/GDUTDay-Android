package com.gdutday.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gdutday.core.common.BlockStatus

/**
 * "今日课程"插件（4x2，可缩放）。
 *
 * ## 尺寸响应
 *
 * `GlanceAppWidget` 的默认 `sizeMode` 是 `SizeMode.Single` —— 它只在第一次确定尺寸后
 * 渲染一次，用户拉大插件**不会**重新组合，`LocalSize` 也永远停在初始值。
 * 所以这里显式改成 [SizeMode.Exact]：launcher 每次改变尺寸都会带上新尺寸重跑，
 * [TodayScheduleContent] 里的 [LocalSize] 随即更新，[WidgetSizing] 算出新的行数。
 * 不写这一行，"拉大后显示更多"的承诺就是空的。
 *
 * ## 点击区域
 *
 * 整张插件点击打开 App；表头右侧的"刷新"是嵌套的第二个点击目标（Glance 里后声明的
 * clickable 覆盖父级）。这样公共场合用户只想刷新数据时不必跳进 App。
 */
public class TodayScheduleWidget : GlanceAppWidget() {

    /** 见类注释：默认 Single 不响应尺寸变化，必须显式改成 Exact。 */
    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = TodayScheduleWidgetStateDefinition()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                // 状态定义已就绪时不会是 null；这里仍给默认值，保证极端时序下不崩。
                val state = currentState<TodayScheduleWidgetState?>() ?: TodayScheduleWidgetState.Loading
                TodayScheduleContent(state)
            }
        }
    }
}

/** Manifest 里声明的类名必须与这个完全一致（见 app/src/main/AndroidManifest.xml）。 */
public class TodayScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayScheduleWidget()

    // 今日课程也要参与刷新链的生命周期：用户可能只放这个插件（M18）。
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshLifecycle.onEnabled(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshLifecycle.onDisabled(context)
    }
}

// ---------------------------------------------------------------------------- UI

@Composable
private fun TodayScheduleContent(state: TodayScheduleWidgetState) {
    val context = LocalContext.current
    // Intent 只需构造一次，不要每次重组都重建。
    val launchIntent = remember(context) { mainActivityIntent(context) }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧主体：点击打开 App。右侧竖条是独立的点击目标，两块区域不重叠。
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .clickable(actionStartActivity(launchIntent)),
            verticalAlignment = Alignment.Top,
        ) {
            TodayHeader(state.dateLine)
            when (state.phase) {
                TodayPhase.HAS_CLASS -> TodayCourseList(state.rows)
                else -> TodayEmpty(state.phase, context)
            }
        }
        DayToggleBar(dayOffset = state.dayOffset)
    }
}

/**
 * 右侧竖条：今天 ↔ 明天 切换。
 *
 * 条上写的是**点下去会看到的那一天** —— 当前显示今天时写"明天"，显示明天时写"今天"，
 * 再点一下就切回来。两个汉字竖排，避免 18dp 宽横排放不下。
 */
@Composable
private fun DayToggleBar(dayOffset: Int) {
    val targetFirst = if (dayOffset == 0) "明" else "今"
    Column(
        modifier = GlanceModifier
            .width(18.dp)
            .fillMaxHeight()
            .clickable(actionRunCallback<WidgetToggleDayAction>())
            .background(GlanceTheme.colors.primaryContainer),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            targetFirst,
            style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 10.sp),
        )
        Text(
            "天",
            style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 10.sp),
        )
    }
}

@Composable
private fun TodayHeader(dateLine: String) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateLine,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Text(
            text = REFRESH_LABEL,
            modifier = GlanceModifier
                .clickable(actionRunCallback<WidgetRefreshAction>())
                // 先 clickable 再 padding：把 11sp 文字四周都包进点击区，
                // 否则 40x14dp 的文字本身几乎点不中。
                .padding(horizontal = 8.dp, vertical = 6.dp),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp),
        )
    }
}

@Composable
private fun TodayCourseList(rows: List<WidgetCourseRow>) {
    // 这里才决定显示几条，mapper 给出的是"今天全部"。
    val maxRows = WidgetSizing.todayRowCount(LocalSize.current.height.value)
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        rows.take(maxRows).forEach { row -> TodayCourseRow(row) }
    }
}

@Composable
private fun TodayCourseRow(row: WidgetCourseRow) {
    val finished = row.status == BlockStatus.FINISHED
    val ongoing = row.status == BlockStatus.ONGOING

    // 课程色块必须用课程自己的颜色，不能跟随壁纸动态取色 —— 用户期望"高数一直是红色"。
    // 已完成只降低该色块的 alpha，颜色身份仍然保留。
    val accent = ColorProvider(
        if (finished) Color(row.colorArgb).copy(alpha = 0.35f) else Color(row.colorArgb),
    )
    val textColor = if (finished) GlanceTheme.colors.outline else GlanceTheme.colors.onSurface
    val nameWeight = if (ongoing) FontWeight.Bold else FontWeight.Medium

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(20.dp)
                .background(accent)
                .cornerRadius(2.dp),
        ) {}
        Text(
            text = row.startClock,
            // 不写死宽度：之前固定 38dp，大字体下 "08:30" 会被截掉一半。
            // 改成按内容自适应，右边留 8dp 与课程名分开。
            modifier = GlanceModifier.padding(start = 6.dp, end = 8.dp),
            style = TextStyle(
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (ongoing) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
        )
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.name,
                style = TextStyle(color = textColor, fontSize = 12.sp, fontWeight = nameWeight),
                maxLines = 1,
            )
            val detail = listOf(row.teacher, row.classroom)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TodayEmpty(phase: TodayPhase, context: Context) {
    val message = when (phase) {
        TodayPhase.LOADING -> context.getString(R.string.widget_loading)
        TodayPhase.NOT_LOGGED_IN -> context.getString(R.string.widget_empty_not_logged_in)
        TodayPhase.NO_CLASS -> context.getString(R.string.widget_empty_no_class)
        TodayPhase.ALL_FINISHED -> context.getString(R.string.widget_empty_no_more_class)
        TodayPhase.HAS_CLASS -> ""
    }
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }
}
