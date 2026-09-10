package com.gdutday.data.gdut.http

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 可持久化的 Cookie。
 *
 * [okhttp3.Cookie] 本身不可序列化，而登录态必须跨进程重启保留
 * （否则每次打开 App 都要重新登录，还会累积风控计数），
 * 所以定义这么一个纯数据镜像，落在 `core-datastore` 里加密存储。
 *
 * @property expiresAtMillis 过期时刻（epoch millis）。[Long.MAX_VALUE] 表示会话级 cookie
 *   （`persistent == false`）—— 这类 cookie 浏览器关掉就没了，但我们**仍然保存**，
 *   因为 `JSESSIONID` 就是会话级的，而它正是登录态的载体。
 * @property hostOnly true 表示只匹配精确主机（cookie 未带 `Domain` 属性），
 *   false 表示匹配该域及其子域。还原时必须保留这个区别，否则匹配范围会出错。
 */
public data class StoredCookie(
    public val name: String,
    public val value: String,
    public val domain: String,
    public val path: String,
    public val expiresAtMillis: Long,
    public val secure: Boolean,
    public val httpOnly: Boolean,
    public val hostOnly: Boolean,
) {
    /** 是否已过期。会话级 cookie（[expiresAtMillis] 为 MAX_VALUE）永不过期。 */
    public fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis != Long.MAX_VALUE && expiresAtMillis <= nowMillis

    public fun toOkHttpCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .path(path)
        .apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            if (secure) secure()
            if (httpOnly) httpOnly()
            // 会话级 cookie 不设 expiresAt，还原成非持久 cookie；
            // 但由于我们每次都从 snapshot 重建，效果等同于持久保存。
            if (expiresAtMillis != Long.MAX_VALUE) expiresAt(expiresAtMillis)
        }
        .build()

    public companion object {
        public fun from(cookie: Cookie): StoredCookie = StoredCookie(
            name = cookie.name,
            value = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            expiresAtMillis = if (cookie.persistent) cookie.expiresAt else Long.MAX_VALUE,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly,
        )
    }
}

/**
 * 内存 CookieJar，带快照/还原能力。
 *
 * ## 为什么自己写而不用 `okhttp3-cookie-helper` 之类的库
 *
 * 旧 Java 后端引了 `okhttp3-cookie-helper:1.0.0`，还自己写了个 `GdutDayCookieJar`。
 * 需要的功能其实只有 30 行：存下来、按 URL 取出来、能导出成可序列化形式。
 * 自己写可以精确控制两件对本项目很关键的事：
 *
 * 1. **hostOnly 语义**：`JSESSIONID` 下发时没有 `Domain` 属性，属于 host-only cookie，
 *    只应发回原主机。还原时若统一按 `domain(...)` 构造，会变成"含子域"，
 *    把 authserver 的 JSESSIONID 误发给 jxfw，导致会话错乱。
 * 2. **会话级 cookie 也要持久化**：OkHttp 的 `Cookie.persistent` 对没有 `Expires/Max-Age`
 *    的 cookie 返回 false，标准 CookieJar 实现通常直接丢弃它们 ——
 *    而 `JSESSIONID` 恰恰就是这种。丢了就等于登录态丢了。
 *
 * ## 线程安全
 *
 * OkHttp 会从连接池线程回调 [saveFromResponse]，同时 ViewModel 可能在主线程读快照。
 * 这里用 `synchronized` 保护整个列表：cookie 数量只有个位数，锁竞争可以忽略，
 * 比引入并发容器的迭代器语义问题简单得多。
 */
public class SessionCookieJar(
    initial: List<StoredCookie> = emptyList(),
) : CookieJar {

    private val lock = Any()
    private val store = LinkedHashMap<String, StoredCookie>()

    init {
        restore(initial)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (c in cookies) {
                // 同名同域同路径的 cookie 覆盖旧值（JSESSIONID 会被反复下发）
                store[keyOf(c.name, c.domain, c.path)] = StoredCookie.from(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(lock) { store.values.toList() }
        val matched = mutableListOf<Cookie>()
        val expiredKeys = mutableListOf<String>()
        for (sc in snapshot) {
            if (sc.isExpired(now)) {
                expiredKeys += keyOf(sc.name, sc.domain, sc.path)
                continue
            }
            val cookie = runCatching { sc.toOkHttpCookie() }.getOrNull() ?: continue
            if (cookie.matches(url)) matched += cookie
        }
        // 顺手清理过期项，避免长期运行后 store 无限增长
        if (expiredKeys.isNotEmpty()) synchronized(lock) { expiredKeys.forEach { store.remove(it) } }
        return matched
    }

    /** 导出当前全部 cookie（含未过期的会话级 cookie），用于持久化。 */
    public fun snapshot(): List<StoredCookie> = synchronized(lock) { store.values.toList() }

    /** 只导出指定主机（及其子域）的 cookie。 */
    public fun snapshotForHost(host: String): List<StoredCookie> =
        snapshot().filter { host.endsWith(it.domain.removePrefix("."), ignoreCase = true) || it.domain.equals(host, ignoreCase = true) }

    /**
     * 拼成 `Cookie` 请求头的值，如 `JSESSIONID=xxx; route=yyy`。
     *
     * 只在需要**手动**指定 cookie 的场景用（例如复用同一个 OkHttpClient 但要隔离两个用户的会话）。
     * 正常流程交给 CookieJar 自动处理即可。
     */
    public fun cookieHeaderValue(url: HttpUrl): String =
        loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }

    /** 是否已经持有某主机的会话 cookie。用作"看起来登录过"的快速判断。 */
    public fun hasSessionFor(host: String): Boolean =
        snapshotForHost(host).any { it.name.equals("JSESSIONID", ignoreCase = true) }

    /** 清空并载入新的 cookie 集合。 */
    public fun restore(cookies: List<StoredCookie>) {
        synchronized(lock) {
            store.clear()
            val now = System.currentTimeMillis()
            for (c in cookies) {
                if (c.isExpired(now)) continue
                store[keyOf(c.name, c.domain, c.path)] = c
            }
        }
    }

    public fun clear() {
        synchronized(lock) { store.clear() }
    }

    public val size: Int get() = synchronized(lock) { store.size }

    private fun keyOf(name: String, domain: String, path: String): String =
        "${name.lowercase()}|${domain.lowercase()}|$path"

    override fun toString(): String = "SessionCookieJar(${snapshot().joinToString { "${it.name}@${it.domain}" }})"
}
