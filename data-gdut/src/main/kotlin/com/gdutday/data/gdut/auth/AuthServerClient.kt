package com.gdutday.data.gdut.auth

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.UserType
import com.gdutday.data.gdut.GdutEndpoints
import com.gdutday.data.gdut.GdutHosts
import com.gdutday.data.gdut.http.RedirectResult
import com.gdutday.data.gdut.http.asBrowserNavigation
import com.gdutday.data.gdut.http.asBrowserXhr
import com.gdutday.data.gdut.http.originOf
import com.gdutday.data.gdut.http.bool
import com.gdutday.data.gdut.http.FormFields
import com.gdutday.data.gdut.http.LenientJson
import com.gdutday.data.gdut.http.RedirectFollower
import com.gdutday.data.gdut.http.SessionCookieJar
import com.gdutday.data.gdut.session.GdutSession
import com.gdutday.data.gdut.session.LoginMethod
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * [AuthServerClient] 的可调参数。
 *
 * 全部给了经过验证的默认值。之所以做成可配置而不是写死：
 * 学校改接口时，**用户/开发者能在不改代码的情况下先试出正确组合**，
 * 这对一个依赖第三方非公开接口的项目非常重要。
 *
 * @property entryUrl 登录流程的第一步访问哪个地址。
 *   默认走"浏览器路径"：先访问教务系统的 SSO 入口，让它 302 到统一认证并带上 `service`。
 *   旧 Java 后端走的是"两段式"：直接 GET `/authserver/login?type=userNameLogin`（不带 service），
 *   认证成功后再用空 body POST 一次带 service 的地址触发 SSO。
 *   两条路都能用，本项目默认浏览器路径，因为它只需要一次认证、跳转链更短、更接近真实用户行为。
 * @property maxRedirectHops 单次请求的最大重定向跳数。
 * @property checkCaptchaBeforeLogin 提交前是否先问一次 `checkNeedCaptcha.htl`。
 *   默认 true：提前发现滑块要求，好过提交后被服务端记一次失败。
 * @property verifyAfterLogin 登录成功后是否再 GET 一次教务首页确认会话真的可用。
 *   默认 true。多一个请求，但能把"跳转链看着对、其实没拿到会话"这种情况挡在登录阶段，
 *   而不是等用户刷课表时才发现。
 * @property rejectNonUndergraduate 检测到非本科生身份时是否直接抛异常。
 *   默认 true（当前版本只实现了本科生课表）。
 *   验证脚本可以设成 false，以便观察研究生/教师账号的登录链路走到哪一步。
 * @property rememberMe 是否勾选"记住我"。默认 true —— 它会让 CAS 下发更持久的 TGT，
 *   显著减少重新登录的频率。表单里 `rememberMe` 本来就是 `value="true"` 的 checkbox。
 */
public data class AuthServerConfig(
    /**
     * 各系统的基础地址。默认生产环境；测试里换成 [GdutHosts.forTestServer] 指向 MockWebServer。
     *
     * 这是整个 `data-gdut` 模块能被离线测试的关键 —— 没有它，
     * 验证登录流程就只能拿真账号去撞学校的服务器。
     */
    public val hosts: GdutHosts = GdutHosts.PRODUCTION,

    /**
     * 登录流程的第一步访问哪个地址。null 表示用 [GdutHosts.jxfwSsoLogin]（默认）。
     *
     * 默认走"浏览器路径"：先访问教务系统的 SSO 入口，让它 302 到统一认证并带上 `service`。
     * 旧 Java 后端走的是"两段式"：直接 GET `/authserver/login?type=userNameLogin`（不带 service），
     * 认证成功后再用空 body POST 一次带 service 的地址触发 SSO。
     * 两条路都能用，本项目默认浏览器路径：只需一次认证、跳转链更短、更接近真实用户行为。
     */
    public val entryUrl: String? = null,

    public val maxRedirectHops: Int = RedirectFollower.DEFAULT_MAX_HOPS,
    public val checkCaptchaBeforeLogin: Boolean = true,
    public val verifyAfterLogin: Boolean = true,
    public val rejectNonUndergraduate: Boolean = true,
    public val rememberMe: Boolean = true,

    /** 登录成功后要求落到的主机。null 表示 [GdutHosts.jxfwHost]。 */
    public val expectedHost: String? = null,
) {
    /** [entryUrl] 的实际取值。 */
    public val effectiveEntryUrl: String get() = entryUrl ?: hosts.jxfwSsoLogin

    /** [expectedHost] 的实际取值。 */
    public val effectiveExpectedHost: String get() = expectedHost ?: hosts.jxfwHost
}

