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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * "下节课"插件（4x1）。
 *
 * 倒计时**不走秒**，也不在这里做任何计时：插件只负责把仓库算好的
 * `countdownText` 画出来，真正的定时刷新交给 [NextClassRefreshScheduler] 的
 * WorkManager 分级任务。这样 launcher 进程不会因为每秒重绘而耗电。
 */
public class NextClassWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = NextClassWidgetStateDefinition()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val state = currentState<NextClassWidgetState?>() ?: NextClassWidgetState.Loading
                NextClassContent(state)
            }
        }
    }
}

/**
 * Manifest 里声明的类名必须与这个完全一致。
 *
 * `onEnabled` / `onDisabled` 是生命周期钩子：第一个插件被添加时启动自我重排的
 * 分级刷新；最后一个被移除时取消，避免用户已不用插件却还有 WorkManager 空转。
 */
public class NextClassWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 此时还没有可信的"下一节课"，先立即跑一次，让 worker 自己算出正确间隔。
        NextClassRefreshScheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        NextClassRefreshScheduler.cancel(context)
    }
}

// ---------------------------------------------------------------------------- UI

@Composable
private fun NextClassContent(state: NextClassWidgetState) {
    val context = LocalContext.current
    val launchIntent = remember(context) { mainActivityIntent(context) }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity(launchIntent)),
        contentAlignment = Alignment.Center,
    ) {
        if (state.phase == NextPhase.HAS_CLASS) {
            Row(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier
                        .width(4.dp)
                        .height(30.dp)
                        .background(ColorProvider(Color(state.colorArgb)))
                        .cornerRadius(2.dp),
                ) {}
                Column(modifier = GlanceModifier.padding(start = 10.dp).defaultWeight()) {
                    Text(
                        text = state.courseName,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = state.locationText,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
                Text(
                    text = state.countdownText,
                    style = TextStyle(
                        color = if (state.ongoing) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (state.ongoing) FontWeight.Bold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = emptyMessage(state.phase, context),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
    }
}

private fun emptyMessage(phase: NextPhase, context: Context): String = when (phase) {
    NextPhase.LOADING -> context.getString(R.string.widget_loading)
    NextPhase.NOT_LOGGED_IN -> context.getString(R.string.widget_empty_not_logged_in)
    // 已登录但近期没有课。复用"今天没有课"文案：语义上最接近，也避免新增字符串资源。
    NextPhase.NONE -> context.getString(R.string.widget_empty_no_class)
    NextPhase.HAS_CLASS -> ""
}
