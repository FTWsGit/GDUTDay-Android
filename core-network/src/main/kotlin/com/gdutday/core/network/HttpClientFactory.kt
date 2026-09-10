package com.gdutday.core.network

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * [HttpClientFactory] 的配置。
 *
 * 每一项都与"启动速度 / 内存占用"这两个目标直接相关，注释里写了取舍理由。
 *
 * @property connectTimeout 建连超时。校园网 + 教务系统都不快，10 秒是"慢但没死"的合理界限。
 * @property readTimeout 读超时。课表 JSON 一次几十 KB，20 秒足够；
 *   太长会让"网络卡住"表现为界面长时间无响应。
 * @property callTimeout 整个调用（含重定向链）的上限。登录一次要跳 3~5 跳，
 *   60 秒能覆盖最坏情况，同时保证失败不会无限挂着。
 * @property maxIdleConnections 连接池里保留的空闲连接数。
 *   我们只跟 **2 个主机**通信（authserver + jxfw），默认值 5 是浪费；
 *   2 个足够让"登录后立刻拉课表"复用连接。
 * @property keepAliveDuration 空闲连接存活时间。30 秒：够覆盖一次同步流程里的连续请求，
 *   又不会让 App 退到后台后长时间占着 socket（省电、省内存）。
 * @property cacheBytes HTTP 响应缓存大小。**默认 0 = 不缓存**。
 *   课表数据必须新鲜，而且我们有 Room 做本地缓存，再叠一层 HTTP 缓存
 *   只会在冷启动时多一次磁盘 I/O，纯属负担。
 * @property logHttp 是否打印请求日志。发布版必须为 false ——
 *   日志里会出现 cookie 和加密后的密码字段，不该落到 logcat。
 */
public data class HttpConfig(
    public val connectTimeout: Duration = Duration.ofSeconds(10),
    public val readTimeout: Duration = Duration.ofSeconds(20),
    public val writeTimeout: Duration = Duration.ofSeconds(20),
    public val callTimeout: Duration = Duration.ofSeconds(60),
    public val maxIdleConnections: Int = 2,
    public val keepAliveDuration: Duration = Duration.ofSeconds(30),
    public val cacheBytes: Long = 0L,
    public val logHttp: Boolean = false,
) {
    public companion object {
        /** 发布版配置。 */
        public val RELEASE: HttpConfig = HttpConfig()

        /** 调试版配置：打开日志。 */
        public val DEBUG: HttpConfig = HttpConfig(logHttp = true)
    }
}

/**
 * OkHttpClient 工厂。
 *
 * ## 为什么全 App 只能有一个 OkHttpClient
 *
 * 每个 `OkHttpClient` 实例都自带**独立的连接池和线程池（Dispatcher）**。
 * `newBuilder()` 派生的实例会共享这两个池，所以派生是廉价的；
 * 但直接 `OkHttpClient()` 新建就是另一套线程 + 另一批 socket。
 *
 * 旧 Java 后端的 `OkHttpUtils.makeOkhttpClient()` 就是每次调用都新建一个，
 * 还在注释里写"严格使用工具 new okhttp…最后记得释放和清空"——
 * 靠人工记得释放来管理线程池，是典型的资源泄漏来源。
 *
 * 本项目的约定：
 * - **这里创建唯一一个根实例**，由 `AppContainer` 持有；
 * - `data-gdut` 里的各个 Client 一律用 `newBuilder()` 派生（绑定自己的 CookieJar、
 *   关掉自动重定向），派生实例共享连接池与线程池；
 * - 派生实例用完**不需要**也**不应该**手动关闭。
 *
 * ## 与启动速度的关系
 *
 * OkHttpClient 的构造本身不重（不预建连接、不起线程），
 * 真正贵的是第一次请求时的 TLS 握手。所以：
 * - 冷启动**不要**为了"预热"而提前建连（那只会拖慢首屏，还费电）；
 * - 首屏直接读 Room，网络同步放到后台。
 */
public object HttpClientFactory {

    /**
     * 创建根实例。
     *
     * @param context 仅用于在 [HttpConfig.cacheBytes] > 0 时定位缓存目录，传 applicationContext。
     */
    public fun create(context: Context, config: HttpConfig = HttpConfig.RELEASE): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .callTimeout(config.callTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .connectionPool(
                ConnectionPool(
                    config.maxIdleConnections,
                    config.keepAliveDuration.toMillis(),
                    TimeUnit.MILLISECONDS,
                ),
            )
            // 登录流程必须自己观察每一跳，所以根实例就关掉自动重定向。
            // data-gdut 的 RedirectFollower 会手动跟。
            .followRedirects(false)
            .followSslRedirects(false)
            // 教务系统的证书链在国内根证书下偶发问题，但**不要**因此放松校验。
            // 这里显式声明使用系统默认的证书校验器与主机名校验器。
            .retryOnConnectionFailure(true)

        if (config.cacheBytes > 0) {
            builder.cache(Cache(context.cacheDir.resolve("http"), config.cacheBytes))
        }

        if (config.logHttp) {
            builder.addInterceptor(HttpLoggingInterceptorSafe)
        }

        return builder.build()
    }

    /**
     * 派生一个绑定了指定 CookieJar 的实例。
     *
     * 与根实例共享连接池和线程池，因此**廉价**，可以每次登录新建一个。
     */
    public fun withCookieJar(
        root: OkHttpClient,
        cookieJar: com.gdutday.data.gdut.http.SessionCookieJar,
    ): OkHttpClient = root.newBuilder().cookieJar(cookieJar).build()
}
