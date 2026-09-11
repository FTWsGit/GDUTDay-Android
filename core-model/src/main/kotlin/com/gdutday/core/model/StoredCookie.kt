package com.gdutday.core.model

/**
 * 可持久化的 Cookie。
 *
 * [okhttp3.Cookie] 本身不可序列化，而登录态必须跨进程重启保留
 * （否则每次打开 App 都要重新登录，还会累积风控计数），
 * 所以定义这么一个纯数据镜像，落在 `core-datastore` 里加密存储。
 *
 * 放在 `core-model` 是分层要求：`core-datastore`（持久化层）要加密存它，
 * 却不该反向依赖协议层 `data-gdut`（core 必须是叶子）。OkHttp 的互转逻辑
 * 留在 `data-gdut` 的 `SessionCookieJar` 里，作为扩展函数存在。
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

    public companion object {
        /**
         * 按 RFC 6265 语义规范化 cookie 的 path，使 OkHttp [okhttp3.Cookie.matches]
         * 的前缀匹配稳定成立。
         *
         * 服务端下发的 `Set-Cookie: JSESSIONID=…; Path=/authserver` 没有尾斜杠。
         * OkHttp 的 path 匹配要求匹配的前缀要么等值、要么以 `/` 结尾、要么后续字符是 `/`，
         * 因此这里统一补齐尾斜杠（`/authserver` → `/authserver/`），
         * 保证 `/authserver/login`、`/authserver/checkNeedCaptcha.htl` 等子路径都能命中，
         * 避免登录态在冷启动复用后静默失效。
         *
         * 规则：
         * - 空路径 → `/`（根路径匹配所有请求）；
         * - `/` 本身保持 `/`（不能再补斜杠）；
         * - 缺前导 `/` 时补上；
         * - 非根且缺尾 `/` 时补上；
         * - 已规范化的路径原样返回。
         */
        public fun normalizePath(path: String): String = when {
            path.isEmpty() -> "/"
            path == "/" -> "/"
            !path.startsWith("/") -> "/$path/"
            !path.endsWith("/") -> "$path/"
            else -> path
        }
    }
}
