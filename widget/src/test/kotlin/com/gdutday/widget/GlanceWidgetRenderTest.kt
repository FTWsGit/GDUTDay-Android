package com.gdutday.widget

import androidx.compose.runtime.Composable
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 两个 Glance 插件组合函数的 JVM 渲染验证（glance-appwidget-testing，不需要模拟器）。
 *
 * 盯住 T2.1 提出的"Glance 渲染从未被验证"的核心面：给一个状态快照，组合函数
 * 必须画出对应文案 —— 包括各空状态分支（加载/未登录/无课/有课）与"今日/明日"
 * 切换条的方向。真实 launcher 上的 RemoteViews 落地仍属真机范畴，这里保证的
 * 是"组合层输出正确"。
 */
// Robolectric 4.16 最高支持 SDK 36，而项目 targetSdk=37，显式钉一个受支持的 SDK。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlanceWidgetRenderTest {

    @Test
    fun `下节课插件有课时渲染课程名地点与倒计时`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            GlanceWidgetContent {
                NextClassContent(
                    NextClassWidgetState(
                        phase = NextPhase.HAS_CLASS,
                        courseName = "高等数学",
                        locationText = "教5-301 · 08:30",
                        countdownText = "25分钟后",
                        colorArgb = 0xFFCC0000.toInt(),
                        ongoing = false,
                    ),
                )
            }
        }
        onNode(hasText("高等数学")).assertExists()
        onNode(hasText("教5-301 · 08:30")).assertExists()
        onNode(hasText("25分钟后")).assertExists()
    }

    @Test
    fun `下节课插件空状态渲染未登录文案`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            GlanceWidgetContent { NextClassContent(NextClassWidgetState.Loading) }
        }
        onNode(hasText("加载中…")).assertExists()
    }

    @Test
    fun `今日课程插件有课时渲染日期行课程行与明日切换条`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            GlanceWidgetContent {
                TodayScheduleContent(
                    TodayScheduleWidgetState(
                        phase = TodayPhase.HAS_CLASS,
                        dateLine = "9月10日 周三 · 第2周",
                        rows = listOf(
                            WidgetCourseRow(
                                startClock = "08:30",
                                name = "数据结构",
                                teacher = "李四",
                                classroom = "教4-201",
                                colorArgb = 0xFF3366CC.toInt(),
                                status = com.gdutday.core.common.BlockStatus.UPCOMING,
                            ),
                        ),
                        dayOffset = 0,
                    ),
                )
            }
        }
        onNode(hasText("9月10日 周三 · 第2周")).assertExists()
        onNode(hasText("数据结构")).assertExists()
        // dayOffset=0 时切换条显示"明"（点下去看到的那一天）
        onNode(hasText("明")).assertExists()
    }

    @Test
    fun `今日课程插件未登录状态渲染未登录文案`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            GlanceWidgetContent {
                TodayScheduleContent(
                    TodayScheduleWidgetState(TodayPhase.NOT_LOGGED_IN, "9月10日 周三", emptyList()),
                )
            }
        }
        onNode(hasText("未登录")).assertExists()
    }

    @Test
    fun `WidgetSizing对0高度给出默认行数`() {
        // LocalSize 异常为 0 时不退化成 1 行（防守分支的组合层兜底）。
        assertThat(WidgetSizing.todayRowCount(0f)).isEqualTo(WidgetSizing.DEFAULT_TODAY_ROWS)
    }
}

/** 测试用的最小包装：与生产路径一样把内容包进 GlanceTheme。 */
@Composable
private fun GlanceWidgetContent(content: @Composable () -> Unit) {
    androidx.glance.GlanceTheme { content() }
}
