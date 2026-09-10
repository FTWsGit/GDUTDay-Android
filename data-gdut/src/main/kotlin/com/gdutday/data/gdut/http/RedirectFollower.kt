package com.gdutday.data.gdut.http

import com.gdutday.core.model.GdutException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 重定向链上的一跳。
 *
 * @property url 本跳请求的地址
 * @property statusCode 本跳的响应码
 * @property location `Location` 响应头（3xx 时才有）
 */
public data class RedirectHop(
    public val url: String,
    public val statusCode: Int,
    public val location: String? = null,
)

/**
 * 手动跟随重定向的结果。
 *
 * @property chain 完整的跳转链，含最终落点。诊断信息里直接展示它。
 * @property response **最终**那一次响应。调用方负责 close。
 * @property loopDetected 是否因为检测到 URL 重复而提前停止。
 *   旧 Java 后端的注释写着"最后会无限重定向，因为 nginx 一直永远返回 302"，
 *   它的处置是硬编码 `if (maxTime > 3) break` —— 静默跳出，不区分成功与失败。
 *   本实现把"跳过了哪些地址"和"为什么停下来"都记录下来，交给调用方判断：
 *   如果链路中已经出现过目标主机、且 cookie jar 里拿到了该主机的 JSESSIONID，
 *   那这个循环就是良性的收尾重定向，应当视为成功。
 */
public class RedirectResult(
    public val chain: List<RedirectHop>,
    public val response: Response,
    public val loopDetected: Boolean = false,
) {
    public val finalUrl: String get() = response.request.url.toString()
    public val finalHost: String get() = response.request.url.host
    public val finalStatusCode: Int get() = response.code
    public val hopCount: Int get() = chain.size

    /** 是否落到了目标主机上（登录成功的判据）。 */
    public fun landedOn(host: String): Boolean = finalHost.equals(host, ignoreCase = true)

    /** 跳转链中**是否出现过**目标主机。循环收尾时 [landedOn] 可能为 false，但这条为 true。 */
    public fun visited(host: String): Boolean =
        chain.any { it.url.contains(host, ignoreCase = true) } || landedOn(host)

    /** 跳转链的可读形式，如 `a(302) -> b(302) -> c(200)`。 */
    public fun chainToString(): String =
        chain.joinToString(" -> ") { "${it.url}(${it.statusCode})" } +
            if (loopDetected) " [loop]" else ""
}

/**
 * 手动重定向跟随器。
 *
 * ## 为什么不用 OkHttp 内置的 `followRedirects(true)`
 *
 * 登录流程需要**观察每一跳**才能判断结果：
 * - 落到 `jxfw.gdut.edu.cn` → 成功
 * - 又回到 `authserver.gdut.edu.cn/authserver/login` → 账号密码错误（响应体是带错误提示的登录页）
 * - 一直跳个不停 → 需要报 [GdutException.TooManyRedirects] 并附上完整链路
 *
 * 内置跟随会把这些信息全部吃掉，只给你一个最终 Response，
 * 出错时根本不知道该提示"密码错误"还是"接口改版"。
 *
 * ## 旧后端的坑
 *
 * 旧 Java 后端 `LoginServiceImpl.debugRedirect()` 的注释写着
 * "最后会无限重定向，因为 nginx 一直永远返回 302"，
 * 并把上限硬编码成 `if (maxTime > 3) break` —— **突破上限时静默 break，不报错**。
 * 于是登录失败和登录成功走到了同一条后续代码路径，只能靠后面某个请求碰巧失败才暴露问题。
 * 本实现在超限时**明确抛异常**并带上链路。
 *
 * @property maxHops 最大跳数。浏览器默认 20；SSO 链路实测 3~5 跳，给 10 足够且能及早发现死循环。
 */
