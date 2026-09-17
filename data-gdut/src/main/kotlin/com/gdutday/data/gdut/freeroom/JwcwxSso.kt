package com.gdutday.data.gdut.freeroom

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.GdutSession
import com.gdutday.data.gdut.http.SessionCookieJar
import com.gdutday.data.gdut.http.asBrowserNavigation
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 打通 jwcwx（微信公众号教务 Web 端）的会话。
 *
 * ## 为什么不需要重新输密码
 *
 * jwcwx 走标准 CAS：访问 `jwcwxBase/login/cas` 会 302 到 authserver。
 * 如果当前会话已持有 authserver 的 TGT（`rememberMe=true` 登录后下发），
 * authserver **不会**再渲染登录页，而是直接带着 ticket 302 回 jwcwx ——
 * 全程无交互，App 内只需要跟随跳转链，cookie jar 里就会多出 jwcwx 域的 JSESSIONID。
 * TGT 过期时 authserver 会渲染登录页 HTML，此时只能让用户重新登录，绝不自动重试
 * （与滑块风控同一处置纪律）。
 *
 * ## ⚠ 302 会降级 http
 *
 * 实测（2026-09-17）：jwcwx 的 302 Location 是 `http://jwcwx...`，
 * 而其 80 端口在部分网络下不通。这里不用 [com.gdutday.data.gdut.http.RedirectFollower]
 * （它没有 URL 改写钩子），而是自己跟随每一跳，把 jwcwx 主机的 http 强制改写回 https。
 *
 * 协议细节见 `docs/reference/spec/gdut-protocol.mdc` §4.8。
 */
public object JwcwxSso {

    private const val MAX_HOPS = 8

    /** 这些主机的 http 一律升级 https（jwcwx 的 302 会把全部 Location 降级 http）。 */
    private fun knownHosts(hosts: GdutHosts): Set<String> =
        setOf(hosts.jwcwxHost, hosts.authserverHost, hosts.jxfwHost)

    /**
     * 用现有会话的 authserver TGT 换取 jwcwx 会话 cookie。
     *
     * @return cookie 被更新的会话副本（诊断信息里追加跳转链）。
     * @throws GdutException.SessionExpired 没有 authserver TGT、或 TGT 已过期
     *   （authserver 返回了登录页）。
     */
    public fun ensureSession(
        httpClient: OkHttpClient,
        session: GdutSession,
        hosts: GdutHosts = session.hosts,
    ): GdutSession {
        val jar = SessionCookieJar(session.cookies)
        val client = httpClient.newBuilder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val chain = mutableListOf<String>()
        try {
            var request: Request = Request.Builder()
                .url("${hosts.jwcwxBase}/login/cas")
                .asBrowserNavigation(referer = "${hosts.jwcwxBase}/login", sameSite = "none")
                .get()
                .build()

            var response: Response = client.newCall(request).execute()
            var hops = 0
            while (response.isRedirect) {
                if (hops >= MAX_HOPS) {
                    response.close()
                    throw GdutException.TooManyRedirects(
                        chain = chain + (response.header("Location") ?: "?"),
                        maxHops = MAX_HOPS,
                    )
                }
                hops++
                chain += "${response.request.url}(${response.code})"
                val location = response.header("Location")
                if (location.isNullOrBlank()) {
                    response.close()
                    throw GdutException.Parse(
                        what = "jwcwx 会话打通",
                        snippet = "${response.code} 重定向缺少 Location",
                    )
                }
                val currentUrl = response.request.url
                response.close()
                // jwcwx 的 302 会把所有 Location 降级 http（含跳往 authserver 的那一跳）；
                // 80 端口不可达且 Android 禁止明文通信。先用 HttpUrl 正式解析（兼容相对 Location），
                // 再对已知主机的 http 替换 scheme —— 不做字符串手术，避免拼出畸形主机。
                val resolved = currentUrl.resolve(location)
                    ?: throw GdutException.Parse(what = "jwcwx 会话打通", snippet = "无法解析 Location: $location")
                val nextUrl = if (resolved.scheme == "http" && resolved.host in knownHosts(hosts)) {
                    resolved.newBuilder().scheme("https").build()
                } else {
                    resolved
                }
                request = Request.Builder()
                    .url(nextUrl)
                    .asBrowserNavigation(referer = currentUrl.toString(), sameSite = "none")
                    .get()
                    .build()
                response = client.newCall(request).execute()
            }
            return finish(response, chain, hosts, jar, session)
        } catch (e: IOException) {
            throw GdutException.Network(detail = "打通 jwcwx 会话失败: ${e.message}", cause = e)
        }
    }

    private fun finish(
        response: Response,
        chain: List<String>,
        hosts: GdutHosts,
        jar: SessionCookieJar,
        session: GdutSession,
    ): GdutSession = response.use { r ->
        val body = runCatching { r.body.string() }.getOrDefault("")
        val landedOnAuthLogin = r.request.url.host == hosts.authserverHost ||
            body.contains("pwdEncryptSalt", ignoreCase = true)
        if (landedOnAuthLogin) {
            throw GdutException.SessionExpired(
                detail = "统一认证会话已过期，无法免密打通 jwcwx（需要重新登录）",
            )
        }
        val cookies = jar.snapshot()
        if (cookies.none {
                it.name.equals("JSESSIONID", ignoreCase = true) &&
                    it.domain.endsWith(hosts.jwcwxHost, ignoreCase = true)
            }
        ) {
            throw GdutException.SessionExpired(
                detail = "jwcwx 跳转链走完但没有拿到 jwcwx 会话 cookie: ${chain.joinToString(" -> ")}",
            )
        }
        session.copy(
            cookies = cookies,
            diagnostics = (session.diagnostics.lineSequence() + "jwcwx SSO: ${chain.joinToString(" -> ")}")
                .joinToString("\n"),
        )
    }
}
