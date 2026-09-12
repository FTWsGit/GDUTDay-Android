package com.gdutday.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gdutday.core.model.Campus
import com.gdutday.core.model.ScheduleFetchStrategy
import com.gdutday.core.model.Term
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * DataStore 实例必须是进程内单例，否则同一个文件被多个 DataStore 打开会抛
 * `IllegalStateException`。用顶层委托可以保证整个进程只有一个实例；
 * 文件名固定为 `gdutday_settings`（Preferences 会自动加 `.preferences_pb` 后缀）。
 */
private val Context.gdutdaySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gdutday_settings",
)

/**
 * 设置读写的存储后端抽象。
 *
 * ## 为什么要抽出来
 *
 * `UserSettings` 与 Preferences 之间的映射（尤其是枚举解析、脏数据回退、作息表顺序）
 * 是本模块最容易出错、也最值得测试的部分，但它和 `DataStore` 的 `edit` 事务机制
 * 没有关系。抽象成"一个会随编辑变化的 `Map<String, Any?>`"之后，
 * 单元测试可以用一个内存假实现覆盖所有映射分支，不需要 Robolectric 或真实文件。
 *
 * 真实实现是 [DataStoreSettingsBackend]；测试实现在 `src/test`。
 */
internal interface SettingsBackend {

    /** 当前全部键值。键是 Preferences 的 name。 */
    val values: Flow<Map<String, Any?>>

    /**
     * 原子编辑。`transform` 收到的是当前快照的可变副本，改完后整体写回。
     *
     * 契约要求：**在一次事务内**完成读取与写回，实现方不得把它拆成两次调用。
     */
    suspend fun edit(transform: (MutableMap<String, Any?>) -> Unit)

    /** 清空全部设置。 */
    suspend fun clear()
}

/**
 * [SettingsBackend] 的 DataStore 实现。
 *
 * 写入时特意先 `clear()` 再按 map 重新写入，而不是增量 diff：设置项只有二十来个，
 * 整体覆写成本可以忽略，却能保证 map 里被删除的键一定从 DataStore 消失，
 * 逻辑比逐键比较简单得多，也不容易漏。
 */
