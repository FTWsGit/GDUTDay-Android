package com.gdutday.data.gdut

/**
 * 广工各系统的 URL 常量。**所有硬编码地址集中在此**，学校改版时只改这一个文件。
 *
 * 每个常量都标注了实测状态与来源，便于判断"这条还能不能用"。
 */
public object GdutEndpoints {

    // ------------------------------------------------------------------ 统一身份认证

    /**
     * 统一认证基础地址（scheme + host，不含尾部斜杠）。
     *
     * 代码里**不要直接引用本类的常量**去拼 URL —— 用 [GdutHosts]，
     * 那样测试才能把地址指向 MockWebServer。本类是协议文档兼默认值来源。
     */
    public const val AUTHSERVER_BASE: String = "https://authserver.gdut.edu.cn"

    /** 本科教务系统基础地址。同上，代码里请用 [GdutHosts]。 */
    public const val JXFW_BASE: String = "https://jxfw.gdut.edu.cn"

    /** 统一认证（CAS 改造版）主机。 */
    public const val AUTHSERVER_HOST: String = "authserver.gdut.edu.cn"

    /**
     * 统一认证登录页 / 登录提交地址（同一个 path，GET 取页面、POST 提交）。
     *
     * 实测（2026-09-10）：GET 带 `?type=userNameLogin` 返回 200 + 完整登录页 HTML，
     * 响应头下发 `JSESSIONID`（Path=/authserver, HttpOnly）与 `route`（负载均衡粘性 cookie）。
     */
    public const val AUTHSERVER_LOGIN: String = "https://authserver.gdut.edu.cn/authserver/login"

    /** GET 登录页时带的查询参数，决定页面渲染"用户名密码登录"标签页。 */
    public const val AUTHSERVER_LOGIN_QUERY_USERNAME: String = "type=userNameLogin"

    /**
     * 检查账号是否需要人机验证。
     *
     * 实测响应：`{"isNeed":false}`，
     * ⚠ **Content-Type 是 `text/plain;charset=UTF-8` 而不是 `application/json`**，
     * 所以不要用"响应类型必须是 JSON"的严格解析器。
     */
    public const val AUTHSERVER_CHECK_CAPTCHA: String =
        "https://authserver.gdut.edu.cn/authserver/checkNeedCaptcha.htl"

    /**
     * 图形验证码图片。仅当登录页内联变量 `captchaSwitch == "1"` 时前端才会用到它。
     *
     * 实测（2026-09-10）`captchaSwitch == "2"`，即**滑块模式**，所以这个地址当前不会被请求。
     * 保留是为了学校把开关改回 `"1"` 时能直接支持。
     */
    public const val AUTHSERVER_CAPTCHA_IMAGE: String =
        "https://authserver.gdut.edu.cn/authserver/getCaptcha.htl"

    /**
     * 滑块验证码的 HTML 片段。
     *
     * `login.js` 里：
     * ```js
     * function createSliderCaptcha() {
     *     $.ajax({ url: contextPath + "/common/toSliderCaptcha.htl", type: "get",
     *              success: function (html) { $("#captchaDiv").hide(); $("#sliderCaptchaDiv").html(html) } });
     * }
     * ```
     * 触发条件：`needCaptcha && captchaSwitch == "2" && cllt == "userNameLogin"`。
     *
     * **本项目不实现滑块自动化**（第三方行为验证，自动化既脆弱又不该做）。
     * 这个常量的唯一用途是：当 [AUTHSERVER_CHECK_CAPTCHA] 返回 `isNeed:true` 时，
     * 在诊断信息里告诉用户"服务端要求走 `toSliderCaptcha.htl`"，
     * 并引导他改用教务系统直登（[JXFW_DIRECT_LOGIN] + [JXFW_CAPTCHA] 的图形验证码）。
     */
    public const val AUTHSERVER_SLIDER_CAPTCHA: String =
        "https://authserver.gdut.edu.cn/authserver/common/toSliderCaptcha.htl"

    /**
     * 登录后取用户信息，用来确定学号与身份类型。
     *
     * 返回的是 **HTML**，学号藏在 `<option value='3120xxxxxx' selected>` 里，
     * 用正则 `<option value='(\d+)' selected>` 抠。
     * 学号首位数字决定身份：3=本科 / 2=研究生 / 0=教师。
     */
    public const val AUTHSERVER_USER_CONF: String =
        "https://authserver.gdut.edu.cn/personalInfo/common/getUserConf"

