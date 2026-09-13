package com.gdutday.core.datastore

import com.gdutday.core.model.Campus
import com.gdutday.core.model.ScheduleFetchStrategy
import com.gdutday.core.model.SyncSourceType
import com.gdutday.core.model.Term
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test

/**
 * 内存 Map 版的 [SettingsBackend]。
 *
 * 它模拟 DataStore 的关键语义：`edit` 在一次受锁保护的事务里完成
 * "读快照 -> 变换 -> 写回"，因此可以真实验证 [DataStoreSettingsStore.update]
 * 是否原子、是否把脏数据正确映射。
 */
private class FakeSettingsBackend(
    initial: Map<String, Any?> = emptyMap(),
) : SettingsBackend {

    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    override val values: Flow<Map<String, Any?>> = state

    override suspend fun edit(transform: (MutableMap<String, Any?>) -> Unit) {
        mutex.withLock {
            val working = LinkedHashMap(state.value)
            transform(working)
            state.value = working
        }
    }

    override suspend fun clear() {
        mutex.withLock { state.value = emptyMap() }
    }
}

/** [DataStoreSettingsStore] / [SettingsMapper] 的映射与脏数据回退测试。 */
class DataStoreSettingsStoreTest {

    private val customTimetable = (1..24).map { "${it.toString().padStart(2, '0')}:00" }

    private fun fullyPopulated() = UserSettings(
        selectedTerm = Term(2025, 2),
        campus = Campus.LONGDONG,
        scheduleView = ScheduleView.DAY,
        courseBlockAlpha = 0.55f,
        dimFinishedCourses = false,
        courseTextColor = CourseTextColor.BLACK,
        backgroundImageUri = "content://media/external/images/1",
        backgroundBlurDp = 12,
        showTeacher = false,
        showClassroom = false,
        showExtraSections = true,
        showWeekend = false,
        customTimetableEnabled = true,
        customTimetable = customTimetable,
        fetchStrategy = ScheduleFetchStrategy.ONLY_DATA_LIST,
        autoSyncOnLaunch = false,
        autoSyncIntervalHours = 3,
    )

    @Test
    fun `所有字段一次往返后完全一致`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        val expected = fullyPopulated()

        store.update { expected }