/**
 * 统一身份认证（CAS）登录客户端。
 *
 * ## 完整流程（与浏览器行为逐步对齐）
 *
 * ```
 * ① GET  https://jxfw.gdut.edu.cn/new/ssoLogin
 *        → 302 https://authserver.gdut.edu.cn/authserver/login?service=https%3A%2F%2Fjxfw...
 *        → 200 登录页 HTML；Set-Cookie: JSESSIONID(authserver), route
 * ② 解析 #pwdFromId 的隐藏域、pwdEncryptSalt、内联变量 service / captchaSwitch
 * ③ GET  /authserver/checkNeedCaptcha.htl?username=<学号>&_=<毫秒时间戳>
 *        → {"isNeed":false}  （true 则抛 CaptchaRequired，绝不自动重试）
 * ④ password = AuthServerCrypto.encryptPassword(明文, salt)
 * ⑤ POST /authserver/login?service=<urlencoded service>
 *        body = 隐藏域原样 + username + password(密文) + captcha="" + rememberMe=true
 *        ⚠ 不含 passwordText（浏览器在提交前把它 disabled 了）
 *        ⚠ 含一个 name 为空的字段，值是 salt
 * ⑥ 手动跟随 302：authserver → jxfw/new/ssoLogin?ticket=ST-xxx → jxfw 首页
 *        每跳都关掉上一个响应体；出现 URL 重复即认定循环并停止
 * ⑦ 判成败：落到 jxfw 主机 = 成功；200 且返回登录页 = 失败（从 #showErrorTip 取文案）
 * ⑧ GET  https://jxfw.gdut.edu.cn/  → 必须 200，若 302 回 authserver 说明会话没建起来
 * ⑨ GET  /personalInfo/common/getUserConf → 正则抠学号 → 首位数字定身份
 * ```
 *
 * ## 线程模型
 *
 * 所有方法都是**阻塞**的（OkHttp 同步调用）。必须由调用方切到 IO 线程 ——
 * `data-repository` 里统一用 `withContext(Dispatchers.IO)` 包一层。
 * 刻意不在本模块引入 suspend，是为了让单元测试可以直接跑，不需要 `runBlocking`。
 *
 * @param httpClient 外部注入的 OkHttpClient。**不要在内部 new** —— Android 侧要统一配置
 *   超时、TLS、DNS、日志；测试侧要能替换成 MockWebServer 的 client。
 *   本类会 `newBuilder()` 派生出关闭自动重定向、绑定临时 CookieJar 的副本，不影响传入的实例。
 */
