package com.gdutday.data.repository

/**
 * 全 App 唯一 [AppContainer] 的进程内访问点。
 *
 * ## 为什么需要这么一个"全局单例持有者"
 *
 * 正常做法是让 Composable 通过 `LocalContext.current.applicationContext as GdutDayApplication`
 * 拿到容器（`app` 模块里的 `Context.appContainer` 扩展属性就是这么做的）。
 * 但 **`widget` 模块拿不到 `GdutDayApplication` 这个类型**：
 * `app` 依赖 `widget`（app 的 manifest 里声明了两个 widget receiver），
 * widget 反向依赖 app 会形成 Gradle 项目环。
 *
 * 于是有三种选择：
 *
 * | 方案 | 问题 |
 * |---|---|
 * | ① widget 里用反射按方法名找 `getContainer()` | R8 全量模式下方法名被混淆，必须配 keep 规则，漏了就是 release 崩溃 |
 * | ② widget 里用反射按**返回类型**匹配 | 能绕过混淆，但仍是反射：每次插件重组都要扫一遍方法表，且"找不到"只能在运行时发现 |
 * | ③ **在双方都能看到的 `data-repository` 里放一个静态 holder** | 引入一个进程级全局可变状态 |
 *
 * 选 ③。全局可变状态通常是坏味道，但这里的约束很硬：
 * - 写入点**只有一个**（`GdutDayApplication.onCreate`），且在进程生命周期内只发生一次；
 * - 之后全部是读；
 * - 容器本身**就应该是**进程级单例（两个容器 = 两份 OkHttp 连接池 + 两份 Room 缓存，
 *   会表现为"改了设置不生效"这类极难排查的问题）。
 *
 * 换句话说，这个 holder 不是引入了全局状态，而是**把一个本来就存在的全局状态显式化**了。
 * 相比之下反射方案把"容器在哪"这件事藏进了运行时，才是真正的问题。
 *
 * ## 线程安全
 *
 * `@Volatile` 足够：写入发生在 `Application.onCreate`（主线程，早于任何组件），
 * 读取可能发生在 WorkManager 的后台线程。volatile 保证了可见性，
 * 而"先写后读"的时序由 Android 的组件生命周期保证（onCreate 一定先于 receiver/worker）。
 * 不需要锁。
 */
public object AppContainerHolder {

    @Volatile
    private var instance: AppContainer? = null

    /**
     * 安装容器。**只应由 `Application.onCreate` 调用一次。**
     *
     * @throws IllegalStateException 重复安装。这不是防御性编程的洁癖 ——
     *   真的装了两个容器意味着两套缓存和两个 OkHttp 连接池，
     *   而它唯一的成因是有人多写了一个 Application 类或在别处调了 install。
     *   这种情况下**尽早崩溃**远好于让用户遇到"设置不生效"。
     */
    public fun install(container: AppContainer) {
        val existing = instance
        check(existing == null || existing === container) {
            "AppContainer 被重复安装。已有的=$existing，新传入的=$container。" +
                "检查是否有多个 Application 类，或者 install 被调用了多次。"
        }
        instance = container
    }

    /**
     * 取容器。
     *
     * @throws IllegalStateException 尚未安装。错误信息里直接给出最可能的原因，
     *   因为"widget 进程里拿不到容器"这类问题的排查成本很高。
     */
    public fun get(): AppContainer = instance ?: throw IllegalStateException(
        "AppContainer 尚未安装。它应当由 GdutDayApplication.onCreate 调用 " +
            "AppContainerHolder.install() 完成。若你在单元测试里看到这条，" +
            "说明测试没有走 Application 初始化 —— 请直接构造 DefaultAppContainer 并 install。",
    )

    /** 是否已安装。诊断页和测试用，不抛异常。 */
    public val isInstalled: Boolean get() = instance != null

    /**
     * 清空。**仅供测试**（每个测试用例需要独立容器时）。
     *
     * 生产代码调用它等于自毁：之后所有 widget/后台任务都会抛异常。
     */
    public fun clearForTesting() {
        instance = null
    }
}
