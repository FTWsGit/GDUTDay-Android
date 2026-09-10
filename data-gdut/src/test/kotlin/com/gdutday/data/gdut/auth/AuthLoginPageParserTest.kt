package com.gdutday.data.gdut.auth

import com.gdutday.core.model.GdutException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [AuthLoginPageParser] 的单元测试，**跑在真实抓取的登录页上**。
 *
 * fixture 说明见 `src/test/resources/fixtures/README.md`。两份 HTML 都是
 * 2026-09-10 从 `authserver.gdut.edu.cn` 抓的未登录页面，不含个人信息。
 *
 * 为什么要用真实页面而不是手写一段最小 HTML：
 * 手写 fixture 只会包含"我以为存在的字段"，而真实页面里那些
 * **我根本不知道的东西**（第二个表单、`encryptSalt` 与 `pwdEncryptSalt` 的区别、
 * `service` 被序列化成 JSON 数组、Thymeleaf 没渲染完的 `th:src` 属性）
 * 才是真正会咬人的地方。
 */
class AuthLoginPageParserTest {

    /** 不带 service 参数的登录页。`var service = null;` */
    private val pageWithoutService = readFixture("authserver_login_page.real.html")

    /**
     * 带 service 参数的登录页 —— 这是**真实登录流程会拿到的那一份**。
     *
     * 抓取方式：`curl -L https://jxfw.gdut.edu.cn/new/ssoLogin`，跟随 1 次 302 后落到
     * `https://authserver.gdut.edu.cn/authserver/login?service=https%3A%2F%2Fjxfw.gdut.edu.cn%2Fnew%2FssoLogin`
     *
     * `var service = ["https:\/\/jxfw.gdut.edu.cn\/new\/ssoLogin"];`
     */
    private val pageWithService = readFixture("authserver_login_page_with_service.real.html")

    private val pageUrlWithService =
        "https://authserver.gdut.edu.cn/authserver/login?service=https%3A%2F%2Fjxfw.gdut.edu.cn%2Fnew%2FssoLogin"

    // ------------------------------------------------------------------ 表单解析