public class AuthServerClient(
    httpClient: OkHttpClient,
    private val config: AuthServerConfig = AuthServerConfig(),
) {

    /**
     * 关掉自动重定向的基座 client。
     *
     * 必须关：登录判定完全依赖观察每一跳（见 [RedirectFollower] 的类注释）。
     */
    private val baseClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * 用统一身份认证登录，并打通本科教务系统。
     *
     * @param studentId 学号（10 位，首位 3）
     * @param password 统一认证密码（明文，**不会**被记录到任何日志）
     * @return 登录成功的会话
     * @throws GdutException.BadCredentials 学号或密码错误
     * @throws GdutException.CaptchaRequired 账号触发滑块验证，无法自动完成
     * @throws GdutException.UnsupportedUserType 账号是研究生或教师
     * @throws GdutException.SessionExpired 跳转链看着成功但教务会话没建起来
     * @throws GdutException.Network 网络不可达
     * @throws GdutException.TooManyRedirects 跳转链异常
     * @throws GdutException.Parse 登录页结构变了
     */
    public fun login(studentId: String, password: String): GdutSession {
        require(studentId.isNotBlank()) { "学号不能为空" }
        require(password.isNotEmpty()) { "密码不能为空" }

        // 每次登录都用全新的 cookie jar：execution 与 JSESSIONID 绑定且一次性，
        // 复用旧会话会让 CAS 认为 flow 已失效。
        val jar = SessionCookieJar()
        val client = baseClient.newBuilder().cookieJar(jar).build()
        val follower = RedirectFollower(client, config.maxRedirectHops)

        // ① 取登录页
        val pageResult = try {
            follower.follow(
                Request.Builder()
                    .url(config.effectiveEntryUrl)
                    .asNavigationEntry()
                    .build(),
            )
        } catch (e: IOException) {
            throw GdutException.Network(detail = "取登录页失败: ${e.javaClass.simpleName}: ${e.message}", cause = e)
        }

        val pageHtml = pageResult.response.use { readBodySafely(it) }
        val pageUrl = pageResult.finalUrl

        // ② 解析表单
        val form = AuthLoginPageParser.parse(pageHtml, pageUrl)
        if (!form.hasUsableSalt()) {
            throw GdutException.Parse(
                what = "登录页的密码加密盐",
                snippet = "salt=${form.salt}（需要 16/24/32 字节）。$form",
            )
        }

        // ③ 滑块检查
        if (config.checkCaptchaBeforeLogin) {
            val captcha = checkCaptchaRequired(studentId, client)
            if (captcha.required) {
                throw GdutException.CaptchaRequired(detail = captcha.raw ?: "checkNeedCaptcha.htl 返回 isNeed=true")
            }
        }

        // ④⑤ 加密并提交
        val cipher = AuthServerCrypto.encryptPassword(password, form.salt)
        val fields = FormFields()
        // 先原样铺上全部隐藏域（含 password="" 和那个空 name 的 salt 字段），再覆盖要填的值。
        // 顺序与浏览器一致：隐藏域在前，用户输入在后。
        form.hiddenFields.forEach { fields.add(it.name, it.value) }
        fields.set("username", studentId)
        fields.set("password", cipher)
        // captcha 必须是"存在且为空"，不能整个字段缺失 —— 页面里它是 type=text 的可见输入框，
        // 浏览器即使没填也会提交 captcha=。
        fields.set("captcha", "")
        if (config.rememberMe) fields.set("rememberMe", "true")
        // passwordText 刻意不加：浏览器提交前会 disabled 它

        val submitUrl = form.submitUrl()
        val postResult = try {
            follower.follow(
                Request.Builder()
                    .url(submitUrl)
                    .post(fields.toRequestBody())
                    .apply {
                        asBrowserNavigation(referer = pageUrl, sameSite = "same-origin")
                        header("Origin", originOf(submitUrl))
                        header("Content-Type", "application/x-www-form-urlencoded")
                        header("Cache-Control", "max-age=0")
                    }
                    .build(),
                refererForHops = pageUrl,
            )
        } catch (e: IOException) {
            throw GdutException.Network(detail = "提交登录失败: ${e.javaClass.simpleName}: ${e.message}", cause = e)
        }

        // ⑥⑦ 判定结果
        val postBody = postResult.response.use { readBodySafely(it) }
        evaluateLoginResponse(postResult, postBody, form, studentId)

        // ⑧ 验证教务会话
        if (config.verifyAfterLogin) verifyJxfwSession(client)

        // ⑨ 取学号与身份
        val resolvedId = fetchStudentId(client) ?: studentId
        val userType = UserType.fromStudentId(resolvedId)
        if (config.rejectNonUndergraduate && userType != UserType.UNDERGRADUATE) {
            throw GdutException.UnsupportedUserType(userType)
        }

        val cookies = jar.snapshot()
        if (!cookies.any { it.name.equals("JSESSIONID", ignoreCase = true) }) {
            throw GdutException.UnexpectedLoginResult(
                detail = "登录流程走完但没有任何 JSESSIONID。链路: ${postResult.chainToString()}",
            )
        }

        return GdutSession(
            cookies = cookies,
            profile = StudentProfile(studentId = resolvedId, userType = userType),
            method = LoginMethod.UNIFIED_AUTH,
            hosts = config.hosts,
            diagnostics = buildString {
                append("登录方式=统一认证\n")
                append("入口=").append(config.effectiveEntryUrl).append('\n')
                append("登录页落点=").append(pageUrl).append('\n')
                append("表单=").append(form).append('\n')
                append("提交地址=").append(submitUrl).append('\n')
                append("跳转链=").append(postResult.chainToString()).append('\n')
                append("cookie=").append(cookies.joinToString { "${it.name}@${it.domain}" })
            },
        )
    }

    /**
     * 询问某账号是否需要人机验证。
     *
     * `GET /authserver/checkNeedCaptcha.htl?username=<学号>&_=<毫秒时间戳>`
     *
     * ⚠ 响应的 Content-Type 是 **`text/plain;charset=UTF-8`** 而不是 `application/json`，
     * 实测如此。所以这里直接读 body 字符串再解析，不看 Content-Type。
     *
     * 网络或解析失败时返回 `required = false`（[CaptchaCheck.unknown]），
     * **不阻断登录** —— 这个检查只是优化，真需要滑块时提交阶段会自己暴露出来。
     */
    public fun checkCaptchaRequired(studentId: String, client: OkHttpClient): CaptchaCheck {
        val url = config.hosts.authCheckCaptcha.toHttpUrlWithQuery(
            "username" to studentId,
            "_" to System.currentTimeMillis().toString(),
        )
        val response = try {
            client.newCall(
                Request.Builder().url(url)
                    .apply { asBrowserXhr(referer = config.hosts.authLogin) }
                    .header("sec-fetch-site", "same-origin")
                    .get().build(),
            ).execute()
        } catch (e: IOException) {
            return CaptchaCheck.unknown("网络异常: ${e.message}")
        }
        val body = response.use { readBodySafely(it) }
        if (response.code != 200) return CaptchaCheck.unknown("HTTP ${response.code}")
        val isNeed = LenientJson.parseObjectOrNull(body)?.bool("isNeed")
        return when (isNeed) {
            true -> CaptchaCheck(required = true, raw = body)
            false -> CaptchaCheck(required = false, raw = body)
            null -> CaptchaCheck.unknown("响应无法解析: ${LenientJson.snippet(body, 120)}")
        }
    }

    /**
     * 从 `getUserConf` 页面抠学号。
     *
     * 旧 Java 后端的正则是 `<option value='(\d+)' selected>`（**单引号**、属性顺序固定）。
     * 这里放宽成引号可选、属性间允许任意空白，并把学号长度限制在 6 位以上，
     * 避免把页面里其它 `<option>` 的数字（例如院系代码）误认成学号。
     *
     * @return 学号；页面结构变了或会话无效时返回 null（调用方回退到用户输入的学号）。
     */
    public fun fetchStudentId(client: OkHttpClient): String? {
        val response = try {
            client.newCall(
                Request.Builder().url(config.hosts.authUserConf)
                    .apply { asBrowserNavigation(referer = config.hosts.authLogin) }
                    .get().build(),
            ).execute()
        } catch (e: IOException) {
            return null
        }
        val body = response.use { readBodySafely(it) }
        if (response.code != 200 || body.isBlank()) return null
        return STUDENT_ID_REGEX.find(body)?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 判定登录 POST 的结果。
     *
     * 三种情形：
     * 1. 落到了 [AuthServerConfig.expectedHost]（或链路中出现过它）→ 成功，直接返回
     * 2. 返回 200 且响应体还是登录页 → 失败，从 `#showErrorTip` 取文案抛 [GdutException.BadCredentials]
     * 3. 其它（例如 200 但既不是登录页也没落到目标主机）→ [GdutException.UnexpectedLoginResult]
     */
    private fun evaluateLoginResponse(
        result: RedirectResult,
        body: String,
        form: AuthLoginForm,
        studentId: String,
    ) {
        val onTarget = result.landedOn(config.effectiveExpectedHost) || result.visited(config.effectiveExpectedHost)

        // 情形 2：还在 authserver 上，且拿回了一个登录页 → 凭据问题
        if (AuthLoginPageParser.looksLikeLoginPage(body)) {
            val message = AuthLoginPageParser.extractLoginError(body)
            // 服务端下发的 _badCredentialsCount 归零意味着"再错就要验证码了"，
            // login.js 的 credentialsCount() 就是靠它决定是否强制显示验证码。
            val remaining = AuthLoginPageParser.extractInlineVar(body, "_badCredentialsCount")?.toIntOrNull()
            val detail = buildString {
                append("chain=").append(result.chainToString())
                if (message != null) append("; serverMessage=").append(message)
                if (remaining != null) append("; badCredentialsCount=").append(remaining)
                append("; captchaSwitch=").append(AuthLoginPageParser.extractInlineVar(body, "captchaSwitch"))
                append("; submittedForm=").append(form)
            }
            // 没有明确错误文案但确实回到了登录页：也可能是滑块/风控拦截，
            // 用 UnexpectedLoginResult 而不是 BadCredentials，避免误导用户改密码。
            if (message == null) throw GdutException.UnexpectedLoginResult(detail = detail)
            throw GdutException.BadCredentials(serverMessage = message, detail = detail)
        }

        if (onTarget) return // 情形 1：成功

        // 情形 3
        throw GdutException.UnexpectedLoginResult(
            detail = "既没有落到 ${config.effectiveExpectedHost}，响应也不是登录页。" +
                "finalUrl=${result.finalUrl} code=${result.finalStatusCode} " +
                "chain=${result.chainToString()} body=${LenientJson.snippet(body, 200)} " +
                "studentId=$studentId",
        )
    }

    /**
     * 确认教务系统会话确实建立了。
     *
     * `GET https://jxfw.gdut.edu.cn/`：
     * - 200 → 会话有效
     * - 302 且 Location 指向 authserver → ticket 没被 jxfw 接受，会话无效
     */
    private fun verifyJxfwSession(client: OkHttpClient) {
        val response = try {
            client.newCall(
                Request.Builder().url(config.hosts.jxfwHome)
                    .apply { asBrowserNavigation(referer = config.hosts.jxfwHome) }
                    .get().build(),
            ).execute()
        } catch (e: IOException) {
            throw GdutException.Network(detail = "校验教务会话失败: ${e.message}", cause = e)
        }
        response.use {
            if (it.isRedirect) {
                val location = it.header("Location").orEmpty()
                if (location.contains(config.hosts.authserverHost, ignoreCase = true)) {
                    throw GdutException.SessionExpired(
                        detail = "登录后访问 ${config.hosts.jxfwHome} 仍被 302 回统一认证: $location",
                    )
                }
            }
            if (it.code == 401 || it.code == 403) {
                throw GdutException.SessionExpired(detail = "教务首页返回 ${it.code}")
            }
        }
    }

    /**
     * 读响应体，把 IO 异常归一成 [GdutException.Network]。
     *
     * 响应体可能很大（登录页 27KB+），但都是文本，一次性读完最简单。
     */
    private fun readBodySafely(response: Response): String = try {
        response.body.string()
    } catch (e: IOException) {
        throw GdutException.Network(detail = "读取响应体失败: ${e.javaClass.simpleName}: ${e.message}", cause = e)
    }

    private fun Request.Builder.asNavigationEntry(): Request.Builder =
        asBrowserNavigation(referer = null, sameSite = "none")

    private fun String.toHttpUrlWithQuery(vararg pairs: Pair<String, String>): String {
        val sb = StringBuilder(this)
        sb.append(if (contains('?')) '&' else '?')
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append('&')
            sb.append(FormFields.encodeComponent(k)).append('=').append(FormFields.encodeComponent(v))
        }
        return sb.toString()
    }

    public companion object {
        /**
         * 从 `getUserConf` 的 HTML 里抠学号。
         *
         * 比旧后端的 `<option value='(\d+)' selected>` 宽松：
         * 引号可选、属性之间允许空白、要求至少 6 位数字。
         */
        public val STUDENT_ID_REGEX: Regex =
            Regex("""<option\s+value\s*=\s*['"]?(\d{6,})['"]?\s+selected""", RegexOption.IGNORE_CASE)
    }
}

/**
 * `checkNeedCaptcha.htl` 的查询结果。
 *
 * @property required 是否要求人机验证。
 * @property raw 原始响应体，写进诊断信息。
 * @property known 查询本身是否成功。false 表示"没问出来"，
 *   此时 [required] 恒为 false —— **问不出来不等于不需要验证**，
 *   但也不应该因此拦住登录，所以选择不阻断，让提交阶段自己暴露问题。
 */
public data class CaptchaCheck(
    public val required: Boolean,
    public val raw: String?,
    public val known: Boolean = true,
) {
    public companion object {
        public fun unknown(reason: String): CaptchaCheck =
            CaptchaCheck(required = false, raw = reason, known = false)
    }
}