internal class DataStoreSettingsBackend(
    private val dataStore: DataStore<Preferences>,
) : SettingsBackend {

    override val values: Flow<Map<String, Any?>> = dataStore.data.map { prefs ->
        prefs.asMap().entries.associate { (key, value) -> key.name to value }
    }

    override suspend fun edit(transform: (MutableMap<String, Any?>) -> Unit) {
        dataStore.edit { prefs ->
            val working = LinkedHashMap<String, Any?>()
            for ((key, value) in prefs.asMap()) working[key.name] = value
            transform(working)
            prefs.clear()
            for ((name, value) in working) write(prefs, name, value)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun write(prefs: MutablePreferences, name: String, value: Any?) {
        when (value) {
            is Boolean -> prefs[booleanPreferencesKey(name)] = value
            is Int -> prefs[intPreferencesKey(name)] = value
            is Long -> prefs[longPreferencesKey(name)] = value
            is Float -> prefs[floatPreferencesKey(name)] = value
            is String -> prefs[stringPreferencesKey(name)] = value
            is Set<*> -> prefs[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
            // null 与未知类型都不写入；因为上面 clear 过，效果就是删除该键。
            else -> Unit
        }
    }
}

/** 所有 Preferences 键名。集中定义避免字符串散落导致读写键对不上。 */
internal object SettingsKeys {
    const val SELECTED_TERM = "selected_term"
    const val CAMPUS = "campus"
    const val SCHEDULE_VIEW = "schedule_view"
    const val COURSE_BLOCK_ALPHA = "course_block_alpha"
    const val DIM_FINISHED_COURSES = "dim_finished_courses"
    const val COURSE_TEXT_COLOR = "course_text_color"
    const val BACKGROUND_IMAGE_URI = "background_image_uri"
    const val BACKGROUND_BLUR_DP = "background_blur_dp"
    const val SHOW_TEACHER = "show_teacher"
    const val SHOW_CLASSROOM = "show_classroom"
    const val SHOW_EXTRA_SECTIONS = "show_extra_sections"
    const val SHOW_WEEKEND = "show_weekend"
    const val CUSTOM_TIMETABLE_ENABLED = "custom_timetable_enabled"
    const val CUSTOM_TIMETABLE = "custom_timetable"
    const val FETCH_STRATEGY = "fetch_strategy"
    const val AUTO_SYNC_ON_LAUNCH = "auto_sync_on_launch"
    const val AUTO_SYNC_INTERVAL_HOURS = "auto_sync_interval_hours"
}

/**
 * `UserSettings` 与键值 [Map] 之间的双向映射。
 *
 * ## 解析策略：宽松 + 回退默认，绝不崩溃
 *
 * DataStore 里可能存在旧版本写下的、被手工改过的、或因降级而"未来版本"的值。
 * 读取时任何字段识别失败都回退到 [UserSettings] 的默认值（枚举用各自的
 * `fromName`，学期用 [Term.parse]），宁可显示默认设置也不能让设置页 / 课表页崩溃。
 */
internal object SettingsMapper {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun toUserSettings(map: Map<String, Any?>): UserSettings {
        val defaults = UserSettings()
        return UserSettings(
            selectedTerm = (map[SettingsKeys.SELECTED_TERM] as? String)?.let { Term.parse(it) },
            campus = parseCampus(map[SettingsKeys.CAMPUS] as? String),
            scheduleView = ScheduleView.fromName(map[SettingsKeys.SCHEDULE_VIEW] as? String),
            courseBlockAlpha = parseAlpha(map[SettingsKeys.COURSE_BLOCK_ALPHA], defaults.courseBlockAlpha),
            dimFinishedCourses = map.bool(SettingsKeys.DIM_FINISHED_COURSES, defaults.dimFinishedCourses),
            courseTextColor = CourseTextColor.fromName(map[SettingsKeys.COURSE_TEXT_COLOR] as? String),
            backgroundImageUri = (map[SettingsKeys.BACKGROUND_IMAGE_URI] as? String)?.takeIf { it.isNotBlank() },
            backgroundBlurDp = map.int(SettingsKeys.BACKGROUND_BLUR_DP, defaults.backgroundBlurDp).coerceAtLeast(0),
            showTeacher = map.bool(SettingsKeys.SHOW_TEACHER, defaults.showTeacher),
            showClassroom = map.bool(SettingsKeys.SHOW_CLASSROOM, defaults.showClassroom),
            showExtraSections = map.bool(SettingsKeys.SHOW_EXTRA_SECTIONS, defaults.showExtraSections),
            showWeekend = map.bool(SettingsKeys.SHOW_WEEKEND, defaults.showWeekend),
            customTimetableEnabled = map.bool(SettingsKeys.CUSTOM_TIMETABLE_ENABLED, defaults.customTimetableEnabled),
            customTimetable = parseTimetable(map[SettingsKeys.CUSTOM_TIMETABLE]),
            fetchStrategy = ScheduleFetchStrategy.fromName(map[SettingsKeys.FETCH_STRATEGY] as? String),
            autoSyncOnLaunch = map.bool(SettingsKeys.AUTO_SYNC_ON_LAUNCH, defaults.autoSyncOnLaunch),
            autoSyncIntervalHours = map.int(SettingsKeys.AUTO_SYNC_INTERVAL_HOURS, defaults.autoSyncIntervalHours)
                .coerceIn(0, MAX_SYNC_INTERVAL_HOURS),
        )
    }

    fun writeInto(map: MutableMap<String, Any?>, settings: UserSettings) {
        putOrRemove(map, SettingsKeys.SELECTED_TERM, settings.selectedTerm?.shortCode)
        map[SettingsKeys.CAMPUS] = settings.campus.name
        map[SettingsKeys.SCHEDULE_VIEW] = settings.scheduleView.name
        map[SettingsKeys.COURSE_BLOCK_ALPHA] = settings.courseBlockAlpha
        map[SettingsKeys.DIM_FINISHED_COURSES] = settings.dimFinishedCourses
        map[SettingsKeys.COURSE_TEXT_COLOR] = settings.courseTextColor.name
        putOrRemove(map, SettingsKeys.BACKGROUND_IMAGE_URI, settings.backgroundImageUri)
        map[SettingsKeys.BACKGROUND_BLUR_DP] = settings.backgroundBlurDp
        map[SettingsKeys.SHOW_TEACHER] = settings.showTeacher
        map[SettingsKeys.SHOW_CLASSROOM] = settings.showClassroom
        map[SettingsKeys.SHOW_EXTRA_SECTIONS] = settings.showExtraSections
        map[SettingsKeys.SHOW_WEEKEND] = settings.showWeekend
        map[SettingsKeys.CUSTOM_TIMETABLE_ENABLED] = settings.customTimetableEnabled
        map[SettingsKeys.CUSTOM_TIMETABLE] = encodeTimetable(settings.customTimetable)
        map[SettingsKeys.FETCH_STRATEGY] = settings.fetchStrategy.name
        map[SettingsKeys.AUTO_SYNC_ON_LAUNCH] = settings.autoSyncOnLaunch
        map[SettingsKeys.AUTO_SYNC_INTERVAL_HOURS] = settings.autoSyncIntervalHours
    }

    private fun putOrRemove(map: MutableMap<String, Any?>, key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    /**
     * 校区同时接受枚举名（我们写入的形式）与中文名（接口/旧数据可能出现的形式）。
     * 认不出来时回退 [Campus.UNKNOWN]，与字段默认值一致。
     */
    private fun parseCampus(raw: String?): Campus {
        if (raw.isNullOrBlank()) return Campus.UNKNOWN
        return Campus.entries.firstOrNull { it.name == raw } ?: Campus.fromRawName(raw)
    }

    private fun parseAlpha(raw: Any?, fallback: Float): Float {
        val value = (raw as? Number)?.toFloat() ?: return fallback
        if (!value.isFinite()) return fallback
        // 脏数据里可能存了 0 或 2.0；钳制到合法区间，避免课程块彻底消失。
        return value.coerceIn(UserSettings.ALPHA_RANGE)
    }

    /**
     * 自定义作息表存成 JSON 数组字符串，而**不用** `stringSetPreferencesKey`。
     *
     * 原因：Preferences 的 Set 在存取过程中不保证顺序（底层 HashSet / 不同版本实现顺序可能变化），
     * 而 24 项 `HH:mm` 是"第几节"的严格序号，一旦乱序整张作息表就错了。
     * JSON 数组天然保序，且能明确表达"这一项是第 3 节"。
     *
     * 为了兼容可能存在的旧数据，也接受 `List` / `Set` 形态。
     */
    private fun parseTimetable(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.filterIsInstance<String>()
        is Set<*> -> raw.filterIsInstance<String>().toList()
        is String -> try {
            if (raw.isBlank()) emptyList()
            else json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (e: Exception) {
            emptyList()
        }
        else -> emptyList()
    }

    private fun encodeTimetable(timetable: List<String>): String =
        buildJsonArray { timetable.forEach { add(it) } }.toString()

    private fun Map<String, Any?>.bool(key: String, fallback: Boolean): Boolean =
        this[key] as? Boolean ?: fallback

    private fun Map<String, Any?>.int(key: String, fallback: Int): Int =
        (this[key] as? Number)?.toInt() ?: fallback

    /** 自动同步间隔上限：一周。防止脏数据导致调度器被排到离谱的时间。 */
    private const val MAX_SYNC_INTERVAL_HOURS: Int = 24 * 7
}

/**
 * [SettingsStore] 的 DataStore Preferences 实现。
 *
 * 字段映射细节见 [SettingsMapper]；原子性由 `DataStore.edit {}` 保证，
 * [update] 中的读取与写回发生在同一个事务里，不存在"读-改-写"竞态。
 */
public class DataStoreSettingsStore internal constructor(
    private val backend: SettingsBackend,
) : SettingsStore {

    public constructor(context: Context) : this(
        DataStoreSettingsBackend(context.applicationContext.gdutdaySettingsDataStore),
    )

    override val settings: Flow<UserSettings> =
        backend.values.map { SettingsMapper.toUserSettings(it) }

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        backend.edit { map ->
            // transform 接收的是事务内快照，写回也在同一事务内完成。
            SettingsMapper.writeInto(map, transform(SettingsMapper.toUserSettings(map)))
        }
    }

    override suspend fun reset() {
        backend.clear()
    }
}