public class RedirectFollower(
    private val client: OkHttpClient,
    public val maxHops: Int = DEFAULT_MAX_HOPS,
) {

    /**
     * 发出 [request] 并手动跟随 3xx，直到出现非 3xx 响应或超出 [maxHops]。
     *
     * 每一跳都会：
     * 1. 记录到链路里；
     * 2. **关闭上一个响应体**（不关会泄漏连接，登录流程一次要跳好几跳）；
     * 3. 把 301/302/303 的后续请求降级为 GET 并丢弃 body（符合 RFC 7231 与浏览器行为）；
     * 4. 307/308 保留原方法与 body。
     *
     * @param refererForHops 每一跳要带的 Referer。传 null 则用上一跳的 URL 作为 Referer（浏览器行为）。
     *   统一认证的 nginx 对 Referer 敏感，旧后端在循环里写死了登录页地址，
     *   这里默认沿用"上一跳 URL"，需要固定值时显式传入。
     */
    public fun follow(
        request: Request,
        refererForHops: String? = null,
    ): RedirectResult {
        val chain = mutableListOf<RedirectHop>()
        val visited = LinkedHashSet<String>()
        var loopDetected = false

        var current: Request = request
        visited += current.url.toString()
        var response: Response = client.newCall(current).execute()
        chain += RedirectHop(current.url.toString(), response.code, response.header("Location"))

        var hops = 0
        while (response.isRedirect) {
            if (hops >= maxHops) {
                val location = response.header("Location")
                response.close()
                throw GdutException.TooManyRedirects(
                    chain = chain.map { "${it.url}(${it.statusCode})" } + "[$location]",
                    maxHops = maxHops,
                )
            }
            hops++

            val location = response.header("Location")
            if (location.isNullOrBlank()) break // 3xx 但没有 Location，交给调用方处理

            val previousUrl = current.url.toString()
            val previousCode = response.code
            // 相对 Location 要按上一跳 URL 解析
            val nextUrl = current.url.resolve(location)
            response.close()

            if (nextUrl == null) {
                throw GdutException.Parse(
                    what = "重定向地址",
                    snippet = "无法解析 Location: $location（来自 $previousUrl）",
                )
            }

            // 循环检测：SSO 收尾阶段 nginx 会在两三个地址之间来回 302。
            // 与其硬编码跳数上限然后静默 break（旧后端的做法），不如识别出"这个地址来过了"
            // 并停下来，把已经拿到的 cookie 和链路交给调用方判断成败。
            val nextKey = nextUrl.toString()
            if (!visited.add(nextKey)) {
                loopDetected = true
                // 重新发一次请求拿到这个地址的真实响应（上面已经 close 掉了），
                // 使 RedirectResult.response 始终是一个可用的、未关闭的响应。
                current = Request.Builder().url(nextUrl).get()
                    .header("User-Agent", BrowserHeaders.USER_AGENT)
                    .header("Referer", refererForHops ?: previousUrl)
                    .build()
                response = client.newCall(current).execute()
                chain += RedirectHop(nextKey, response.code, response.header("Location"))
                break
            }

            val preserveMethod = previousCode == 307 || previousCode == 308
            val referer = refererForHops ?: previousUrl

            current = Request.Builder()
                .url(nextUrl)
                // 跨主机时不携带上一跳的自定义头，避免把 authserver 的头泄给 jxfw
                .apply {
                    if (preserveMethod) {
                        method(request.method, request.body)
                        request.headers.forEach { (name, value) -> header(name, value) }
                    } else {
                        get()
                        header("User-Agent", BrowserHeaders.USER_AGENT)
                        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        header("Upgrade-Insecure-Requests", "1")
                        header("sec-ch-ua", BrowserHeaders.SEC_CH_UA)
                        header("sec-ch-ua-mobile", BrowserHeaders.SEC_CH_UA_MOBILE)
                        header("sec-ch-ua-platform", BrowserHeaders.SEC_CH_UA_PLATFORM)
                        header("sec-fetch-dest", "document")
                        header("sec-fetch-mode", "navigate")
                        header("sec-fetch-user", "?1")
                    }
                    header("Referer", referer)
                    // same-origin / same-site / cross-site：浏览器会区分，这里照做
                    header(
                        "sec-fetch-site",
                        when {
                            nextUrl.host == request.url.host -> "same-origin"
                            isSameSite(nextUrl.host, request.url.host) -> "same-site"
                            else -> "cross-site"
                        },
                    )
                }
                .build()

            response = client.newCall(current).execute()
            chain += RedirectHop(current.url.toString(), response.code, response.header("Location"))
        }

        return RedirectResult(chain, response, loopDetected)
    }


    public companion object {
        public const val DEFAULT_MAX_HOPS: Int = 10

        /**
         * 粗略的可注册域比较，用于判定 `sec-fetch-site`。
         *
         * `authserver.gdut.edu.cn` 与 `jxfw.gdut.edu.cn` 的可注册域都是 `gdut.edu.cn` → 同站。
         * 只取最后两段对 `.com.cn` 这类后缀不准，但广工全部域名都是 `*.gdut.edu.cn`，够用。
         */
        public fun isSameSite(a: String, b: String): Boolean {
            fun registrable(h: String) = h.split('.').takeLast(2).joinToString(".").lowercase()
            return registrable(a) == registrable(b)
        }
    }
}
