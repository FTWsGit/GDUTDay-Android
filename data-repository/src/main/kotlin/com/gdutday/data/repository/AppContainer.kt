package com.gdutday.data.repository

import android.content.Context

/**
 * 手写依赖容器。
 *
 * ## 为什么不用 Hilt / Dagger
 *
 * 三个理由，都直接对应本项目的两个核心目标（启动速度、资源占用）：
 *
 * 1. **启动速度**：Hilt 生成的组件在 Application.onCreate 里做依赖图的实例化，
 *    冷启动通常多花 30~100ms；Dagger 的 `Lazy`/`Provider` 包装还会多一层间接。
 *    手写容器就是一个 `by lazy` 的对象图，第一个字段被访问时才构造，**零启动开销**。
 * 2. **包体积**：Hilt + Dagger runtime + 生成代码约 1.5~2MB。这个项目总共才十几个类需要注入。
 * 3. **构建稳定性**：Hilt 的版本必须与 Kotlin/KSP/AGP 三方对齐。本项目用的是
 *    AGP 9 的 built-in Kotlin（见 gradle.properties 的注释），
 *    再叠一个 KSP 处理器等于把最难调的那类构建问题引进来。
 *
 * 代价是没有编译期校验"某个依赖忘了提供"。项目这个规模（<20 个可注入类型）完全可控，
 * 而且容器是**一个文件**，漏了什么一眼就能看到。
 *
 * ## 约定
 *
 * - 所有字段用 `by lazy`，构造顺序即依赖顺序；
 * - **不要在字段初始化里做 I/O**（读文件、开数据库）。数据库的 `by lazy` 只是拿到
 *   `RoomDatabase` 实例，真正的 SQLite 打开发生在第一次查询时；
 * - Application 持有一个实例；Widget 的 `GlanceAppWidget` 通过
 *   `(context.applicationContext as GdutDayApplication).container` 拿到同一个。
 */
public interface AppContainer {

    // ---------------------------------------------------------------- 基础设施

    public val applicationContext: Context

    /** 全 App 唯一的根 OkHttpClient。见 `HttpClientFactory` 的注释说明为什么只能有一个。 */
    public val okHttpClient: okhttp3.OkHttpClient

    public val networkMonitor: com.gdutday.core.network.NetworkMonitor

    public val database: com.gdutday.core.database.GdutDatabase

    public val settingsStore: com.gdutday.core.datastore.SettingsStore

    public val sessionStore: com.gdutday.core.datastore.SessionStore

    public val credentialStore: com.gdutday.core.datastore.CredentialStore

    // ---------------------------------------------------------------- 协议层
    // data-gdut 的 Client 是**无状态**的（状态在 SessionCookieJar 里，每次登录新建），
    // 所以这里只暴露构造它们的工厂，不缓存实例。
    // 缓存一个绑定了旧 cookie 的 Client 是"登录后还是提示未登录"这类 bug 的经典来源。

    /** 用给定会话构造一个 jxfw 客户端。会话为 null 时返回 null。 */
    public fun jxfwClient(session: com.gdutday.data.gdut.session.GdutSession?): com.gdutday.data.gdut.jxfw.JxfwClient?

    /** 构造一个统一认证客户端（每次登录都应该新建，不复用）。 */
    public fun authServerClient(): com.gdutday.data.gdut.auth.AuthServerClient

    // ---------------------------------------------------------------- 业务层

    public val authRepository: AuthRepository

    public val scheduleRepository: ScheduleRepository

    public val gradeRepository: GradeRepository

    public val libraryRepository: LibraryRepository

    /** 后台同步的编排者。WorkManager 的 Worker 只调用它，不含业务逻辑。 */
    public val syncScheduler: SyncScheduler
}

/**
 * 同步调度。
 *
 * ## 为什么用 WorkManager 而不是 AlarmManager / 前台 Service
 *
 * - 课表同步是**可延迟的**（晚几分钟毫无影响），正好是 WorkManager 的定位；
 * - Doze 模式下 AlarmManager 的 setExact 会被推迟，setExactAndAllowWhileIdle
 *   又要求前台权限，得不偿失；
 * - WorkManager 自带重试、约束（有网才跑）、电量感知，
 *   以及**进程被杀后仍会恢复**——这一点对"每周自动刷新"很关键。
 *
 * 旧小程序做不到这些：它的"每周自动刷新"是**打开页面时**才检查
 * （`commonFun.js` 的 `checkAutoUpdateClassData`），用户不打开就永远不刷新。
 * 本项目改为真正的后台周期任务 + 打开时的即时检查双保险。
 */
public interface SyncScheduler {

    /**
     * 注册周期性同步。
     *
     * 约束：需要网络、非低电量。周期取 [com.gdutday.core.datastore.UserSettings.autoSyncIntervalHours]，
     * 但 WorkManager 的周期任务**最小间隔是 15 分钟**，比这更小的设置会被钳制。
     *
     * 用 `ExistingPeriodicWorkPolicy.UPDATE`（而不是 KEEP 或 REPLACE）：
     * - KEEP：用户改了间隔不会生效；
     * - REPLACE：会取消正在跑的任务并重新开始，浪费已完成的进度；
     * - UPDATE：保留进度，只更新约束和周期。这是唯一正确的选择。
     */
    public fun schedulePeriodicSync()

    /** 取消周期同步（用户在设置里关掉自动同步时）。 */
    public fun cancelPeriodicSync()

    /**
     * 触发一次性同步。用户下拉刷新时调用。
     *
     * @param expedited 是否申请加急。下拉刷新应该为 true（用户正在等），
     *   后台唤醒应该为 false（省电）。
     */
    public fun requestImmediateSync(expedited: Boolean = false)

    /** 当前是否有同步在跑。UI 用来禁用刷新按钮、避免重复触发。 */
    public val isSyncing: kotlinx.coroutines.flow.StateFlow<Boolean>
}
