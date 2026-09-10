package com.gdutday.data.gdut

import com.gdutday.core.model.GdutException

/**
 * 各系统的基础地址，以及由它们派生出的全部端点。
 *
 * ## 为什么要有这个类（[GdutEndpoints] 里不都是常量吗）
 *
 * 因为**常量没法测试**。整个登录流程（取页面 → 解析 → 加密 → POST → 跟随 302 → 校验会话）
 * 是这个项目风险最高的部分，而它只有在真实的 `authserver.gdut.edu.cn` 上才能跑 ——
 * 那意味着每次改代码都要拿真账号去撞学校的服务器，既慢又不负责任。
 *
 * 有了这个类，测试可以把 [authserverBase] / [jxfwBase] 指向 MockWebServer 的
 * `http://localhost:<port>`，然后用真实抓取的登录页 fixture 走完整条链路。
 * 这样"重定向跟随""错误页识别""cookie 传递"这些最容易出错的逻辑都能离线回归。
 *
 * 附带好处：将来如果学校换域名、或者要做私有化部署/抓包调试，改一处即可。
 *
 * ## 与 [GdutEndpoints] 的分工
 *
 * - [GdutEndpoints]：**协议文档**。逐条记录实测结果、字段含义、坑点，给人看的。
 * - [GdutHosts]：**运行时配置**。代码实际引用的地址，默认值取自 [GdutEndpoints]。
 *
 * 两者的默认值由 [GdutHostsTest] 交叉校验，防止改了文档忘了改代码（或反之）。
 *
 * @property authserverBase 统一认证基础地址，**不含尾部斜杠**
 * @property jxfwBase 本科教务系统基础地址，**不含尾部斜杠**
 */
public data class GdutHosts(
    public val authserverBase: String = DEFAULT_AUTHSERVER_BASE,
    public val jxfwBase: String = DEFAULT_JXFW_BASE,
) {
    init {
        for ((name, base) in listOf("authserverBase" to authserverBase, "jxfwBase" to jxfwBase)) {
            if (base.isBlank()) throw IllegalArgumentException("$name 不能为空")
            if (base.endsWith("/")) throw IllegalArgumentException("$name 不应以 '/' 结尾: $base")
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                throw IllegalArgumentException("$name 必须带 scheme: $base")
            }
        }
    }

    /** 主机名，用于 `Origin` / `sec-fetch-site` 头和 cookie 域匹配。 */
    public val authserverHost: String get() = hostOf(authserverBase)
    public val jxfwHost: String get() = hostOf(jxfwBase)

    /** 是否指向真实的生产环境。测试里为 false。 */
    public val isProduction: Boolean
        get() = authserverBase == DEFAULT_AUTHSERVER_BASE && jxfwBase == DEFAULT_JXFW_BASE

    // ------------------------------------------------------- 统一身份认证

    /** 登录页 / 登录提交地址（同 path，GET 取页面、POST 提交）。 */
    public val authLogin: String get() = "$authserverBase/authserver/login"

    /** GET 登录页时的查询串。 */
    public val authLoginQueryUsername: String get() = "type=userNameLogin"

    /** 是否需要人机验证。⚠ 响应 Content-Type 是 `text/plain` 而不是 JSON。 */
    public val authCheckCaptcha: String get() = "$authserverBase/authserver/checkNeedCaptcha.htl"

    /** 图形验证码图片（仅 `captchaSwitch == "1"` 时前端会用到）。 */
    public val authCaptchaImage: String get() = "$authserverBase/authserver/getCaptcha.htl"

    /** 滑块验证码 HTML 片段（`captchaSwitch == "2"` 时）。本项目不自动化它。 */
    public val authSliderCaptcha: String get() = "$authserverBase/authserver/common/toSliderCaptcha.htl"

    /** 登录后取学号与身份。返回 HTML，学号在 `<option value='…' selected>` 里。 */
    public val authUserConf: String get() = "$authserverBase/personalInfo/common/getUserConf"

    // ------------------------------------------------------- 本科教务系统

    /** 首页。未登录 302 到统一认证，已登录 200 —— 用作会话有效性探针。 */
    public val jxfwHome: String get() = "$jxfwBase/"

    /** SSO 回调入口。也是登录流程的**入口地址**（先访问它，让它 302 带上 service）。 */
    public val jxfwSsoLogin: String get() = "$jxfwBase/new/ssoLogin"

    /** 教务系统直登（绕过统一认证），只接受 POST；GET 返回 405。 */
    public val jxfwDirectLogin: String get() = "$jxfwBase/new/login"

    /** 图形验证码。实测 JPEG 140×60，并下发 `JSESSIONID; Path=/; Secure; HttpOnly`。 */
    public val jxfwCaptcha: String get() = "$jxfwBase/yzm"

    /** 学期列表页。当前学期在 `<option value='202501' selected>` 里。 */
    public val jxfwTermList: String get() = "$jxfwBase/xsksap!ksapList.action"

    /** 课表接口 A：按教学班聚合，返回 HTML（内含 `var kbxx = [...]`）。 */
    public val jxfwScheduleAllKbList: String get() = "$jxfwBase/xsgrkbcx!xsAllKbList.action"

    /** 接口 A 必须的 Referer。F# 源码里标了 "TODO: 重要！需要记录"。 */
    public val jxfwScheduleAllKbReferer: String get() = "$jxfwBase/xsgrkbcx!getXsgrbkList.action"

    /** 课表接口 B：按周炸开，返回 JSON `{total, rows:[…]}`。 */
    public val jxfwScheduleDataList: String get() = "$jxfwBase/xsgrkbcx!getDataList.action"

    /** 考试安排。`xqmc` 字段是本科生数据里唯一的校区线索。 */
    public val jxfwExamDataList: String get() = "$jxfwBase/xsksap!getDataList.action"

    /** 成绩。 */
    public val jxfwScoreDataList: String get() = "$jxfwBase/xskccjxx!getDataList.action"

    /** jxfw 所有 XHR 接口通用的 Referer。 */
    public val jxfwDefaultReferer: String get() = "$jxfwBase/"

    public companion object {
        public const val DEFAULT_AUTHSERVER_BASE: String = GdutEndpoints.AUTHSERVER_BASE
        public const val DEFAULT_JXFW_BASE: String = GdutEndpoints.JXFW_BASE

        /** 生产环境配置。 */
        public val PRODUCTION: GdutHosts = GdutHosts()

        /**
         * 指向本地 MockWebServer 的测试配置。
         *
         * ⚠ MockWebServer 只有一个端口，所以这里把两个 base 都指向同一个地址。
         * 测试脚本要按 **path** 区分请求（`/authserver/login` vs `/new/ssoLogin`），
         * 这与生产环境的路径布局一致，因此业务代码不需要做任何特判。
         */
        public fun forTestServer(baseUrl: String): GdutHosts =
            GdutHosts(authserverBase = baseUrl.trimEnd('/'), jxfwBase = baseUrl.trimEnd('/'))

        private fun hostOf(base: String): String =
            base.substringAfter("://").substringBefore('/').substringBefore(':')
                .ifBlank { throw GdutException.Local("无法从 $base 解析出主机名") }
    }
}
