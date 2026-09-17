package com.gdutday.core.model

/**
 * 各系统的基础地址，以及由它们派生出的全部端点。
 *
 * ## 为什么要有这个类
 *
 * 因为**常量没法测试**。整个登录流程（取页面 → 解析 → 加密 → POST → 跟随 302 → 校验会话）
 * 是这个项目风险最高的部分，而它只有在真实的 `authserver.gdut.edu.cn` 上才能跑 ——
 * 那意味着每次改代码都要拿真账号去撞学校的服务器，既慢又不负责任。
 *
 * 有了这个类，测试可以把 [authserverBase] / [jxfwBase] 指向 MockWebServer 的
 * `http://localhost:<port>`，然后用真实抓取的登录页 fixture 走完整条链路。
 *
 * ## 为什么放在 core-model
 *
 * `GdutSession` 绑定 [GdutHosts]（cookie 域匹配、会话有效性判断都依赖它），
 * 而 `core-datastore` 要持久化会话、`core-network` 要签名引用会话 ——
 * core 必须是叶子，不能反向依赖协议层 `data-gdut`。
 * 端点的**协议文档**（实测记录、字段含义、坑点）仍在 `data-gdut` 的 `GdutEndpoints`，
 * 两边的默认值由该模块的 `GdutHostsTest` 交叉校验。
 *
 * @property authserverBase 统一认证基础地址，**不含尾部斜杠**
 * @property jxfwBase 本科教务系统基础地址，**不含尾部斜杠**
 */
public data class GdutHosts(
    public val authserverBase: String = DEFAULT_AUTHSERVER_BASE,
    public val jxfwBase: String = DEFAULT_JXFW_BASE,
    public val jwcwxBase: String = DEFAULT_JWCWX_BASE,
) {
    init {
        for ((name, base) in listOf(
            "authserverBase" to authserverBase,
            "jxfwBase" to jxfwBase,
            "jwcwxBase" to jwcwxBase,
        )) {
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
    public val jwcwxHost: String get() = hostOf(jwcwxBase)

    /** 是否指向真实的生产环境。测试里为 false。 */
    public val isProduction: Boolean
        get() = authserverBase == DEFAULT_AUTHSERVER_BASE && jxfwBase == DEFAULT_JXFW_BASE &&
            jwcwxBase == DEFAULT_JWCWX_BASE

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

    // ------------------------------------------------- 班级课表（xsbjkbcx，实测 2026-09-12）

    /** 班级课表主接口：GET，返回 JSON 数组 `[课表rows, 周日期rows]`。Referer 非必需。 */
    public val jxfwClassScheduleGetKbRq: String get() = "$jxfwBase/xsbjkbcx!getKbRq.action"

    /** 班级课表备接口：GET，返回 HTML（内含 `var kbxx = [...]`，全学期聚合）。 */
    public val jxfwClassScheduleAllKbList: String get() = "$jxfwBase/xsbjkbcx!xsAllKbList.action"

    /** 班级选择级联接口：POST，响应为 `text`（`<guid>^getFind:<JSON数组>`）。 */
    public val jxfwClassScheduleFind: String get() = "$jxfwBase/xsbjkbcx!getFind.action"

    /** 班级课表查询主页面（页面里服务端渲染了全部班级 `<option>`）。 */
    public val jxfwClassScheduleMain: String get() = "$jxfwBase/xsbjkbcx!xsbjkbMain.action"

    /** 单门课程上课信息明细。⚠ 必须带 [jxfwClassScheduleReferer] 指定的 Referer。 */
    public val jxfwClassScheduleDetail: String get() = "$jxfwBase/xsbjkbcx!getSkxxDataList.action"

    /** 班级课表接口的通用 Referer。实测 2026-09-13 起全站校验 Referer，站内任意路径即可。 */
    public val jxfwClassScheduleReferer: String get() = jxfwDefaultReferer

    /** 成绩。 */
    public val jxfwScoreDataList: String get() = "$jxfwBase/xskccjxx!getDataList.action"

    /** jxfw 所有 XHR 接口通用的 Referer。 */
    public val jxfwDefaultReferer: String get() = "$jxfwBase/"

    // ------------------------------------------------- 空闲教室查询（jwcwx，实测 2026-09-17）

    /**
     * CAS 登录 service 参数（指向 jwcwx 的回调入口）。
     *
     * 实测（2026-09-17）：jwcwx 有 `/login/cas` 统一认证入口，登录页结构与 jxfw 完全一致，
     * 可复用 authserver 登录实现，不需要微信 OAuth。
     */
    public val jwcwxCasService: String get() = "$jwcwxBase/login/cas"

    /** 教学楼列表：GET，返回 `{code,data:[{jzwdm,jzwmc,szxqdm,xqmc}…]}`。 */
    public val jwcwxFreeRoomBuildings: String get() = "$jwcwxBase/free-class-room/buildingData"

    /** 教室占用查询：GET `jzwdm=<楼>&rq=<yyyy-MM-dd>`，返回该楼当天所有占用行。 */
    public val jwcwxFreeRoomUsedData: String get() = "$jwcwxBase/free-class-room/classroomUsedData"

    public companion object {
        /** 生产默认地址（与 `data-gdut` 的 `GdutEndpoints` 交叉校验，勿单边修改）。 */
        public const val DEFAULT_AUTHSERVER_BASE: String = "https://authserver.gdut.edu.cn"

        /** 生产默认地址（与 `data-gdut` 的 `GdutEndpoints` 交叉校验，勿单边修改）。 */
        public const val DEFAULT_JXFW_BASE: String = "https://jxfw.gdut.edu.cn"

        /** 生产默认地址（微信公众号教务的 Web 端，空闲教室查询，实测 2026-09-17）。 */
        public const val DEFAULT_JWCWX_BASE: String = "https://jwcwx.gdut.edu.cn"

        /** 生产环境配置。 */
        public val PRODUCTION: GdutHosts = GdutHosts()

        /**
         * 指向本地 MockWebServer 的测试配置。
         *
         * ⚠ MockWebServer 只有一个端口，所以这里把所有 base 都指向同一个地址。
         * 测试脚本要按 **path** 区分请求（`/authserver/login` vs `/new/ssoLogin`），
         * 这与生产环境的路径布局一致，因此业务代码不需要做任何特判。
         */
        public fun forTestServer(baseUrl: String): GdutHosts =
            GdutHosts(
                authserverBase = baseUrl.trimEnd('/'),
                jxfwBase = baseUrl.trimEnd('/'),
                jwcwxBase = baseUrl.trimEnd('/'),
            )

        private fun hostOf(base: String): String =
            base.substringAfter("://").substringBefore('/').substringBefore(':')
                .ifBlank { throw GdutException.Local("无法从 $base 解析出主机名") }
    }
}
