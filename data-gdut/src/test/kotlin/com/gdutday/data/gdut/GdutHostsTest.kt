package com.gdutday.data.gdut

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GdutHosts] 与 [GdutEndpoints] 的**交叉校验**。
 *
 * ## 为什么需要这个测试
 *
 * 项目里有两份地址：
 * - [GdutEndpoints]：**协议文档**，逐条记录实测结果、字段含义、坑点，给人读的；
 * - [GdutHosts]：**运行时配置**，代码实际引用的地址。
 *
 * [GdutHosts] 的默认值理应从 [GdutEndpoints] 派生，但它们是两处独立书写的字符串。
 * 学校哪天换域名，维护者很可能只改了一处 —— 改了代码没改文档（下一个人被误导），
 * 或者改了文档没改代码（更糟，看起来改好了但其实没生效）。
 *
 * 这个测试就是把"两处必须一致"从口头约定变成编译期之后的硬约束。
 *
 * ## 顺带守住的几条不变量
 *
 * - base 地址**不含尾部斜杠**（`jxfwHome` 自己会补 `/`，两处都补就变成 `//`）
 * - 必须带 scheme
 * - 派生出的完整 URL 与文档里的常量**逐字符相等**
 */
class GdutHostsTest {

    private val hosts = GdutHosts()

    @Test
    fun `默认 base 与文档常量一致`() {
        assertThat(GdutHosts.DEFAULT_AUTHSERVER_BASE)
            .isEqualTo("https://" + GdutEndpoints.AUTHSERVER_HOST)
        assertThat(GdutHosts.DEFAULT_JXFW_BASE)
            .isEqualTo("https://" + GdutEndpoints.JXFW_HOST)
        assertThat(hosts.isProduction).isTrue()
    }

    @Test
    fun `base 地址不含尾部斜杠且带 scheme`() {
        for (base in listOf(hosts.authserverBase, hosts.jxfwBase)) {
            assertThat(base.endsWith("/")).isFalse()
            assertThat(base.startsWith("https://")).isTrue()
        }
    }

    @Test
    fun `统一认证端点逐条与文档一致`() {
        assertThat(hosts.authLogin).isEqualTo(GdutEndpoints.AUTHSERVER_LOGIN)
        assertThat(hosts.authLoginQueryUsername).isEqualTo(GdutEndpoints.AUTHSERVER_LOGIN_QUERY_USERNAME)
        assertThat(hosts.authCheckCaptcha).isEqualTo(GdutEndpoints.AUTHSERVER_CHECK_CAPTCHA)
        assertThat(hosts.authCaptchaImage).isEqualTo(GdutEndpoints.AUTHSERVER_CAPTCHA_IMAGE)
        assertThat(hosts.authSliderCaptcha).isEqualTo(GdutEndpoints.AUTHSERVER_SLIDER_CAPTCHA)
        assertThat(hosts.authUserConf).isEqualTo(GdutEndpoints.AUTHSERVER_USER_CONF)
    }

    @Test
    fun `教务系统端点逐条与文档一致`() {
        assertThat(hosts.jxfwHome).isEqualTo(GdutEndpoints.JXFW_HOME)
        assertThat(hosts.jxfwSsoLogin).isEqualTo(GdutEndpoints.JXFW_SSO_LOGIN)
    }

    @Test
    fun `主机名提取正确，用于 cookie 域匹配与 sec-fetch-site 头`() {
        assertThat(hosts.authserverHost).isEqualTo(GdutEndpoints.AUTHSERVER_HOST)
        assertThat(hosts.jxfwHost).isEqualTo(GdutEndpoints.JXFW_HOST)
    }

    @Test
    fun `SSO 回调必须走 https`() {
        // http://jxfw.gdut.edu.cn/new/ssoLogin 会先 301 到 https（实测）。
        // 多一跳本身不致命，但部分 HTTP 客户端在 301 时会把 POST 降级成 GET 或丢掉 body，
        // 而 CAS 回调是带 query 的 GET，所以真正的风险是**cookie 在跨 scheme 跳转时不被发送**。
        assertThat(GdutEndpoints.JXFW_SSO_LOGIN).startsWith("https://")
        assertThat(GdutEndpoints.JXFW_SERVICE_PARAM).startsWith("https://")
        // service 参数与回调地址必须是同一个值，否则 CAS 签发的 ticket 换不到会话
        assertThat(GdutEndpoints.JXFW_SERVICE_PARAM).isEqualTo(GdutEndpoints.JXFW_SSO_LOGIN)
    }

    @Test
    fun `指向 MockWebServer 时 isProduction 为 false`() {
        // 这条守的是"测试环境不会误判成生产"。AuthServerClient 在若干地方
        // 会根据 isProduction 决定要不要打印诊断信息，判错会导致测试里泄漏真实 cookie。
        val local = GdutHosts(
            authserverBase = "http://localhost:1234",
            jxfwBase = "http://localhost:1234",
        )
        assertThat(local.isProduction).isFalse()
        assertThat(local.authserverHost).isEqualTo("localhost")
    }

    @Test
    fun `非法 base 在构造时就被拒绝`() {
        // 快速失败优于"请求打到了一个奇怪的地址然后超时"
        assertThat(runCatching { GdutHosts(authserverBase = "") }.isFailure).isTrue()
        assertThat(runCatching { GdutHosts(authserverBase = "authserver.gdut.edu.cn") }.isFailure).isTrue()
        assertThat(runCatching { GdutHosts(jxfwBase = "https://jxfw.gdut.edu.cn/") }.isFailure).isTrue()
    }
}
