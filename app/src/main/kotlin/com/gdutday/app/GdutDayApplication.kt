package com.gdutday.app

import android.app.Application
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.AppContainerHolder
import com.gdutday.data.repository.DefaultAppContainer
import com.gdutday.data.repository.SyncListeners

/**
 * 应用入口。
 *
 * ## onCreate 里能做什么、不能做什么
 *
 * 冷启动时间的预算几乎全在 `Application.onCreate` + `Activity.onCreate` 这两段。
 * 本项目首屏是课表页，要让它尽快出现，所以 **onCreate 里只做一件事：构造容器对象**。
 *
 * [DefaultAppContainer] 的所有字段都是 `by lazy`，构造它本身是 O(1) 的，
 * 不碰磁盘、不碰网络、不开数据库。真正的开销推迟到第一次访问对应字段时。
 *
 * 明确**不做**的事（每一项都会拖慢冷启动，且都可推迟）：
 * - ❌ 预热网络（提前建 TLS 连接）：首屏读 Room 就够了，预热反而占 CPU
 * - ❌ 初始化 WorkManager 之外的任何 SDK：本项目没有第三方 SDK
 * - ❌ 读 DataStore：设置项在课表页真正需要时才读
 * - ❌ 打开数据库：Room 的 `databaseBuilder.build()` 不打开 SQLite，
 *      第一次查询才打开，这是它默认就有的好性质，别用 `openHelper.writableDatabase` 破坏它
 * - ❌ 检查更新 / 上报统计：本项目没有这类功能
 *
 * @property container 全 App 唯一的依赖容器。
 *   Composable 通过 `LocalContext.current.applicationContext as GdutDayApplication` 取得，
 *   Widget 进程同理（见 widget 模块）。
 */
public class GdutDayApplication : Application() {

    public lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val built = DefaultAppContainer(
            context = this,
            // debug 构建允许"迁移不了就重建数据库"，release 绝不允许
            // （那会静默清空用户的整学期课表和成绩）
            destructiveMigrationFallback = BuildConfig.DEBUG,
            logHttp = BuildConfig.DEBUG,
        )
        container = built
        // widget 模块拿不到 GdutDayApplication 这个类型（app 依赖 widget，反向依赖会成环），
        // 所以容器还要注册到 data-repository 的进程级 holder 里。
        // 理由与取舍见 AppContainerHolder 的类注释。
        AppContainerHolder.install(built)

        // 同步完成后刷新桌面插件。
        // 这个接线必须放在 app 模块：data-repository 不能引用 widget（会成环），
        // widget 也不知道"同步"这件事什么时候发生。app 是唯一同时看得到两边的组装层。
        // 依赖倒置的完整理由见 SyncListeners 的类注释。
        SyncListeners.register {
            // 插件刷新失败不该影响任何东西，WidgetUpdateManager 内部已容错；
            // 这里再包一层是因为 Application 级别的回调崩了会带走整个进程。
            runCatching { com.gdutday.widget.WidgetUpdateManager.updateAll(this@GdutDayApplication) }
        }

        // 刻意**不在这里**启动"下节课倒计时"的刷新调度。原因：
        // 1. 调度链的起点是 widget receiver 的 onEnabled（用户把插件拖到桌面时），
        //    那里会调 NextClassRefreshScheduler.refreshNow，之后 worker 每次执行完
        //    自己按分级策略重排下一次 —— 链条是自维持的；
        // 2. WorkManager 的任务队列**跨进程死亡和设备重启持久化**，不需要每次启动重新播种；
        // 3. 如果在这里无条件排一次，那么没放插件的用户每次冷启动都会白白唤醒一个 worker
        //    去查一遍课表，纯粹浪费电。
    }
}

/**
 * 从任意 Context 取容器。
 *
 * 先走 ContextWrapper 链找到 [GdutDayApplication]（编译期类型安全，最快），
 * 找不到再回退到 [AppContainerHolder]（覆盖 instrumentation 等
 * applicationContext 类型不对的环境）。
 *
 * 两条路都失败时抛明确的异常，而不是返回一个新建的容器 ——
 * 后者会导致两个容器、两份缓存，表现为"改了设置不生效"这类极难排查的问题。
 */
public val android.content.Context.appContainer: AppContainer
    get() {
        var ctx: android.content.Context = this
        while (ctx is android.content.ContextWrapper) {
            if (ctx is GdutDayApplication) return ctx.container
            ctx = ctx.baseContext
        }
        if (AppContainerHolder.isInstalled) return AppContainerHolder.get()
        throw IllegalStateException(
            "无法从 $this 取得 AppContainer：Application 不是 GdutDayApplication，" +
                "且 AppContainerHolder 也未被安装。检查 AndroidManifest 里的 android:name。",
        )
    }
