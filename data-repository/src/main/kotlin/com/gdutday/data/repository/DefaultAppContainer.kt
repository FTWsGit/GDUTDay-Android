package com.gdutday.data.repository

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.gdutday.core.datastore.DataStoreSettingsStore
import com.gdutday.core.datastore.FileCredentialStore
import com.gdutday.core.datastore.FileSessionStore
import com.gdutday.core.network.HttpClientFactory
import com.gdutday.core.network.HttpConfig
import com.gdutday.core.network.NetworkMonitor
import com.gdutday.data.gdut.auth.AuthServerClient
import com.gdutday.data.gdut.jxfw.JxfwClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * 手写容器。[AppContainer] 的默认实现，同时充当 WorkManager 的 [Configuration.Provider]。
 *
 * ## 构造零开销
 *
 * 所有字段都是 `by lazy`，构造函数体里**只有一次 `context.applicationContext` 读取**，
 * 不碰磁盘、不开数据库、不建网络连接。Application.onCreate 里 new 出它之后立刻返回，
 * 冷启动的预算全留给首屏。
 *
 * 注意"懒"的边界：`database` 的 lazy 只是拿到 `RoomDatabase` 实例，
 * 真正的 SQLite 打开发生在第一次查询；`sessionStore` / `credentialStore` 的 lazy
 * 只是在对象里记下路径，文件读同样推迟到第一次 `current()`。这两点是整套启动优化成立的前提。
 *
 * ## WorkManager 初始化链路（完整说明）
 *
 * `app` 的 manifest 通过 `tools:node="remove"` 删掉了 `androidx.startup` 里的
 * `WorkManagerInitializer`，目的是不让默认实现新建一套线程池。删除之后，
 * **必须有人在任何 `WorkManager.getInstance()` 之前显式初始化**，否则会抛
 * "WorkManager is not initialized properly"。
 *
 * 本类承担这个职责：[workManager] 的 lazy 块先调用 `WorkManager.initialize(...)` 再
 * `getInstance`。由于全 App 只有 [syncScheduler] 会碰 WorkManager，而 `syncScheduler`
 * 只在真正要排程时才被访问，所以初始化一定发生在第一次使用之前。
 * [workManagerConfiguration] 里装上 [GdutWorkerFactory]，把 Repository 注入 Worker，
 * 不必让 Worker 去摸全局单例。
 *
 * 兜底的 `catch (IllegalStateException)` 是为了兼容"默认初始化没被删掉"的构建变体
 * （例如单元测试或将来某次 manifest 改动）：已经初始化过就直接取已有实例。
 *
 * ## 契约
 *
 * - 全 App 只有一个根 [okHttpClient]（`newBuilder()` 派生的共享连接池，见 `HttpClientFactory`）；
 * - [jxfwClient] 每次新建，因为 `JxfwClient` 的状态在 CookieJar 里，
 *   缓存一个绑定了旧 cookie 的实例是"登录后仍提示未登录"的经典来源。
 *
 * @param destructiveMigrationFallback 仅 debug 传 true：迁移失败时重建数据库。
 *   release 传 true 会在一次 schema 变更后静默清空用户整学期数据。
 * @param logHttp 仅 debug 传 true：cookie 已过滤，但 URL/Location 里可能有一次性票据，发布版绝不能开。
 */
public class DefaultAppContainer(
    context: Context,
    private val destructiveMigrationFallback: Boolean,
    private val logHttp: Boolean,
) : AppContainer, Configuration.Provider {

    private val appContext: Context = context.applicationContext

    /** 与 Application 同生命周期的作用域，承载会话共享与后台排程。 */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val applicationContext: Context get() = appContext

    // ---------------------------------------------------------------- 基础设施

    override val okHttpClient: OkHttpClient by lazy {
        HttpClientFactory.create(appContext, if (logHttp) HttpConfig.DEBUG else HttpConfig.RELEASE)
    }

    override val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    override val database: com.gdutday.core.database.GdutDatabase by lazy {
        com.gdutday.core.database.GdutDatabase.build(appContext, destructiveMigrationFallback)
    }

    override val settingsStore: com.gdutday.core.datastore.SettingsStore by lazy {
        DataStoreSettingsStore(appContext)
    }

    override val sessionStore: com.gdutday.core.datastore.SessionStore by lazy {
        FileSessionStore(appContext)
    }

    override val credentialStore: com.gdutday.core.datastore.CredentialStore by lazy {
        FileCredentialStore(appContext)
    }

    // ---------------------------------------------------------------- 协议层

    override fun jxfwClient(session: com.gdutday.data.gdut.session.GdutSession?): JxfwClient? =
        session?.let { JxfwClient(okHttpClient, it) }

    override fun authServerClient(): AuthServerClient = AuthServerClient(okHttpClient)

    // ---------------------------------------------------------------- 业务层

    override val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            sessionStore = sessionStore,
            credentialStore = credentialStore,
            database = database,
            okHttpClient = okHttpClient,
            authClientFactory = { authServerClient() },
            jxfwClientFactory = { session -> JxfwClient(okHttpClient, session) },
            scope = appScope,
        )
    }

    /** 具体类型持有，[syncScheduler] 需要它的 [ScheduleRepositoryImpl.isSyncing]。 */
    private val scheduleRepositoryImpl: ScheduleRepositoryImpl by lazy {
        ScheduleRepositoryImpl(
            courseDao = database.courseDao(),
            examDao = database.examDao(),
            termMetaDao = database.termMetaDao(),
            courseColorDao = database.courseColorDao(),
            syncStateDao = database.syncStateDao(),
            settingsStore = settingsStore,
            sessionStore = sessionStore,
            authRepository = authRepository,
            jxfwClientFactory = { session, config ->
                JxfwClient(okHttpClient, session, config)
            },
        )
    }

    override val scheduleRepository: ScheduleRepository get() = scheduleRepositoryImpl

    override val gradeRepository: GradeRepository by lazy {
        GradeRepositoryImpl(
            gradeDao = database.gradeDao(),
            examDao = database.examDao(),
            syncStateDao = database.syncStateDao(),
            sessionStore = sessionStore,
            authRepository = authRepository,
            jxfwClientFactory = { session -> JxfwClient(okHttpClient, session) },
        )
    }

    override val libraryRepository: LibraryRepository by lazy {
        LibraryRepositoryImpl(sessionStore, settingsStore)
    }

    override val syncScheduler: SyncScheduler by lazy {
        SyncSchedulerImpl(
            workManager = workManager,
            settingsStore = settingsStore,
            syncing = scheduleRepositoryImpl.isSyncing,
        )
    }

    // ---------------------------------------------------------------- WorkManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(GdutWorkerFactory(scheduleRepositoryImpl, settingsStore))
            .build()

    /**
     * 第一次访问时初始化 WorkManager。见类注释的"初始化链路"。
     *
     * 之所以不借助 `Configuration.Provider` 的自动发现：WorkManager 只会从
     * `applicationContext`（即 `GdutDayApplication`）上找该接口，而 app 模块的
     * Application 未实现它，本容器实现只是为了把配置集中在一处并显式初始化。
     */
    private val workManager: WorkManager by lazy {
        try {
            WorkManager.initialize(appContext, workManagerConfiguration)
            WorkManager.getInstance(appContext)
        } catch (e: IllegalStateException) {
            // 已经初始化过（默认初始化未禁用、或测试环境）：直接用现成实例。
            WorkManager.getInstance(appContext)
        }
    }
}