    @Test
    fun `真实页面能解析出 pwdFromId 表单`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)

        assertThat(form.action).isEqualTo("/authserver/login")
        assertThat(form.cllt).isEqualTo("userNameLogin")
        assertThat(form.dllt).isEqualTo("generalLogin")
        assertThat(form.lt).isEmpty()
        assertThat(form.execution).isNotEmpty()
        assertThat(form.execution).contains("_") // UUID_JWT 的形式
    }

    @Test
    fun `salt 取自真实页面的 pwdEncryptSalt 且是合法 AES key`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)

        assertThat(form.salt).isEqualTo("xaOfScaw6epvgypH")
        assertThat(form.salt).hasLength(16)
        assertThat(form.hasUsableSalt()).isTrue()
    }

    @Test
    fun `隐藏域里包含那个 name 为空的 salt 字段`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)

        assertThat(form.hasEmptyNameField()).isTrue()
        val emptyName = form.hiddenFields.filter { it.name.isEmpty() }
        assertThat(emptyName).hasSize(1)
        // 它的 value 就是 salt —— 浏览器会把它提交成 `=<salt>`，我们必须原样复刻
        assertThat(emptyName.single().value).isEqualTo(form.salt)
    }

    @Test
    fun `隐藏域集合与实测抓包完全一致`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)

        // 实测（2026-09-10）#pwdFromId 内的 input[type=hidden]，按文档顺序：
        //   saltPassword(name=password, value="")
        //   _eventId(submit) / cllt(userNameLogin) / dllt(generalLogin) / lt("")
        //   pwdEncryptSalt(无 name)
        //   execution(...)
        assertThat(form.hiddenFields.map { it.name })
            .containsExactly("password", "_eventId", "cllt", "dllt", "lt", "", "execution")
            .inOrder()

        assertThat(form.hiddenFields.first { it.name == "_eventId" }.value).isEqualTo("submit")
        assertThat(form.hiddenFields.first { it.name == "password" }.value).isEmpty()
    }

    @Test
    fun `不会把第二个表单（动态登录）的字段混进来`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)

        // 页面上还有一个 cllt=dynamicLogin 的表单，它用的 salt 元素 id 是 encryptSalt（不是 pwdEncryptSalt）。
        // 如果解析器没限定在 #pwdFromId 内，这两个特征值就会漏进来。
        assertThat(form.cllt).isEqualTo("userNameLogin")
        assertThat(form.hiddenFields.none { it.value == "dynamicLogin" }).isTrue()
        assertThat(form.hiddenFields.none { it.name == "dynamicCode" }).isTrue()
        // 而且 salt 必须是 pwdEncryptSalt 那个，不是 encryptSalt 那个
        assertThat(form.salt).isEqualTo("xaOfScaw6epvgypH")
    }

    @Test
    fun `passwordText 不在隐藏域里 - 因为浏览器提交前会 disabled 它`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)
        // login.js: $(LOGIN_PASSWORD_ID).attr("disabled","disabled")
        // 它是 type=password，本来就不该被收集；这条测试防止有人"好心"把它加进来
        assertThat(form.hiddenFields.none { it.name == "passwordText" }).isTrue()
        assertThat(form.hasPasswordField()).isTrue() // name=password 的 saltPassword 才是真正提交的字段
    }

    // ------------------------------------------------------------------ 内联变量

    @Test
    fun `service 变量是 JSON 数组形态也能正确解出 - 这是最容易踩的坑`() {
        // 真实页面里是：var service = ["https:\/\/jxfw.gdut.edu.cn\/new\/ssoLogin"];
        // 只匹配 `var service = "..."` 的正则在这里会返回 null，
        // 进而导致 submitUrl() 丢掉 ?service=，登录静默失败。
        // 注意断言用普通字符串而非原始字符串：原始字符串以反斜杠结尾时很难写对。
        assertThat(pageWithService).contains("var service = [\"https:\\/\\/jxfw.gdut.edu.cn\\/new\\/ssoLogin\"]")

        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)
        assertThat(form.service).isEqualTo("https://jxfw.gdut.edu.cn/new/ssoLogin")
    }

    @Test
    fun `不带 service 的页面解析出 null 而不是抛异常`() {
        assertThat(pageWithoutService).contains("var service = null")
        val form = AuthLoginPageParser.parse(
            pageWithoutService,
            "https://authserver.gdut.edu.cn/authserver/login?type=userNameLogin",
        )
        assertThat(form.service).isNull()
    }

    @Test
    fun `captchaSwitch 与 badCredentialsCount 从真实页面解出`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)

        // 实测 captchaSwitch == "2" → 滑块模式（"1" 才是图形验证码）
        assertThat(form.captchaSwitch).isEqualTo("2")
        // 实测 _badCredentialsCount="5"（注意等号两边没有空格）
        assertThat(form.badCredentialsCount).isEqualTo(5)
    }

    @Test
    fun `extractInlineVar 能处理等号两边无空格、单引号、数字、布尔等写法`() {
        val html = """
            <script>
              var tight="2";
              var loose = "hello world" ;
              var single = 'abc';
              var num = 5;
              var bool = true;
              var nul = null;
              var undef = undefined;
              var arr = ["https:\/\/x.y\/z"];
              var withSemicolonInString = "a;b";
              var multi = ["one","two"];
            </script>
        """.trimIndent()

        assertThat(AuthLoginPageParser.extractInlineVar(html, "tight")).isEqualTo("2")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "loose")).isEqualTo("hello world")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "single")).isEqualTo("abc")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "num")).isEqualTo("5")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "bool")).isEqualTo("true")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "nul")).isNull()
        assertThat(AuthLoginPageParser.extractInlineVar(html, "undef")).isNull()
        assertThat(AuthLoginPageParser.extractInlineVar(html, "arr")).isEqualTo("https://x.y/z")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "withSemicolonInString")).isEqualTo("a;b")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "multi")).isEqualTo("one")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "notThere")).isNull()
    }

    @Test
    fun `extractInlineVar 不会把名字前缀相同的变量认错`() {
        // 页面上同时存在 `service` 和 `_badCredentialsCount`、`captchaSwitch`，
        // 还有 `isQrLogin` / `isQrLoginEnabled` 这种前缀相同的对，必须精确匹配
        val html = """
            var isQrLogin = "false";
            var isQrLoginEnabled = "true";
            var needCaptcha = "";
        """.trimIndent()
        assertThat(AuthLoginPageParser.extractInlineVar(html, "isQrLogin")).isEqualTo("false")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "isQrLoginEnabled")).isEqualTo("true")
        assertThat(AuthLoginPageParser.extractInlineVar(html, "needCaptcha")).isEmpty()
    }

    // ------------------------------------------------------------------ 提交地址

    @Test
    fun `submitUrl 会把 service 拼进 query - 漏掉它就登不上`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)
        val url = form.submitUrl()

        assertThat(url.toString()).isEqualTo(
            "https://authserver.gdut.edu.cn/authserver/login" +
                "?service=https%3A%2F%2Fjxfw.gdut.edu.cn%2Fnew%2FssoLogin",
        )
        assertThat(url.queryParameter("service")).isEqualTo("https://jxfw.gdut.edu.cn/new/ssoLogin")
    }

    @Test
    fun `没有 service 时 submitUrl 就是解析后的 action`() {
        val form = AuthLoginPageParser.parse(
            pageWithoutService,
            "https://authserver.gdut.edu.cn/authserver/login?type=userNameLogin",
        )
        assertThat(form.submitUrl().toString())
            .isEqualTo("https://authserver.gdut.edu.cn/authserver/login")
    }

    @Test
    fun `相对 action 会按页面 URL 解析成绝对地址`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)
        assertThat(form.action).startsWith("/") // 原始值是相对路径
        assertThat(form.resolveAction().host).isEqualTo("authserver.gdut.edu.cn")
        assertThat(form.resolveAction().scheme).isEqualTo("https")
    }

    // ------------------------------------------------------------------ 错误识别

    @Test
    fun `真实登录页被识别为登录页`() {
        assertThat(AuthLoginPageParser.looksLikeLoginPage(pageWithService)).isTrue()
        assertThat(AuthLoginPageParser.looksLikeLoginPage(pageWithoutService)).isTrue()
    }

    @Test
    fun `初始页面的错误容器是空的，因此 extractLoginError 返回 null`() {
        // 页面里有 <span id="showErrorTip" class="form-error"></span>，但是空的。
        // 登录成功时也会返回这个页面（如果没跳转），所以"是登录页"不等于"登录失败"，
        // 必须靠"错误容器里有没有字"来区分。
        assertThat(AuthLoginPageParser.extractLoginError(pageWithService)).isNull()
    }

    @Test
    fun `服务端填入错误文案后能提取出来`() {
        val failed = pageWithService.replace(
            """<span id="showErrorTip" class="form-error"></span>""",
            """<span id="showErrorTip" class="form-error">用户名或密码错误</span>""",
        )
        assertThat(failed).isNotEqualTo(pageWithService) // 确认替换真的发生了
        assertThat(AuthLoginPageParser.extractLoginError(failed)).isEqualTo("用户名或密码错误")
    }

    @Test
    fun `非登录页不会被误判`() {
        assertThat(AuthLoginPageParser.looksLikeLoginPage("""{"code":0}""")).isFalse()
        assertThat(AuthLoginPageParser.looksLikeLoginPage("<html><body>404</body></html>")).isFalse()
        assertThat(AuthLoginPageParser.looksLikeLoginPage("")).isFalse()
        assertThat(AuthLoginPageParser.extractLoginError("""{"total":1,"rows":[]}""")).isNull()
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    fun `页面里没有 pwdFromId 时抛 Parse 异常并带上诊断信息`() {
        val e = assertThrows(GdutException.Parse::class.java) {
            AuthLoginPageParser.parse("<html><body>系统维护中</body></html>", "https://x/y")
        }
        assertThat(e.userMessage).contains("统一认证登录页")
        assertThat(e.detail).contains("一个 <form> 都没有")
    }

    @Test
    fun `被下发到移动版页面时诊断信息里会列出实际存在的表单`() {
        val mobile = """
            <html><head><title>统一身份认证</title></head><body>
            <form id="mobileLoginForm" action="/authserver/mlogin">
              <input type="hidden" name="execution" value="abc"/>
            </form>
            </body></html>
        """.trimIndent()
        val e = assertThrows(GdutException.Parse::class.java) {
            AuthLoginPageParser.parse(mobile, "https://authserver.gdut.edu.cn/authserver/login")
        }
        assertThat(e.detail).contains("mobileLoginForm")
        assertThat(e.detail).contains("没有 #pwdFromId")
    }

    @Test
    fun `toString 不泄露 salt 与 execution 全文`() {
        val form = AuthLoginPageParser.parse(pageWithService, pageUrlWithService)
        val s = form.toString()
        assertThat(s).doesNotContain("xaOfScaw6epvgypH")
        assertThat(s).contains("xa***")           // 只留前 2 位 + 长度
        assertThat(s).doesNotContain(form.execution!!)
        assertThat(s).contains("<empty-name>")    // 空名字段用占位符表示，不打印它的值
    }

    private fun readFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.readText()
            ?: error("找不到 fixture: $name（见 src/test/resources/fixtures/README.md）")
}
