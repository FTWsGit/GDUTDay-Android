package com.gdutday.feature.settings

import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.gdutday.data.repository.SyncInfo
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * 自定义作息表校验失败的具体原因。
 *
 * 要区分到这一层是因为"保存失败"对用户毫无信息量：
 * 用户需要知道是**少填了一节**、**时间格式错了**还是**结束早于开始**。
 */
public enum class TimetableInvalidReason {
    /** 不是 24 项（12 节的起止）。 */
    WRONG_SIZE,

    /** 存在无法解析的 `HH:mm`。 */
    BAD_FORMAT,

    /** 某节结束时刻早于或等于开始时刻。 */
    REVERSED,
}

/** 校验结果。 */
public sealed interface TimetableValidation {
    /** 校验通过。`normalized` 是规范化后的 `HH:mm` 列表。 */
    public data class Valid(public val normalized: List<String>) : TimetableValidation

    public data class Invalid(public val reason: TimetableInvalidReason) : TimetableValidation
}

/**
 * 设置页纯逻辑。
 *
 * 抽出来的都是"不依赖 Android 却能直接坑到用户"的规则：
 * 作息表校验、透明度钳制、诊断信息拼装。它们的共同点是结果错了用户很难自己发现。
 */
public object SettingsLogic {

    /** 透明度滑杆可选的自动同步间隔（小时）。 */
    public val SYNC_INTERVAL_OPTIONS: List<Int> = listOf(1, 2, 3, 6, 12, 24)

    /** 某校区内置作息表的 `HH:mm` 字符串对（14 节共 28 项）。 */
    public fun defaultTimetable(campus: Campus): List<String> =
        CampusTimetable.of(campus).periods.flatMap { listOf(formatTime(it.start), formatTime(it.end)) }

    /** `LocalTime` → `HH:mm`。固定 24 小时制，避免跟随系统区域设置变化。 */
    public fun formatTime(time: LocalTime): String =
        String.format(Locale.US, "%02d:%02d", time.hour, time.minute)

    /**
     * 透明度钳制。用户拖不到 0，否则课程块完全消失后找不回来
     * （这是旧小程序的一个实际投诉点）。
     */
    public fun clampAlpha(alpha: Float): Float = alpha.coerceIn(UserSettings.ALPHA_RANGE)

    /**
     * 校验自定义作息表。
     *
     * 前三步给出**具体失败原因**，最后再交给 [CampusTimetable.parseCustom] 兜底 ——
     * 它是保存的唯一权威，返回 null 就一定不保存。
     */
    public fun validateCustomTimetable(campus: Campus, raw: List<String>): TimetableValidation {
        // 接受 28 项（14 节，当前标准）或 24 项（旧版 12 节，缺失节次由 parseCustom 补默认值）。
        if (raw.size != CampusTimetable.SECTIONS_PER_DAY * 2 && raw.size != 24) {
            return TimetableValidation.Invalid(TimetableInvalidReason.WRONG_SIZE)
        }
        // 校验循环按输入的实际节次走（24 项时只查前 12 节，末两节交给默认值）。
        val sectionCount = raw.size / 2
        val parsedTimes = raw.map { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
        if (parsedTimes.any { it == null }) {
            return TimetableValidation.Invalid(TimetableInvalidReason.BAD_FORMAT)
        }
        for (i in 0 until sectionCount) {
            val start = parsedTimes[i * 2]!!
            val end = parsedTimes[i * 2 + 1]!!
            if (end <= start) return TimetableValidation.Invalid(TimetableInvalidReason.REVERSED)
        }
        // 权威校验：即使上面都过了，parseCustom 仍可能因为别的原因拒绝。
        val timetable = CampusTimetable.parseCustom(campus, raw)
            ?: return TimetableValidation.Invalid(TimetableInvalidReason.BAD_FORMAT)
        val normalized = timetable.periods.flatMap { listOf(formatTime(it.start), formatTime(it.end)) }
        return TimetableValidation.Valid(normalized)
    }

    /**
     * 拼装诊断信息。
     *
     * ## 安全约束
     *
     * 入参 [sessionSafe] 必须已经过 `GdutSession.toSafeString()`，**绝不能传 cookie**。
     * 这个函数只做字符串拼接，没有任何读取会话内部字段的能力 —— 这样"诊断信息泄露
     * cookie"在类型层面就不可能发生。
     */
    public fun buildDiagnostics(
        sessionSafe: String?,
        syncInfo: SyncInfo?,
        timetable: CampusTimetable?,
        semesterStart: LocalDate?,
        startSource: SemesterStartSource,
        appVersion: String,
    ): String = buildString {
        appendLine("广工课表 诊断信息")
        appendLine("版本: $appVersion")
        appendLine("会话: ${sessionSafe ?: "未登录"}")
        appendLine("上次同步: ${syncInfo?.relativeTime() ?: "从未同步"}")
        appendLine("  命中接口: ${syncInfo?.source?.label ?: "-"}")
        appendLine("  结果: ${if (syncInfo?.success == false) "失败" else "成功"}")
        if (!syncInfo?.error.isNullOrBlank()) {
            appendLine("  错误: ${syncInfo?.error}")
        }
        syncInfo?.warnings?.forEach { appendLine("  警告: $it") }
        appendLine("学期开始: ${semesterStart ?: "-"}（来源: ${startSource.name}）")
        val campusName = timetable?.campus?.displayName?.ifBlank { "未知" } ?: "未知"
        appendLine("作息表（$campusName）:")
        timetable?.periods?.forEach {
            appendLine("  第${it.index}节 ${formatTime(it.start)}-${formatTime(it.end)}")
        }
    }
}