    /** 从 [AUTHSERVER_USER_CONF] 的 HTML 里抠学号的正则。 */
    public const val USER_CONF_STUDENT_ID_REGEX: String = """<option value='(\d+)' selected>"""

    // ------------------------------------------------------------------ 本科教务系统 jxfw

    /** 本科教务系统主机。 */
    public const val JXFW_HOST: String = "jxfw.gdut.edu.cn"

    /** 教务系统首页。未登录时 302 跳统一认证；已登录时 200。用作**会话有效性探针**。 */
    public const val JXFW_HOME: String = "https://jxfw.gdut.edu.cn/"

    /**
     * SSO 回调入口。统一认证成功后带着 `ticket` 302 到这里，
     * jxfw 校验 ticket 并下发自己的 `JSESSIONID`。
     *
     * ⚠ **必须用 https**：`http://jxfw.gdut.edu.cn/new/ssoLogin` 会先 301 到 https，
     * 多一跳且部分 HTTP 客户端在 301 时会把 POST 降级/丢 body。
     */
    public const val JXFW_SSO_LOGIN: String = "https://jxfw.gdut.edu.cn/new/ssoLogin"

    /** 统一认证登录时 `service` 参数的取值（指向 [JXFW_SSO_LOGIN]）。 */
    public const val JXFW_SERVICE_PARAM: String = "https://jxfw.gdut.edu.cn/new/ssoLogin"

    /**
     * 教务系统**直登**接口（绕过统一认证），只接受 POST。
     *
     * 实测：`GET /new/login` 返回 **405 Method Not Allowed**，说明它是纯 JSON API，
     * 没有配套的 HTML 登录页。
     *
     * 请求体：`account`(学号) `pwd`(密码) `verifycode`(图形验证码)
     * 响应体：`{"code":0,"data":...,"message":...}` 成功；
     *        `{"code":-1,"data":null,"message":"验证码不正确"}` 失败。
     * ⚠ 响应 **HTTP 200 但 Content-Type 是 `text/html;charset=utf-8`**，body 才是 JSON。
     */
    public const val JXFW_DIRECT_LOGIN: String = "https://jxfw.gdut.edu.cn/new/login"

    /**
     * 教务系统图形验证码。
     *
     * 实测：200，`Content-Type: image/jpeg;charset=UTF-8`，JPEG 140×60，
     * 并下发 `Set-Cookie: JSESSIONID=...; Path=/; Secure; HttpOnly`。
     * **必须把这个 JSESSIONID 带到 [JXFW_DIRECT_LOGIN] 的请求里**，否则永远提示验证码不正确。
     */
    public const val JXFW_CAPTCHA: String = "https://jxfw.gdut.edu.cn/yzm"

    /**
     * 学期列表页（考试安排页面）。当前学期藏在
     * `<option value='202501' selected>` 里。
     */
    public const val JXFW_TERM_LIST: String = "https://jxfw.gdut.edu.cn/xsksap!ksapList.action"

    /**
     * 课表接口 A：**按教学班聚合**，返回 HTML，内含 `var kbxx = [ {...} ];`。
     *
     * 字段：`kcmc` `kcbh` `jxbmc` `kcrwdm` `jcdm2`(逗号分隔节次) `zcs`(逗号分隔周次)
     *      `xq`(星期) `jxcdmcs`(场地，可能多个) `teaxms`(教师，可能多个)
     *
     * GET，查询参数 `xnxqdm=<长码>`。
     * ⚠ **必须带 `Referer: https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action`**
     * （F# 版 `GDUT.ClassSchedule/Library.fs` 里专门写了 `// TODO: 重要！需要记录`）。
     *
     * 状态：2022 年逆向所得，**未在本次实测中验证是否仍然存活**。
     * 它的 `jxcdmcs` 只有整学期的教室列表、也没有 `sknrjj`，所以 `JxfwClient` 默认先试
     * [JXFW_SCHEDULE_DATA_LIST]，失败或为空时才回退到本接口。
     */
    public const val JXFW_SCHEDULE_ALL_KB_LIST: String =
        "https://jxfw.gdut.edu.cn/xsgrkbcx!xsAllKbList.action"

