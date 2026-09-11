package com.gdutday.feature.settings

import com.gdutday.core.common.CampusTimetable
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.Campus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 设置纯逻辑测试。
 *
 * 重点是自定义作息表校验：返回 null 就不保存，但用户需要知道**为什么**。
 * 这里逐条钉住"少一项 / 格式错 / 时间倒挂"三种失败原因。
 */
class SettingsLogicTest {

    @Test
    fun `默认作息表是 28 项且可被 parseCustom 接受，旧版 24 项也接受`() {
        val raw = SettingsLogic.defaultTimetable(Campus.UNIVERSITY_CITY)
        assertThat(raw).hasSize(CampusTimetable.SECTIONS_PER_DAY * 2)
        assertThat(CampusTimetable.parseCustom(Campus.UNIVERSITY_CITY, raw)).isNotNull()
        // 旧版 12 节（24 项）仍能通过校验并补齐为 28 项
        val legacy = SettingsLogic.validateCustomTimetable(Campus.UNIVERSITY_CITY, raw.dropLast(4))
        assertThat(legacy).isInstanceOf(TimetableValidation.Valid::class.java)
        assertThat((legacy as TimetableValidation.Valid).normalized).hasSize(28)
    }

    @Test
    fun `少一项时报 WRONG_SIZE`() {
        val raw = SettingsLogic.defaultTimetable(Campus.DONGFENG_ROAD).dropLast(1)
        val result = SettingsLogic.validateCustomTimetable(Campus.DONGFENG_ROAD, raw)
        assertThat(result).isInstanceOf(TimetableValidation.Invalid::class.java)
        assertThat((result as TimetableValidation.Invalid).reason).isEqualTo(TimetableInvalidReason.WRONG_SIZE)
    }

    @Test
    fun `时间格式错误时报 BAD_FORMAT`() {
        val raw = SettingsLogic.defaultTimetable(Campus.LONGDONG).toMutableList()
        raw[0] = "abc"
        val result = SettingsLogic.validateCustomTimetable(Campus.LONGDONG, raw)
        assertThat(result).isInstanceOf(TimetableValidation.Invalid::class.java)
        assertThat((result as TimetableValidation.Invalid).reason).isEqualTo(TimetableInvalidReason.BAD_FORMAT)
    }

    @Test
    fun `结束早于开始时上报 REVERSED`() {
        val raw = SettingsLogic.defaultTimetable(Campus.UNIVERSITY_CITY).toMutableList()
        // 第 1 节：08:30-09:15 → 改成 10:00-09:15（倒挂）
        raw[0] = "10:00"
        val result = SettingsLogic.validateCustomTimetable(Campus.UNIVERSITY_CITY, raw)
        assertThat(result).isInstanceOf(TimetableValidation.Invalid::class.java)
        assertThat((result as TimetableValidation.Invalid).reason).isEqualTo(TimetableInvalidReason.REVERSED)
    }

    @Test
    fun `合法输入返回规范化结果`() {
        val raw = SettingsLogic.defaultTimetable(Campus.UNIVERSITY_CITY)
        val result = SettingsLogic.validateCustomTimetable(Campus.UNIVERSITY_CITY, raw)
        assertThat(result).isInstanceOf(TimetableValidation.Valid::class.java)
        assertThat((result as TimetableValidation.Valid).normalized).hasSize(28)
    }

    @Test
    fun `透明度被钳制在 ALPHA_RANGE`() {
        assertThat(SettingsLogic.clampAlpha(0f)).isEqualTo(UserSettings.ALPHA_RANGE.start)
        assertThat(SettingsLogic.clampAlpha(2f)).isEqualTo(UserSettings.ALPHA_RANGE.endInclusive)
        assertThat(SettingsLogic.clampAlpha(0.8f)).isEqualTo(0.8f)
    }

    @Test
    fun `时间格式化固定两位且 24 小时制`() {
        assertThat(SettingsLogic.formatTime(java.time.LocalTime.of(8, 5))).isEqualTo("08:05")
        assertThat(SettingsLogic.formatTime(java.time.LocalTime.of(20, 55))).isEqualTo("20:55")
    }
}
