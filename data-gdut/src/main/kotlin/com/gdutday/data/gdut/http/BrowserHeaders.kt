package com.gdutday.data.gdut.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * 浏览器请求头伪装。
 *
 * ## 为什么一个 Android App 要假装成 Linux 桌面 Chrome
 *
 * 因为**解析器是对着桌面版登录页写的**。统一认证登录页的内联配置里有
 * `mobileModel` / `mobileThemeColor` / `mobileImages` 等字段，说明该系统
 * 会按 UA 下发**不同的移动版页面**。移动版页面的表单结构未经验证，
 * `#pwdFromId`、`#pwdEncryptSalt` 这些 id 很可能不同 —— 用移动 UA 会让解析器直接失效。
 *
 * 旧 Java 后端和 F# 库都用桌面 UA，且都验证过可用。本项目沿用。
 *
 * ## 版本号的取舍
 *
 * 下面这套 `sec-ch-ua` / `User-Agent` 逐字抄自旧 Java 后端
 * `LoginServiceImpl.debugRedirect()`（Chrome 130，2024 年的版本）。
 * 选它的理由是**这是唯一一份被生产验证过的完整头组合**。
 *
 * 太旧的 UA 理论上也是一种指纹。如果哪天登录开始莫名失败，
 * **第一个该试的就是把版本号整体调新**（改 [CHROME_MAJOR_VERSION] 即可，
 * 所有相关头都会跟着变，不会出现"UA 说 130、sec-ch-ua 说 120"的自相矛盾）。
 */
public object BrowserHeaders {

    /** Chrome 主版本号。调这一个常量即可整体升级伪装版本。 */
    public const val CHROME_MAJOR_VERSION: Int = 130

    public val USER_AGENT: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROME_MAJOR_VERSION.0.6613.35 Safari/537.36"

    /** 客户端提示：品牌列表。Chrome 从 90 起固定这个 `(Not(A:Brand` 格式。 */
    public val SEC_CH_UA: String =
        "\"(Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$CHROME_MAJOR_VERSION\", " +
            "\"Chromium\";v=\"$CHROME_MAJOR_VERSION\""

    public val SEC_CH_UA_FULL_VERSION_LIST: String =
        "\"(Not(A:Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"$CHROME_MAJOR_VERSION\", " +
            "\"Chromium\";v=\"$CHROME_MAJOR_VERSION\""

    public const val SEC_CH_UA_PLATFORM: String = "\"Linux\""
    public const val SEC_CH_UA_MOBILE: String = "?0"
}

// ---------------------------------------------------------------------------
// 下面两个是**文件级顶层扩展函数**，不是 BrowserHeaders 的成员。
// 原因：Kotlin 的成员扩展函数无法在 object 外部用 `BrowserHeaders.ext(builder)` 的形式调用，
// 只能在 `with(BrowserHeaders) { ... }` 作用域里用接收者语法，写起来很别扭。
// 做成顶层函数后，调用方在 `Request.Builder.apply { asBrowserNavigation(...) }` 里直接用即可。
// ---------------------------------------------------------------------------

/**
 * 给"导航类" GET 请求（取登录页、跟随 302）加上完整的浏览器头。
 *
 * @param referer 上一跳的地址。统一认证的 nginx 会校验 Referer，
 *   旧后端在重定向循环里显式写死了
 *   `Referer: https://authserver.gdut.edu.cn/authserver/login?type=userNameLogin`。
 * @param sameSite `sec-fetch-site` 的取值：同站跳转填 `same-site`，同源填 `same-origin`，首次导航填 `none`。
 */
public fun Request.Builder.asBrowserNavigation(
    referer: String? = null,
    sameSite: String = "same-site",
): Request.Builder = apply {
    header("User-Agent", BrowserHeaders.USER_AGENT)
    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
    header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    header("Pragma", "no-cache")
    header("Cache-Control", "no-cache")
    header("Upgrade-Insecure-Requests", "1")
    header("sec-ch-ua", BrowserHeaders.SEC_CH_UA)
    header("sec-ch-ua-full-version-list", BrowserHeaders.SEC_CH_UA_FULL_VERSION_LIST)
    header("sec-ch-ua-mobile", BrowserHeaders.SEC_CH_UA_MOBILE)
    header("sec-ch-ua-platform", BrowserHeaders.SEC_CH_UA_PLATFORM)
    header("sec-fetch-dest", "document")
    header("sec-fetch-mode", "navigate")
    header("sec-fetch-site", sameSite)
    header("sec-fetch-user", "?1")
    if (referer != null) header("Referer", referer)
}

/**
 * 给 jxfw 的 `!getDataList.action` 类 XHR 请求加头。
 *
 * 这些接口是页面里的 AJAX 调用，`sec-fetch-*` 与导航请求不同：
 * `dest=empty`、`mode=cors`、`site=same-origin`，并且带 `X-Requested-With`。
 *
 * @param referer **必填**。实测课表接口要求
 *   `Referer: https://jxfw.gdut.edu.cn/`（`getDataList`）或
 *   `https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action`（`xsAllKbList`），
 *   缺失会被拒。旧 F# 库里专门留了 `// TODO: 重要！需要记录` 的注释。
 */
public fun Request.Builder.asBrowserXhr(referer: String): Request.Builder = apply {
    header("User-Agent", BrowserHeaders.USER_AGENT)
    header("Accept", "application/json, text/javascript, */*; q=0.01")
    header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    header("X-Requested-With", "XMLHttpRequest")
    header("Referer", referer)
    header("sec-ch-ua", BrowserHeaders.SEC_CH_UA)
    header("sec-ch-ua-mobile", BrowserHeaders.SEC_CH_UA_MOBILE)
    header("sec-ch-ua-platform", BrowserHeaders.SEC_CH_UA_PLATFORM)
    header("sec-fetch-dest", "empty")
    header("sec-fetch-mode", "cors")
    header("sec-fetch-site", "same-origin")
}

/**
 * 由请求 URL 推导 `Origin` 头的值。
 *
 * 浏览器发的 Origin 只有 `scheme://host[:port]`（默认端口省略），**不含路径**。
 *
 * ## 为什么不能写成 "https://" + host
 *
 * 第一版就是这么写的，结果在 MockWebServer 上发出了 `Origin: https://localhost`
 * —— scheme 错了（mock 是 http）、端口丢了。生产环境下恰好看不出来，
 * 因为 `https://authserver.gdut.edu.cn` 正好等于"硬编码 scheme + 生产 host"。
 * 这类 bug 只在测试里暴露，但**它说明生产代码把环境假设写死了**，
 * 一旦学校换成非 443 端口或者上灰度域名就会出问题。
 *
 * 所以：Origin 一律从实际请求 URL 推导，不硬编码。
 */
public fun originOf(url: HttpUrl): String {
    val defaultPort = if (url.scheme.equals("https", ignoreCase = true)) 443 else 80
    val portPart = if (url.port == defaultPort) "" else ":${url.port}"
    return "${url.scheme}://${url.host}$portPart"
}

/** [originOf] 的字符串重载。 */
public fun originOf(url: String): String = originOf(url.toHttpUrl())
