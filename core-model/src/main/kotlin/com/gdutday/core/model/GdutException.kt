package com.gdutday.core.model

/**
 * 与广工各系统交互时的全部失败模式。
 *
 * 设计原则：
 * 1. **UI 层只认这个类型**，不把 OkHttp/jsoup 的异常泄漏到 ViewModel 之上。
 * 2. 每个子类都带一个**面向用户的中文短句** [userMessage]，UI 可以直接展示，
 *    不需要再写一遍 when 映射。
 * 3. 可恢复性显式标注（[recoverable] / [shouldRetryLogin]），
 *    让 Repository 能自动决定"重登一次再试"还是"直接把错误抛给用户"。
 *
 * 用法：
 * ```kotlin
 * try {
 *     val courses = client.fetchSchedule(session, term)
 * } catch (e: GdutException.SessionExpired) {
 *     sessionStore.clear(); navigator.toLogin()
 * } catch (e: GdutException) {
 *     uiState.error = e.userMessage
 * }
 * ```
 */
public sealed class GdutException(
    /** 面向用户的一句话说明，可直接展示。 */
    public val userMessage: String,
    /** 用于排查的技术细节，只在日志/关于页的"诊断信息"里展示。 */
    public val detail: String? = null,
    cause: Throwable? = null,
) : Exception(buildMessage(userMessage, detail), cause) {

    /** 是否值得自动重试（网络抖动类）。 */
    public open val recoverable: Boolean get() = false

    /** 是否应当清掉会话并引导用户重新登录。 */
    public open val shouldRetryLogin: Boolean get() = false

    // ---------------------------------------------------------------- 网络层

    /** 连不上服务器：DNS 失败、超时、TLS 握手失败、被校园网拦截等。 */
    public class Network(
        detail: String? = null,
        cause: Throwable? = null,
    ) : GdutException("网络连接失败，请检查网络后重试", detail, cause) {
        override val recoverable: Boolean get() = true
    }

    /** 学校服务器返回了非预期状态码。 */
    public class Http(
        public val statusCode: Int,
        public val url: String,
        detail: String? = null,
    ) : GdutException(
        userMessage = when (statusCode) {
            in 500..599 -> "教务系统繁忙（$statusCode），请稍后再试"
            404 -> "接口不存在，可能学校改版了（$statusCode）"
            405 -> "请求方法不被允许（405）"
            else -> "服务器返回异常状态码 $statusCode"
        },
        detail = detail ?: url,
    ) {
        override val recoverable: Boolean get() = statusCode in 500..599
    }

    // ---------------------------------------------------------------- 认证层

    /** 学号或密码错误。 */
    public class BadCredentials(
        /** 服务端返回的原文错误提示，可能包含"密码错误次数过多"之类的信息。 */
        public val serverMessage: String? = null,
        detail: String? = null,
    ) : GdutException(serverMessage?.takeIf { it.isNotBlank() } ?: "学号或密码错误", detail)

    /**
     * Cookie / 会话已失效，需要重新登录。
     *
     * 触发条件（实测）：
     * - 带着旧 cookie 请求 `jxfw.gdut.edu.cn/` 时被 302 回 `authserver/login`
     * - 请求课表接口返回的不是 JSON 而是登录页 HTML
     */
    public class SessionExpired(
        detail: String? = null,
    ) : GdutException("登录状态已过期，请重新登录", detail) {
        override val shouldRetryLogin: Boolean get() = true
    }

    /**
     * 统一认证要求人机验证（**滑块**）。
     *
     * 触发条件：`GET /authserver/checkNeedCaptcha.htl?username=<学号>` 返回 `{"isNeed":true}`。
     *
     * ## 实测到的完整机制（2026-09-10）
     *
     * 登录页内联变量 `captchaSwitch = "2"`，`login.js` 的提交逻辑是：
     * ```js
     * if (checkForm()) {
     *     var cllt = $("#cllt").val();
     *     if (needCaptcha && captchaSwitch == "2" && cllt == "userNameLogin") {
     *         createSliderCaptcha();          // ← 滑块
     *     } else {
     *         $(".loginFromClass").submit();  // ← 直接提交
     *     }
     * }
     * function createSliderCaptcha() {
     *     $.ajax({ url: contextPath + "/common/toSliderCaptcha.htl", type: "get",
     *              success: function (html) { $("#sliderCaptchaDiv").html(html) } });
     * }
     * ```
     * 即 `captchaSwitch` 的语义是**验证码形态**而不是开关：
     * - `"1"` → 图形验证码，`reloadCaptcha()` 会把 `#captchaImg` 指向 `/authserver/getCaptcha.htl`
     * - `"2"` → 滑块验证码，从 `/authserver/common/toSliderCaptcha.htl` 拉一段 HTML 片段塞进页面
     *
     * `needCaptcha` 初值是空串（假值），由 `checkNeedCaptcha.htl` 的返回决定。
     * 实测用一个正常学号请求返回 `{"isNeed":false}`，所以**正常情况下不会走到滑块**。
     * 会在这些情况下变成 true：连续密码错误、异地/机房 IP、风控策略调整。
     *
     * ## App 侧的正确处置
     *
     * 滑块是第三方行为验证，**自动化既不可行也不应该做**。所以：
     * 1. **绝不自动重试** —— 重试只会让风控计数继续涨；
     * 2. 提示用户改用**教务系统直登**（`LoginMethod.JXFW_DIRECT`），
     *    那条路用的是 `jxfw.gdut.edu.cn/yzm` 的图形验证码，用户看一眼就能填；
     * 3. 或者让用户先在浏览器里正常登录一次教务系统，把风控计数清掉，再回 App 重试。
     */
    public class CaptchaRequired(
        /** `checkNeedCaptcha.htl` 的原始响应，便于诊断。 */
        detail: String? = null,
    ) : GdutException("账号触发了滑块验证，请改用「教务系统登录」或先在浏览器登录一次教务系统", detail)

    /**
     * 重定向次数超出上限。
     *
     * 旧 Java 后端的注释写着"最后会无限重定向，因为 nginx 一直永远返回 302"，
     * 并把上限硬编码成 3 次（`if (maxTime > 3) break`）。
     * 本项目上限 [maxHops] 可配，超限时抛这个异常并附上完整的跳转链，便于诊断。
     */
    public class TooManyRedirects(
        public val chain: List<String>,
        public val maxHops: Int,
    ) : GdutException(
        "登录跳转次数过多（>$maxHops），可能是学校接口变更",
        chain.joinToString(" -> "),
    )

    /** 登录流程拿到了响应，但既不是成功跳转也不是可识别的错误页。 */
    public class UnexpectedLoginResult(
        detail: String? = null,
    ) : GdutException("登录结果无法识别，请稍后重试或改用教务系统登录", detail)

    /** 图形验证码错误（教务系统直登路径）。 */
    public class BadCaptcha(
        public val serverMessage: String? = null,
    ) : GdutException(serverMessage?.takeIf { it.isNotBlank() } ?: "验证码不正确")

    // ---------------------------------------------------------------- 数据层

    /**
     * 响应结构与预期不符 —— 几乎总是意味着**学校改版了**。
     *
     * 这是最需要被清楚上报的错误：它会带上原始响应片段 [snippet]，
     * 便于用户在 GitHub 提 issue 时直接贴出诊断信息。
     */
    public class Parse(
        public val what: String,
        public val snippet: String? = null,
        cause: Throwable? = null,
    ) : GdutException(
        "解析${what}失败，学校接口可能已改版",
        snippet?.take(500),
        cause,
    )

    /** 登录成功但课表为空。可能是大一新生还没排课，也可能是选错学期。 */
    public class EmptySchedule(
        public val term: Term? = null,
    ) : GdutException(
        term?.let { "${it.displayName} 课表为空" } ?: "课表为空",
    )

    /** 身份不受支持（研究生 / 教师）。 */
    public class UnsupportedUserType(
        public val userType: UserType,
    ) : GdutException(
        when (userType) {
            UserType.GRADUATE -> "当前版本暂不支持研究生课表"
            UserType.TEACHER -> "当前版本暂不支持教师课表"
            else -> "无法识别的账号类型"
        },
    )

    /** 本地数据不可用（数据库损坏、学期未初始化等）。 */
    public class Local(
        message: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : GdutException(message, detail, cause)

    public companion object {
        private fun buildMessage(userMessage: String, detail: String?): String =
            if (detail.isNullOrBlank()) userMessage else "$userMessage  [detail: $detail]"
    }
}
