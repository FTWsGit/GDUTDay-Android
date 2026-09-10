package com.gdutday.data.repository

/**
 * 同步完成后的回调。
 *
 * 典型用途：桌面插件刷新。
 *
 * ## 为什么要有这么一层，而不是让 Repository 直接调 Widget
 *
 * 因为**模块依赖方向不允许**：`widget` 依赖 `data-repository`（插件要读课表数据），
 * 所以 `data-repository` 反过来引用 `WidgetUpdateManager` 会形成 Gradle 项目环。
 *
 * 三种可选方案：
 *
 * | 方案 | 问题 |
 * |---|---|
 * | ① 把 `WidgetUpdateManager` 下沉到 data-repository | widget 模块要依赖 Glance，把 UI 依赖塞进数据层，方向反了 |
 * | ② 让 widget 观察 `SyncStateEntity` 的 Flow 自己刷新 | 可行，但插件进程会被每次同步唤醒一次**并读取整个 sync_state 表**，且"跨天刷新"仍然要另一套机制 |
 * | ③ **在 data-repository 定义回调接口，由 app 在启动时注册实现** | 多一个注册步骤，但依赖方向干净，且天然可扩展 |
 *
 * 选 ③。这是标准的"依赖倒置"：高层模块（数据编排）定义抽象，
 * 低层模块（插件）的实现由**组装层**（`app` 的 Application）注入。
 *
 * ## 为什么用全局注册表而不是构造函数注入
 *
 * 构造函数注入（`DefaultAppContainer(syncListeners = listOf(...))`）类型更安全，
 * 但它要求 `app` 在构造容器**之前**就能引用 widget 的类 —— 这一点确实满足
 * （app 依赖 widget）。所以严格说构造注入也可行。
 *
 * 仍然选注册表，是因为监听者的注册时机可能晚于容器构造：
 * 例如将来要加"同步完成后发通知"，而通知渠道的创建依赖用户是否授予了
 * `POST_NOTIFICATIONS`，那是要等 Activity 起来才知道的。
 * 注册表允许**任意时刻**追加监听者，构造注入不行。
 */
public fun interface SyncListener {
    /**
     * 同步结束时回调。
     *
     * **成功和失败都会调用** —— 失败时插件也该知道（例如把"上次更新"的时间显示出来，
     * 或者停止"正在同步"的指示）。用 [SyncInfo.success] 区分。
     *
     * 在调用方的协程上下文里执行（通常是 `Dispatchers.IO`），实现里**不要切主线程做重活**。
     * 抛出的异常会被 [SyncListeners] 捕获并记录，不会影响其它监听者，
     * 也不会让同步本身失败 —— 数据已经落库了，插件刷新失败不该让用户看到"同步失败"。
     */
    public suspend fun onSyncCompleted(info: SyncInfo)
}

/**
 * [SyncListener] 的进程内注册表。
 *
 * 线程安全：用 `CopyOnWriteArrayList`。读远多于写（每次同步读一遍，注册只发生在启动时），
 * 正是 COW 的适用场景，不需要额外的锁。
 */
public object SyncListeners {

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SyncListener>()

    /** 注册。重复注册同一个实例会被忽略（用引用相等判断）。 */
    public fun register(listener: SyncListener) {
        if (listeners.none { it === listener }) listeners += listener
    }

    public fun unregister(listener: SyncListener) {
        listeners.removeAll { it === listener }
    }

    /** 当前注册数量。诊断页用。 */
    public val size: Int get() = listeners.size

    /**
     * 通知全部监听者。
     *
     * 逐个串行调用而不是并发：监听者通常很轻（刷新插件），并发反而会和
     * Room 的写事务抢 SQLite 锁。串行也让日志顺序可预测。
     *
     * 单个监听者抛异常只记录不传播，理由见 [SyncListener.onSyncCompleted] 的注释。
     */
    public suspend fun notifyCompleted(info: SyncInfo) {
        for (listener in listeners) {
            try {
                listener.onSyncCompleted(info)
            } catch (e: Exception) {
                android.util.Log.w(
                    "GdutDaySync",
                    "同步监听者 ${listener.javaClass.simpleName} 执行失败，已忽略: $e",
                )
            }
        }
    }

    /** 清空。**仅供测试。** */
    public fun clearForTesting() = listeners.clear()
}
