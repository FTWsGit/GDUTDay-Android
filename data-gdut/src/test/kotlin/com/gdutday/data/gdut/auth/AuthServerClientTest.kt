package com.gdutday.data.gdut.auth

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.UserType
import com.gdutday.data.gdut.GdutHosts
import com.gdutday.data.gdut.session.LoginMethod
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * **完整登录流程的端到端测试**，跑在 MockWebServer 上。
 *
 * ## 这是整个项目最重要的一组测试
 *
 * 登录链路上任何一个环节出错，症状都是同一句"账号或密码错误"，排查成本极高：
 * - `?service=` 漏了 → CAS 不签 ticket → 拿不到 jxfw 会话
 * - 空 name 的 salt 字段漏了 → 服务端解不出密码
 * - `passwordText` 多带了 → 服务端可能优先读明文然后判定失败
 * - Base64 里的 `+` 没做百分号编码 → 服务端收到空格 → 解密失败
 * - 302 没跟够 → 停在半路
 * - cookie 域搞错 → authserver 的 JSESSIONID 被发给了 jxfw
 *
 * 这些全部只有端到端测试能抓到，单元测试看不到。
 *
 * ## 用的是真实登录页
 *
 * 服务端返回的 HTML 是 fixture `authserver_login_page_with_service.real.html`
 * （2026-09-10 从线上抓的真实页面），只把里面的 `var service` 和 `execution`
 * 替换成指向 MockWebServer 的值 —— 表单结构、隐藏域、salt 全部是真实的。
 * 所以"解析真实页面 → 组装请求体"这条路径是被真正验证过的。
 */
class AuthServerClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var hosts: GdutHosts

    /** 服务端收到的全部请求，按顺序。用于断言 POST body。 */
    private val recorded = mutableListOf<RecordedRequest>()

    /** 每个测试可以覆盖的剧本。 */
    private lateinit var script: Script

    /** 登录页 fixture，`service` 已替换成 mock 地址。 */
    private val loginPageHtml: String by lazy {
        val raw = readFixture("authserver_login_page_with_service.real.html")
        raw
            // fixture 里的 service 指向真实的 jxfw，测试里要指向 mock server
            .replace(
                """var service = ["https:\/\/jxfw.gdut.edu.cn\/new\/ssoLogin"];""",
                """var service = [""" + "\"${escapeJs(mockServiceUrl)}\"" + """];""",
            )
            // 去掉 BOM，避免个别断言被不可见字符干扰（真实页面开头有两个 BOM）
            .replace("\uFEFF", "")
    }

    private val mockServiceUrl: String get() = "${baseUrl()}/new/ssoLogin"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        hosts = GdutHosts.forTestServer(baseUrl())
        script = Script()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return route(request)
            }
        }
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = "http://localhost:${server.port}"

    // ================================================================== 剧本

    /**
     * 一次登录会依次触发的请求。用可配置的剧本而不是硬编码，
     * 这样每个测试只改自己关心的那一环。
     */
    private inner class Script(
        /**
         * SSO **入口**（未登录访问 `/new/ssoLogin`）的响应。
         * 默认 302 到统一认证登录页并带上 `service`，与实测的真实行为一致。
         */
        var ssoEntry: () -> MockResponse = {
            redirect("${baseUrl()}/authserver/login?service=" + urlEncode(mockServiceUrl))
        },
        /** POST 登录之后的响应。默认 302 到 SSO 回调。 */
        var postLogin: () -> MockResponse = { redirect("${baseUrl()}/new/ssoLogin?ticket=ST-mock-ticket") },
        /** SSO 回调的响应。默认 302 到首页并下发 jxfw 的 JSESSIONID。 */
        var ssoCallback: () -> MockResponse = {
            redirect("${baseUrl()}/")
                .addHeader("Set-Cookie", "JSESSIONID=JXFW-SESSION-1; Path=/; HttpOnly")
        },
        /** 教务首页的响应。默认 200（会话有效）。 */
        var jxfwHome: () -> MockResponse = { ok("") },
        /** checkNeedCaptcha 的响应。默认不需要验证码。 */
        var checkCaptcha: () -> MockResponse = {
            // ⚠ 实测这个接口的 Content-Type 是 text/plain，不是 application/json
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/plain;charset=UTF-8")
                .setBody("""{"isNeed":false}""")
        },
        /** getUserConf 的响应。默认返回一个本科学号。 */
        var userConf: () -> MockResponse = { ok(userConfHtml("3120012345")) },
    )

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.path ?: return MockResponse().setResponseCode(404)
        val method = request.method ?: "GET"
        return when {
            method == "POST" && path.startsWith("/authserver/login") -> script.postLogin()
            method == "GET" && path.startsWith("/authserver/login") -> loginPage()
            path.startsWith("/authserver/checkNeedCaptcha.htl") -> script.checkCaptcha()
            path.startsWith("/personalInfo/common/getUserConf") -> script.userConf()
            // 关键区分：
            //   /new/ssoLogin（不带 ticket）= SSO **入口**，未登录，要 302 到统一认证并带上 service
            //   /new/ssoLogin?ticket=...     = SSO **回调**，验票后 302 到首页并下发 jxfw 的 JSESSIONID
            // 真实环境就是这个语义（实测 GET https://jxfw.gdut.edu.cn/ 未登录时
            // 302 到 authserver/login?service=https%3A%2F%2Fjxfw.gdut.edu.cn%2Fnew%2FssoLogin）。
            path.startsWith("/new/ssoLogin") && path.contains("ticket=") -> script.ssoCallback()
            path.startsWith("/new/ssoLogin") -> script.ssoEntry()
            path == "/" || path.isEmpty() -> script.jxfwHome()
            else -> MockResponse().setResponseCode(404).setBody("unexpected: $method $path")
        }
    }

    private fun loginPage(): MockResponse = ok(loginPageHtml)
        .addHeader("Set-Cookie", "JSESSIONID=AUTH-SESSION-1; Path=/authserver; HttpOnly")
        .addHeader("Set-Cookie", "route=mock-lb-route; Path=/authserver")

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/html;charset=UTF-8")
        .setBody(body)

    private fun redirect(location: String) = MockResponse().setResponseCode(302)
        .setHeader("Location", location)
        .setHeader("Content-Length", "0")

    private fun userConfHtml(studentId: String) = """
        <html><body>
        <select name="xh">
          <option value='1120000001'>某学院</option>
          <option value='$studentId' selected>$studentId</option>
        </select>
        </body></html>
    """.trimIndent()

    private fun escapeJs(s: String) = s.replace("\\", "\\\\").replace("/", "\\/")

    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    // ================================================================== 成功路径

    @Test
    fun `完整登录流程能走通并拿到两个域的会话`() {
        val session = newClient().login("3120012345", "correct-password")

        assertThat(session.method).isEqualTo(LoginMethod.UNIFIED_AUTH)
        assertThat(session.studentId).isEqualTo("3120012345")
        assertThat(session.userType).isEqualTo(UserType.UNDERGRADUATE)
        assertThat(session.hasAuthServerSession).isTrue()
        assertThat(session.hasJxfwSession).isTrue()
        assertThat(session.cookies.map { it.name }).contains("route") // 负载均衡粘性 cookie 也要留下
        // 两个 JSESSIONID 都必须留下：authserver 的是 CAS TGT 载体，jxfw 的是业务会话
        val sessionIds = session.cookies.filter { it.name == "JSESSIONID" }.map { it.value }
        assertThat(sessionIds).containsAtLeast("AUTH-SESSION-1", "JXFW-SESSION-1")
        // 会话要记住自己是在哪套地址上建立的（否则 hasJxfwSession 无从判断）
        assertThat(session.hosts).isEqualTo(hosts)
    }

    @Test
    fun `第一步先访问 SSO 入口，让它 302 带上 service`() {
        newClient().login("3120012345", "pw")

        val first = recorded.first()
        assertThat(first.method).isEqualTo("GET")
        assertThat(first.path).isEqualTo("/new/ssoLogin")
        // 桌面 UA：移动版页面结构不同，解析器会失效
        assertThat(first.getHeader("User-Agent")).contains("X11; Linux x86_64")
        assertThat(first.getHeader("sec-ch-ua-mobile")).isEqualTo("?0")
    }

    @Test
    fun `POST 地址必须带上 service 查询参数`() {
        newClient().login("3120012345", "pw")

        val post = recorded.first { it.method == "POST" }
        assertThat(post.path).startsWith("/authserver/login?service=")
        val service = URLDecoder.decode(
            post.path!!.substringAfter("service="),
            Charsets.UTF_8,
        )
        assertThat(service).isEqualTo(mockServiceUrl)
    }

    @Test
    fun `POST body 包含空 name 的 salt 字段`() {
        // 这是浏览器行为的忠实复刻：pwdEncryptSalt 没有 name 属性，
        // 提交时会变成 `=<salt>`。旧 Java 后端专门写了 tempMap.put("", salt)。
        newClient().login("3120012345", "pw")

        val body = parseForm(recorded.first { it.method == "POST" }.body.readUtf8())
        assertThat(body[""]).isEqualTo("xaOfScaw6epvgypH")
    }

    @Test
    fun `POST body 里 password 是密文，不是明文，也不含 passwordText`() {
        val password = "correct-password"
        newClient().login("3120012345", password)

        val raw = recorded.first { it.method == "POST" }.body.readUtf8()
        val body = parseForm(raw)

        assertThat(body["username"]).isEqualTo("3120012345")
        // 密文是 Base64，长度远大于明文，且不等于明文
        assertThat(body["password"]).isNotEmpty()
        assertThat(body["password"]).isNotEqualTo(password)
        assertThat(body["password"]!!.length).isGreaterThan(60)
        // login.js 在提交前把 passwordText 那个 input disabled 了，所以它不该出现
        assertThat(body).doesNotContainKey("passwordText")
        // 其余隐藏域要原样带上
        assertThat(body["_eventId"]).isEqualTo("submit")
        assertThat(body["cllt"]).isEqualTo("userNameLogin")
        assertThat(body["dllt"]).isEqualTo("generalLogin")
        assertThat(body).containsKey("lt")          // 值为空但字段必须存在
        assertThat(body["captcha"]).isEmpty()        // 同上
        assertThat(body["rememberMe"]).isEqualTo("true")
        // execution 是从登录页原样带回去的
        assertThat(body["execution"]).contains("_")

        // 密文能被服务端（用同一套算法）还原
        val recovered = AuthServerCrypto.decryptStripPrefix(body["password"]!!, "xaOfScaw6epvgypH", "0".repeat(16))
        assertThat(recovered).isEqualTo(password)
    }

    @Test
    fun `POST body 对 Base64 里的加号、斜杠、等号做了百分号编码`() {
        // Base64 密文必然含 + / =。表单编码里 '+' 表示空格，
        // 不编码的话服务端收到的密文会多出空格，解密必然失败、报"密码错误"。
        newClient().login("3120012345", "pw")

        val raw = recorded.first { it.method == "POST" }.body.readUtf8()
        val passwordField = raw.split('&').first { it.startsWith("password=") }
        // 原始串里不能出现裸露的 '+'（除非它本来就是 %2B）
        val value = passwordField.removePrefix("password=")
        assertThat(value).doesNotContain("+")
        assertThat(value).doesNotContain("/")
        // 解码回来的 Base64 应该是合法的
        val decoded = URLDecoder.decode(value, Charsets.UTF_8)
        assertThat(java.util.Base64.getDecoder().decode(decoded)).isNotEmpty()
    }

    @Test
    fun `POST 带 Origin 与登录页 Referer`() {
        newClient().login("3120012345", "pw")
        val post = recorded.first { it.method == "POST" }
        assertThat(post.getHeader("Origin")).isEqualTo(baseUrl())
        assertThat(post.getHeader("Referer")).contains("/authserver/login")
    }

    @Test
    fun `会跟随 302 直到落到教务首页`() {
        newClient().login("3120012345", "pw")

        val paths = recorded.map { "${it.method} ${it.path?.substringBefore('?')}" }
        assertThat(paths).containsAtLeast(
            "GET /new/ssoLogin",          // 入口
            "GET /authserver/login",      // 取登录页
            "GET /authserver/checkNeedCaptcha.htl",
            "POST /authserver/login",     // 提交
            "GET /new/ssoLogin",          // SSO 回调（带 ticket）
            "GET /",                      // 会话校验
            "GET /personalInfo/common/getUserConf",
        )
    }

    // ================================================================== 失败路径

    @Test
    fun `密码错误时抛 BadCredentials 并带上服务端原文`() {
        script.postLogin = {
            ok(
                loginPageHtml.replace(
                    """<span id="showErrorTip" class="form-error"></span>""",
                    """<span id="showErrorTip" class="form-error">用户名或密码错误</span>""",
                ),
            )
        }

        val e = assertThrows(GdutException.BadCredentials::class.java) {
            newClient().login("3120012345", "wrong")
        }
        assertThat(e.userMessage).isEqualTo("用户名或密码错误")
        assertThat(e.detail).contains("chain=")
    }

    @Test
    fun `登录页回来了但没有错误文案时，不武断地说密码错误`() {
        // 也可能是滑块拦截、会话过期、风控静默拒绝等。
        // 误报"密码错误"会让用户去改一个本来正确的密码，甚至反复重试把账号锁掉，
        // 所以宁可报一个更含糊但诚实的错误。
        script.postLogin = { ok(loginPageHtml) }

        val e = assertThrows(GdutException.UnexpectedLoginResult::class.java) {
            newClient().login("3120012345", "pw")
        }
        // 诊断信息里要能看到跳转链和表单摘要，方便用户提 issue
        assertThat(e.detail).contains("chain=")
        assertThat(e.detail).contains("submittedForm=")
        assertThat(e.detail).contains("badCredentialsCount=5")
    }

    @Test
    fun `需要滑块验证时抛 CaptchaRequired 且不再提交登录`() {
        script.checkCaptcha = {
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/plain;charset=UTF-8")
                .setBody("""{"isNeed":true}""")
        }

        val e = assertThrows(GdutException.CaptchaRequired::class.java) {
            newClient().login("3120012345", "pw")
        }
        assertThat(e.userMessage).contains("滑块")
        // 关键：**绝不能在需要滑块时还去提交** —— 那会白白累积风控计数
        assertThat(recorded.none { it.method == "POST" }).isTrue()
    }

    @Test
    fun `checkNeedCaptcha 返回 text-plain 也能解析`() {
        // 实测该接口 Content-Type 是 text/plain;charset=UTF-8，不是 application/json。
        // 如果实现里用了"响应类型必须是 JSON"的严格解析器，这里会直接失效。
        newClient().login("3120012345", "pw")
        val req = recorded.first { it.path?.startsWith("/authserver/checkNeedCaptcha.htl") == true }
        assertThat(req.path).contains("username=3120012345")
        assertThat(req.path).contains("_=")   // 防缓存时间戳
    }

    @Test
    fun `跳转链看似成功但教务会话没建起来时报 SessionExpired`() {
        // 场景：ticket 被接受了（SSO 回调返回 200 并下发了 JSESSIONID），
        // 但随后访问教务首页又被 302 回统一认证 —— 说明会话实际上没建起来。
        // 旧 Java 后端在这种情况下会静默继续，直到用户刷课表时才莫名失败；
        // 本项目在登录阶段就把它挡下来。
        script.ssoCallback = {
            ok("")
                .addHeader("Set-Cookie", "JSESSIONID=JXFW-SESSION-1; Path=/; HttpOnly")
        }
        script.jxfwHome = { redirect("${baseUrl()}/authserver/login?service=x") }

        val e = assertThrows(GdutException.SessionExpired::class.java) {
            newClient().login("3120012345", "pw")
        }
        assertThat(e.shouldRetryLogin).isTrue()
        assertThat(e.detail).contains("仍被 302 回统一认证")
    }

    @Test
    fun `研究生账号被拒绝`() {
        script.userConf = { ok(userConfHtml("2120012345")) }   // 首位 2 = 研究生
        val e = assertThrows(GdutException.UnsupportedUserType::class.java) {
            newClient().login("2120012345", "pw")
        }
        assertThat(e.userMessage).contains("研究生")
    }

    @Test
    fun `教师账号被拒绝`() {
        script.userConf = { ok(userConfHtml("0120012345")) }   // 首位 0 = 教师
        assertThrows(GdutException.UnsupportedUserType::class.java) {
            newClient().login("0120012345", "pw")
        }
    }

    @Test
    fun `getUserConf 拿不到学号时回退到用户输入的学号`() {
        script.userConf = { ok("<html><body>没有 option</body></html>") }
        val session = newClient().login("3120099999", "pw")
        assertThat(session.studentId).isEqualTo("3120099999")
    }

    @Test
    fun `登录页没有 pwdFromId 时抛 Parse 并附诊断`() {
        // 覆盖 route：让取登录页那一步返回维护页
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return if (request.path?.startsWith("/authserver/login") == true && request.method == "GET") {
                    ok("<html><head><title>系统维护中</title></head><body>请稍后再试</body></html>")
                } else {
                    route(request)
                }
            }
        }
        val e = assertThrows(GdutException.Parse::class.java) {
            newClient().login("3120012345", "pw")
        }
        assertThat(e.userMessage).contains("统一认证登录页")
    }

    @Test
    fun `无限重定向时抛 TooManyRedirects 并附完整链路`() {
        // 旧 Java 后端在这里的注释是"最后会无限重定向，因为 nginx 一直永远返回 302"，
        // 它的处置是硬编码 `if (maxTime > 3) break` —— 静默跳出、不报错，
        // 于是登录失败和登录成功走到了同一条后续代码路径。
        // 本实现明确抛异常并附上完整跳转链。
        //
        // 注意：RedirectFollower 对**重复 URL** 会判定为循环并停下（不抛 TooManyRedirects），
        // 所以要触发上限，每一跳的 URL 都必须不同 —— 这里用递增的 ticket 模拟。
        var counter = 0
        script.postLogin = { redirect("${baseUrl()}/new/ssoLogin?ticket=ST-loop-0") }
        script.ssoCallback = {
            counter++
            redirect("${baseUrl()}/new/ssoLogin?ticket=ST-loop-$counter")
        }

        val config = AuthServerConfig(hosts = hosts, maxRedirectHops = 4, verifyAfterLogin = false)
        val e = assertThrows(GdutException.TooManyRedirects::class.java) {
            AuthServerClient(client, config).login("3120012345", "pw")
        }
        assertThat(e.chain.size).isGreaterThan(3)
        assertThat(e.detail).contains("ST-loop-")
        assertThat(e.userMessage).contains("跳转次数过多")
    }

    @Test
    fun `关闭 checkCaptchaBeforeLogin 时不会请求验证码接口`() {
        val config = AuthServerConfig(hosts = hosts, checkCaptchaBeforeLogin = false)
        AuthServerClient(client, config).login("3120012345", "pw")
        assertThat(recorded.none { it.path?.contains("checkNeedCaptcha") == true }).isTrue()
    }

    @Test
    fun `关闭 verifyAfterLogin 时不会请求教务首页`() {
        val config = AuthServerConfig(hosts = hosts, verifyAfterLogin = false)
        AuthServerClient(client, config).login("3120012345", "pw")
        // 注意 SSO 回调后的 302 仍会访问 "/"，那是重定向链的一部分，不是主动校验。
        // 主动校验发生在 getUserConf 之前；关掉后 "/" 只会被访问一次（来自重定向链）
        val homeHits = recorded.count { it.path == "/" }
        assertThat(homeHits).isEqualTo(1)
    }

    // ================================================================== 辅助

    private fun newClient() = AuthServerClient(client, AuthServerConfig(hosts = hosts))

    /** 解析 `a=1&b=2&=3` 形式的表单，**保留空 name 的键**。 */
    private fun parseForm(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            out[URLDecoder.decode(k, Charsets.UTF_8)] = URLDecoder.decode(v, Charsets.UTF_8)
        }
        return out
    }

    private fun readFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.readText()
            ?: error("找不到 fixture: $name")
}
