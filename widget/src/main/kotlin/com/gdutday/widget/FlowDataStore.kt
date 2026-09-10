package com.gdutday.widget

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow

/**
 * 只读的 [DataStore]，数据源是一个普通 [Flow]。
 *
 * ## 它为什么存在
 *
 * Glance 的 `GlanceStateDefinition` 要求返回 `DataStore<T>`，而标准的
 * `PreferencesDataStore` / `DataStoreFactory` 都是**持久化**存储，不适合我们这种
 * "状态从 Room 实时派生"的场景。Glance 读取状态时只做 `dataStore.data.first()`，
 * 从不监听 `data` 的后续发射，所以只要 `data` 每次被收集时重新订阅上游 Flow，
 * 每次 `GlanceAppWidget.update()` 就都能拿到最新的数据库快照。
 *
 * ## 为什么 `updateData` 直接抛异常
 *
 * 插件的状态是**派生数据**，唯一真相在 Room/DataStore。如果有人调用
 * `updateAppWidgetState` 往这里写，说明状态模型设计跑偏了（应该有别的上游 Flow），
 * 与其静默吞掉写入造成"改了没生效"，不如立刻抛出，把问题暴露在开发阶段。
 * 本项目所有刷新路径都是 `update()` / `WidgetUpdateManager.updateAll()`，不会触发它。
 */
internal class FlowDataStore<T>(
    private val source: () -> Flow<T>,
) : DataStore<T> {

    override val data: Flow<T> get() = source()

    override suspend fun updateData(transform: suspend (T) -> T): T =
        throw UnsupportedOperationException(
            "插件状态是只读的派生数据，不能通过 updateAppWidgetState 写入。",
        )
}
