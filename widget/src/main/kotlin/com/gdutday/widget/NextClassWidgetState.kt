package com.gdutday.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.glance.state.GlanceStateDefinition
import com.gdutday.core.common.CourseColors
import com.gdutday.core.datastore.UserSettings
import com.gdutday.data.repository.NextClass
import kotlinx.coroutines.flow.combine
import java.io.File

/**
 * "下节课"插件的渲染快照。
 *
 * 课程名可能已被打码；[locationText]（教室 · 上课时间）与 [countdownText] 永远可读 ——
 * 打码只针对"能暴露你上什么课"的信息，"在哪、还有多久"是插件存在的意义，必须保留。
 */
public data class NextClassWidgetState(
    public val phase: NextPhase,
    public val courseName: String,
    public val locationText: String,
    public val countdownText: String,
    public val colorArgb: Int,
    public val ongoing: Boolean,
) {
    public companion object {
        public val Loading: NextClassWidgetState = NextClassWidgetState(
            phase = NextPhase.LOADING,
            courseName = "",
            locationText = "",
            countdownText = "",
            colorArgb = CourseColors.DEFAULT.argb,
            ongoing = false,
        )
    }
}

/** [NextPhase.NONE] 表示已登录但近期（三周内）没有任何未结束的课。 */
public enum class NextPhase { LOADING, NOT_LOGGED_IN, NONE, HAS_CLASS }

/**
 * `NextClass?` + 设置 → 插件快照 的纯映射。
 *
 * 直接复用 [NextClass] 自带的 `countdownText` / `locationText`：这两个字符串的逻辑
 * 与首页卡片完全一致，放在 `ScheduleUiState.kt` 里是刻意的，Widget 不再抄一遍，
 * 否则"文字不一致"这类 bug 会两边各修一次。
 */
public object NextClassMapper {

    public fun map(next: NextClass?, loggedIn: Boolean, settings: UserSettings): NextClassWidgetState {
        if (next == null) {
            val phase = if (loggedIn) NextPhase.NONE else NextPhase.NOT_LOGGED_IN
            return NextClassWidgetState(
                phase = phase,
                courseName = "",
                locationText = "",
                countdownText = "",
                colorArgb = CourseColors.DEFAULT.argb,
                ongoing = false,
            )
        }
        val blur = WidgetText.shouldBlur(settings)
        return NextClassWidgetState(
            phase = NextPhase.HAS_CLASS,
            courseName = WidgetText.mask(next.block.course.name, blur),
            locationText = next.locationText,
            countdownText = next.countdownText,
            colorArgb = next.block.color.argb,
            ongoing = next.isOngoing,
        )
    }
}

/**
 * 与 [TodayScheduleWidgetStateDefinition] 同理：把仓库 Flow 接进 Glance 的状态定义。
 *
 * 不同点是数据源只有 `observeNextClass()` 一路（它内部已经 combine 了课表和设置），
 * 再叠加"是否登录"用于区分空状态。
 */
internal class NextClassWidgetStateDefinition : GlanceStateDefinition<NextClassWidgetState> {

    override fun getLocation(context: Context, fileKey: String): File = File(context.cacheDir, fileKey)

    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<NextClassWidgetState> {
        val container = context.widgetContainer()
        return FlowDataStore {
            combine(
                container.scheduleRepository.observeNextClass(),
                container.settingsStore.settings,
                container.authRepository.isLoggedIn,
            ) { next, settings, loggedIn ->
                NextClassMapper.map(next, loggedIn, settings)
            }
        }
    }
}