        assertThat(store.settings.first()).isEqualTo(expected)
    }

    @Test
    fun `自定义作息表顺序被保留`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        // 逆序写入，读取必须仍是逆序 —— 这正是不能用 Set 的原因
        val reversed = customTimetable.reversed()

        store.update { it.copy(customTimetableEnabled = true, customTimetable = reversed) }

        val read = store.settings.first().customTimetable
        assertThat(read).isEqualTo(reversed)
        assertThat(read.first()).isEqualTo("24:00")
    }

    @Test
    fun `空设置映射为默认值`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        assertThat(store.settings.first()).isEqualTo(UserSettings())
    }

    @Test
    fun `脏数据全部回退默认值而不是崩溃`() = runBlocking {
        // 模拟被手工改坏 / 跨版本不兼容的 Preferences
        val backend = FakeSettingsBackend(
            mapOf(
                SettingsKeys.CAMPUS to "火星",
                SettingsKeys.SCHEDULE_VIEW to "GALAXY",
                SettingsKeys.COURSE_TEXT_COLOR to "MAGENTA",
                SettingsKeys.FETCH_STRATEGY to "WARP_DRIVE",
                SettingsKeys.SELECTED_TERM to "not-a-term",
                SettingsKeys.CUSTOM_TIMETABLE to "}{ not json",
                SettingsKeys.BACKGROUND_BLUR_DP to "deep",
                SettingsKeys.AUTO_SYNC_INTERVAL_HOURS to "many",
                SettingsKeys.SHOW_TEACHER to "yes",
                SettingsKeys.COURSE_BLOCK_ALPHA to "opaque",
                SettingsKeys.BACKGROUND_IMAGE_URI to "",
            ),
        )
        val store = DataStoreSettingsStore(backend)

        assertThat(store.settings.first()).isEqualTo(UserSettings())
    }

    @Test
    fun `超范围的透明度被钳制而不是丢弃整个设置`() = runBlocking {
        val store = DataStoreSettingsStore(
            FakeSettingsBackend(mapOf(SettingsKeys.COURSE_BLOCK_ALPHA to 0.0f)),
        )
        assertThat(store.settings.first().courseBlockAlpha)
            .isEqualTo(UserSettings.ALPHA_RANGE.start)
    }

    @Test
    fun `超范围或负数的同步间隔被钳制`() = runBlocking {
        val store = DataStoreSettingsStore(
            FakeSettingsBackend(mapOf(SettingsKeys.AUTO_SYNC_INTERVAL_HOURS to -3)),
        )
        assertThat(store.settings.first().autoSyncIntervalHours).isAtLeast(0)
    }

    @Test
    fun `update 基于当前值而不是默认值`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        store.update { it.copy(campus = Campus.PANYU, showTeacher = false) }
        store.update { it.copy(showClassroom = false) }

        val result = store.settings.first()
        // 第二次 update 没有丢第一次的修改
        assertThat(result.campus).isEqualTo(Campus.PANYU)
        assertThat(result.showTeacher).isFalse()
        assertThat(result.showClassroom).isFalse()
    }

    @Test
    fun `reset 清空回默认值`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        store.update { fullyPopulated() }
        assertThat(store.settings.first()).isNotEqualTo(UserSettings())

        store.reset()

        assertThat(store.settings.first()).isEqualTo(UserSettings())
    }

    @Test
    fun `写入 null 的可选字段会被移除`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        store.update {
            it.copy(selectedTerm = Term(2025, 1), backgroundImageUri = "content://x")
        }
        store.update { it.copy(selectedTerm = null, backgroundImageUri = null) }

        val result = store.settings.first()
        assertThat(result.selectedTerm).isNull()
        assertThat(result.backgroundImageUri).isNull()
    }

    @Test
    fun `setLibraryQrStudentId 写入并读取`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())

        store.setLibraryQrStudentId("3120001234")

        assertThat(store.settings.first().libraryQrStudentId).isEqualTo("3120001234")
    }

    @Test
    fun `setLibraryQrStudentId 过滤非数字字符`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())

        store.setLibraryQrStudentId("31a2b0c0x9")

        assertThat(store.settings.first().libraryQrStudentId).isEqualTo("312009")
    }

    @Test
    fun `setLibraryQrStudentId 空字符串删除键`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        store.setLibraryQrStudentId("3120001234")
        assertThat(store.settings.first().libraryQrStudentId).isEqualTo("3120001234")

        store.setLibraryQrStudentId("")

        // 键被删除后读回默认值（空字符串）
        assertThat(store.settings.first().libraryQrStudentId).isEmpty()
    }

    // ---------------------------------------------------------------- 同步源

    @Test
    fun `setSyncSourceType 切换班级课表后再切回个人`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        store.setSyncSourceType(SyncSourceType.CLASS_SCHEDULE)
        assertThat(store.settings.first().syncSourceType).isEqualTo(SyncSourceType.CLASS_SCHEDULE)

        store.setSyncSourceType(SyncSourceType.PERSONAL)
        assertThat(store.settings.first().syncSourceType).isEqualTo(SyncSourceType.PERSONAL)
    }

    @Test
    fun `setClassSchedule 同时写入班级代码与显示名`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())

        store.setClassSchedule(" 116523137 ", " 计算机25(5) ")

        val settings = store.settings.first()
        assertThat(settings.classScheduleBjdm).isEqualTo("116523137")
        assertThat(settings.classScheduleClassName).isEqualTo("计算机25(5)")
    }

    @Test
    fun `班级课表字段一次往返后完全一致`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())

        store.update {
            it.copy(
                syncSourceType = SyncSourceType.CLASS_SCHEDULE,
                classScheduleBjdm = "116523137",
                classScheduleClassName = "计算机25(5)",
            )
        }

        assertThat(store.settings.first()).isEqualTo(
            UserSettings(
                syncSourceType = SyncSourceType.CLASS_SCHEDULE,
                classScheduleBjdm = "116523137",
                classScheduleClassName = "计算机25(5)",
            ),
        )
    }

    @Test
    fun `班级课表空字符串在写入时被移除且读回默认值`() = runBlocking {
        val store = DataStoreSettingsStore(FakeSettingsBackend())
        store.setClassSchedule("116523137", "计算机25(5)")
        assertThat(store.settings.first().classScheduleBjdm).isEqualTo("116523137")

        store.setClassSchedule("", "")

        assertThat(store.settings.first().classScheduleBjdm).isEmpty()
        assertThat(store.settings.first().classScheduleClassName).isEmpty()
    }

    @Test
    fun `脏数据的同步源类型回退个人课表`() = runBlocking {
        val backend = FakeSettingsBackend(
            mapOf(
                SettingsKeys.SYNC_SOURCE_TYPE to "MAGIC",
                SettingsKeys.CLASS_SCHEDULE_BJDM to 12345,
            ),
        )
        val store = DataStoreSettingsStore(backend)

        assertThat(store.settings.first().syncSourceType).isEqualTo(SyncSourceType.PERSONAL)
        assertThat(store.settings.first().classScheduleBjdm).isEmpty()
    }
}
