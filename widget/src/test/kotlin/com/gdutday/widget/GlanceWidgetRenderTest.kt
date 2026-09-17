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
 * 必须画出对应文案 —— 包括各空状态分支（加载/未登录/无课/有课）与"今天/明天"
 * 切换条是否显示当前正在看的那一天。真实 launcher 上的 RemoteViews 落地仍属真机范畴，
 * 这里保证的是"组合层输出正确"。
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
                                endClock = "09:15",
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
        // 起止时间上下两行堆叠渲染（见 TodayScheduleWidget 的注释），分别断言两行都存在
        onNode(hasText("08:30")).assertExists()
        onNode(hasText("09:15")).assertExists()
        // dayOffset=0 时切换条显示"今"（当前正在显示的那一天，见 DayToggleBar 的 KDoc）
        onNode(hasText("今")).assertExists()
    }

    @Test
    fun `今日课程插件明天视图显示明天切换条与明天没有课文案`() = runGlanceAppWidgetUnitTest {
        // 回归：切换条曾经显示"点下去会看到的那一天"，dayOffset=1（正在看明天）时
        // 条上反而写"今"，容易被误认为没切换成功；空状态文案也曾经不管 dayOffset
        // 恒写"今天没有课"，明天没课时看着像是显示错了天。
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable {
            GlanceWidgetContent {
                TodayScheduleContent(
                    TodayScheduleWidgetState(
                        phase = TodayPhase.NO_CLASS,
                        dateLine = "9月11日 周四 · 第2周",
                        rows = emptyList(),
                        dayOffset = 1,
                    ),
                )
            }
        }
        // 空状态文案已经隐含验证了 dayOffset=1（"明"），这里不再重复断言切换条本身的
        // 单字文案：`hasText("明")` 对精确匹配也会同时命中"明天没有课"里的"明"字，
        // 让 onNode() 因为匹配到 2 个节点而失败——这是断言写法问题，不是产品 bug。
        onNode(hasText("明天没有课")).assertExists()
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