    /** 访问 [JXFW_SCHEDULE_ALL_KB_LIST] 必须带的 Referer。 */
    public const val JXFW_SCHEDULE_ALL_KB_REFERER: String =
        "https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action"

    /**
     * 课表接口 B：**按周炸开**，返回 JSON `{total, rows:[...]}`。
     *
     * POST `application/x-www-form-urlencoded`，参数：
     * `xnxqdm`(长码) `zc`(空) `page`(1-based) `rows`(每页条数) `sort=kxh` `order=asc`
     * ⚠ **必须带 `Referer: https://jxfw.gdut.edu.cn/`**。
     *
     * 字段：`kcmc` `jxcdmc` `teaxms` `xq` `zc`(单个周次) `jcdm`(两位拼接节次)
     *      `sknrjj` `pkrq`(具体上课日期) `jxbmc` `kcbh`
     *
     * 状态：2024 年旧 Java 后端在用，可信度高。
     */
    public const val JXFW_SCHEDULE_DATA_LIST: String =
        "https://jxfw.gdut.edu.cn/xsgrkbcx!getDataList.action"

    /**
     * 考试安排。POST，参数：
     * `xnxqdm`(长码) `page=1` `rows=200` `sort=zc,xq,jcdm2` `order=asc`
     *
     * 字段：`kcmc` `kcbh` `ksrq`(日期) `kssj`(时段，形如 `08:30--10:05`)
     *      `kscdmc`(场地) `xqmc`(**校区名**) `kslbmc`(类别) `ksaplxmc`(安排类型)
     *
     * `xqmc` 是本科生数据里**唯一**能拿到校区的地方，用于 `Campus` 探测。
     */
    public const val JXFW_EXAM_DATA_LIST: String =
        "https://jxfw.gdut.edu.cn/xsksap!getDataList.action"

    // ------------------------------------------------------------------ 班级课表（实测 2026-09-12）

    /**
     * 班级课表主接口。
     *
     * 实测（2026-09-12，bash 脚本 + curl）：
     * `GET /xsbjkbcx!getKbRq.action?xnxqdm=<长码>&bjdm=<班级代码>&zc=<周次>`
     * - `zc` 缺省 = 返回全学期（实测 324 行不分页）；超范围（如 20）不报错，返回校历外日期 + 空课表。
     * - Referer / X-Requested-With 均非必需（与个人课表 A 的特殊 Referer 要求不同）。
     * - 响应为 JSON 数组 `[课表rows, 周日期rows]`：
     *   `rows[0]` 每周每教学班一行，24 字段：`kcmc kcbh kcdm teaxms teadms jxbdm jxbmc xnxqdm
     *   zc(单周次) jcdm("0102"两位拼接) jcdm2("01,02"逗号) xq jxcdmc sknrjj xs zxs pkrs kxh
     *   flfzmc jxhjmc tkbz dgksdm kbdm`；
     *   `rows[1]` = `[{"xqmc":"1","rq":"2026-08-31"}, …]`，该周周一至周日的真实日期，
     *   周一的 `rq` 即该周开学日，可反推学期开学日期。
     * - ⚠ 参数只认 URL 查询串；响应头 Content-Type 不可信（`text/html` 包 JSON 体）。
     */
    public const val JXFW_CLASS_SCHEDULE_GET_KB_RQ: String = "xsbjkbcx!getKbRq.action"

    /**
     * 班级课表备接口（回退用）。
     *
     * 实测（2026-09-12）：`GET /xsbjkbcx!xsAllKbList.action?xnxqdm=<长码>&bjdm=<班级代码>`，
     * 返回 HTML 内嵌 `var kbxx = [...]`（用 `JsonExtractor` 括号配对扫描提取）。
     * 每行 9 字段：`kcmc kcbh jxbmc kcrwdm jcdm2 zcs("1,2,…,16"逗号) xq jxcdmcs teaxms` ——
     * 与个人课表 A 的字段表完全一致。
     * ⚠ 缺 `bjdm` 时**静默返回空数组，不报错**；参数只认 URL 查询串（POST body 传参会返回空）。
     */
    public const val JXFW_CLASS_SCHEDULE_ALL_KB_LIST: String = "xsbjkbcx!xsAllKbList.action"

