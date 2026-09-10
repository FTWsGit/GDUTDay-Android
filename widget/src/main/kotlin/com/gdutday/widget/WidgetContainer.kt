package com.gdutday.widget

import android.content.Context
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.AppContainerHolder

/**
 * 从任意 [Context] 取到全 App 唯一的 [AppContainer]。
 *
 * ## 为什么不能像 UI 层那样直接强转 Application
 *
 * 依赖方向决定了 widget 模块**不能**引用 `com.gdutday.app` 里的任何东西：
 * `app` 依赖 `widget`（app 的 manifest 里声明了两个 widget receiver），
 * widget 反向依赖 `app` 会形成 Gradle 项目环。
 * 而项目里那个好用的 `Context.appContainer` 扩展属性恰恰定义在 app 模块的
 * `GdutDayApplication.kt` 里，编译期不可见。
 *
 * ## 为什么用 holder 而不是反射
 *
 * 这里曾经用反射按**返回类型**扫描 Application 的公开无参方法来找容器
 * （按类型而非按方法名，以绕过 R8 的方法名混淆）。那个方案能工作，但有三个问题：
 *
 * 1. **仍然是反射**：每次插件重组都要扫一遍方法表。插件更新虽然低频，
 *    但它发生在 launcher 拉起我们进程的时刻，正是最该省 CPU 的时候；
 * 2. **失败只能在运行时发现**：如果哪天 `GdutDayApplication` 的容器属性
 *    被改成 `private` 或换了类型，编译期毫无提示，插件会静默失效；
 * 3. **依赖"Application 一定有某个公开 getter"这个隐含约定**，而这个约定没有任何东西保证。
 *
 * 改成 [AppContainerHolder]（定义在 widget 已经依赖的 `data-repository` 里）之后：
 * 编译期类型安全、零反射开销、写入点唯一（`GdutDayApplication.onCreate`）。
 * 代价是引入一个进程级全局状态 —— 但容器本来就**应该**是进程级单例，
 * 详见 `AppContainerHolder` 类注释里的完整权衡。
 *
 * ## 缓存
 *
 * holder 内部已经是 `@Volatile` 字段读取，这里不再额外缓存一层。
 * 多缓存一份只会在 holder 被 `clearForTesting()` 重置后留下陈旧引用。
 */
internal object WidgetContainerAccess {

    /**
     * @throws IllegalStateException 容器尚未安装。错误信息由 holder 统一给出，
     *   里面已经写明了最可能的原因，这里不重复包装 —— 多包一层只会让栈更难读。
     */
    fun get(context: Context): AppContainer = AppContainerHolder.get()
}

/**
 * 简写，语义与 app 模块的 `Context.appContainer` 一致。
 *
 * 保留 [Context] 参数（虽然当前实现用不到它）是为了让调用点写法与 UI 层统一，
 * 也为将来"插件跑在独立进程"这种情况留出改造空间 —— 那时确实需要 Context
 * 去构造该进程自己的容器。
 */
@Suppress("UNUSED_PARAMETER")
internal fun Context.widgetContainer(): AppContainer = WidgetContainerAccess.get(this)