    /**
     * 班级选择级联接口。
     *
     * 实测（2026-09-12）：`POST /xsbjkbcx!getFind.action`，
     * `Content-Type: application/x-www-form-urlencoded`，
     * body: `guid=<下级字段名>&xnxqdm=<长码>&xqdm=&rxnf=&xsyxdm=<学院>&zydm=<专业>`。
     * 按 `guid` 逐级下钻（`xsyxdm` → 专业；`zydm` → 班级，`dm` 即 `bjdm`），
     * 响应为 `text`，格式 `<guid>^getFind:<JSON数组>`，**需先 split 再 parseJSON**。
     */
    public const val JXFW_CLASS_SCHEDULE_FIND: String = "xsbjkbcx!getFind.action"

    /**
     * 班级课表查询主页面。
     *
     * 实测（2026-09-12）：`GET /xsbjkbcx!xsbjkbMain.action`，
     * 页面里服务端渲染了全部班级 `<option>`（value = bjdm），
     * 一次 GET 即可拿全班级列表，无需走 [JXFW_CLASS_SCHEDULE_FIND] 级联。
     */
    public const val JXFW_CLASS_SCHEDULE_MAIN: String = "xsbjkbcx!xsbjkbMain.action"

    /**
     * 单门课程上课信息明细（EasyUI `{total, rows}`，可选实现）。
     *
     * 实测（2026-09-12）：`POST /xsbjkbcx!getSkxxDataList.action`，
     * body: `kcrwdm=<课程任务代码>&bjdm=&page=1&rows=100`。
     * ⚠ **必须带 Referer `https://jxfw.gdut.edu.cn/xsbjkbcx!xsAllKbList.action`**，
     * 否则 total=0（类似个人课表 A 的专用 Referer，但值不同）。
     * 字段：`kxh zc xq jcdm2 kcmc sknrjj jxbmc jxcdmc jxhjmc teaxms`。
     */
    public const val JXFW_CLASS_SCHEDULE_DETAIL: String = "xsbjkbcx!getSkxxDataList.action"

    /** 访问 [JXFW_CLASS_SCHEDULE_DETAIL] 必须带的 Referer。 */
    public const val JXFW_CLASS_SCHEDULE_DETAIL_REFERER: String =
        "$JXFW_BASE/xsbjkbcx!xsAllKbList.action"

    /**
     * 成绩。POST，参数：
     * `xnxqdm`(空=全部学期，或具体长码) `jhlxdm`(空) `sort=xnxqdm` `order=asc` `page` `rows`
     *
     * 字段：`kcmc` `xnxqmc`(学期中文名) `xnxqdm` `zcj`(总成绩) `cjjd`(绩点) `xf`(学分)
     *      `kcdlmc`(课程大类) `kcflmc`(课程分类) `xdfsmc`(修读方式)
     */
    public const val JXFW_SCORE_DATA_LIST: String =
        "https://jxfw.gdut.edu.cn/xskccjxx!getDataList.action"

    /** 访问 jxfw 所有 `!getDataList.action` 接口时应带的 Referer。 */
    public const val JXFW_DEFAULT_REFERER: String = "https://jxfw.gdut.edu.cn/"

    // ------------------------------------------------------------------ 研究生（未实现，仅记录）

    /**
     * 研究生统一认证 service 参数。
     *
     * 当前版本**不实现研究生**。保留常量是为了：
     * 1. 登录成功后能识别出"这是研究生账号"并给出明确提示（[com.gdutday.core.model.GdutException.UnsupportedUserType]）；
     * 2. 将来要做时不必重新逆向。
     *
     * 研究生体系的额外复杂度（详见 docs/01-gdut-protocol.md）：
     * - 每个子应用要**单独 POST 授权一次**（`wdcjapp` 成绩、`wdkbapp` 课表）
     * - 课表返回的是**具体时刻** `KSSJ`/`JSSJ`（如 1630/1715）而非节次，需要时刻→节次映射表
     * - `ZCBH` 是 21 位的 0/1 串，`ZCMC` 是 `"1-16周"`/`"1-11单周"`/`"2-12双周"` 这类文本
     * - 字段名全大写：`KCMC` `JASMC` `JSXM` `XQ` `BJMC`
     * - 需要做**连堂课合并**（相邻节次同一门课合成一条）
     */
    public const val YJS_SERVICE_PARAM: String =
        "https://yjsxt.gdut.edu.cn/gsapp/sys/yjsemaphome/portal/index.do"

    public const val YJS_HOST: String = "yjsxt.gdut.edu.cn"
}
