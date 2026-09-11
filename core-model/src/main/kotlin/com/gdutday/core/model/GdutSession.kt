package com.gdutday.core.model

/**
 * 登录路径。两条路的可用性与前置条件完全不同，UI 上要做成两个入口。
 */
public enum class LoginMethod(
    public val displayName: String,
    public val description: String,
) {
    /**
     * 统一身份认证（authserver / CAS）。
     *
     * 优点：一次登录同时打通教务、ehall、图书馆等所有子系统；支持 `rememberMe`，会话更持久。
     * 缺点：触发风控时会要求**滑块验证**，App 无法自动通过
     * （见 [GdutException.CaptchaRequired]）。
     *
     * 这是**默认路径**。
     */
    UNIFIED_AUTH(
        displayName = "统一身份认证",
        description = "用校园统一账号登录，初始密码为身份证后六位",
    ),

    /**
     * 教务系统直登（jxfw `/new/login` + 图形验证码）。
     *
     * 优点：验证码是 `jxfw.gdut.edu.cn/yzm` 下发的 140×60 JPEG，用户看一眼就能填，
     * **不存在无法自动化的滑块**；流程只有两个请求，比 SSO 链路短得多。
     * 缺点：只拿到教务系统的会话，进不了 ehall / 研究生系统；
     * 密码是否加密未经充分验证（旧 Java 后端按明文 POST，本项目沿用并标注为待验证）。
     *
     * 作为统一认证被风控时的**逃生通道**。旧小程序的 `login-edu.vue` 走的就是这条路，
     * 页面上写着"20级点这里:使用教务系统登录"。
     */
    JXFW_DIRECT(
        displayName = "教务系统登录",
        description = "需要输入图形验证码，适用于统一认证触发滑块验证时",
    ),
}

/**
 * 一次成功登录的产物。
 *
 * 这是**唯一需要持久化**的登录态：cookie 在手，后续所有接口都不必重新认证。
 * 由 `core-datastore` 加密存储（cookie 等同于账号密码，泄露即等于账号被盗）。
 *
 * 放在 `core-model` 是分层要求：`core-datastore`（持久化层）与 `core-network`
 * 都要引用它，而 core 必须是叶子 —— 不能反向依赖协议层 `data-gdut`。
 *
 * @property cookies 全部 cookie，**必须包含 authserver 与 jxfw 两个域的 JSESSIONID**。
 *   authserver 的是 TGT（票据授权票）载体，jxfw 的是业务会话；
 *   少了前者会在会话续期时失败，少了后者所有教务接口都会 302 回登录页。
 * @property profile 学号与身份。
 * @property method 走的哪条登录路径，UI 上"当前登录方式"要显示它。
 * @property obtainedAtMillis 登录完成时刻（epoch millis）。
 *   用于"登录态已保持 N 天"的展示，以及判断是否该主动续期。
 * @property diagnostics 登录过程的可读摘要（跳转链、命中的接口）。
 *   **只在"关于 → 诊断信息"里展示**，不含任何密码或完整 cookie 值。
 * @property hosts 这个会话是在哪套地址上建立的。
 *
 *   会话与主机是绑定的：cookie 的 domain、[hasJxfwSession] 的判断都依赖它。
 *   把它存进会话而不是每次去读全局配置，有两个好处：
 *   1. 测试里用 MockWebServer 建的会话能被正确判定（否则 [hasJxfwSession]
 *      会拿生产域名去比对 `localhost` 的 cookie，永远是 false）；
 *   2. 万一将来学校换域名，老会话仍然知道自己是从哪儿来的，
 *      不会因为"当前配置的域名"变了而被误判为失效。
 */
public data class GdutSession(
    public val cookies: List<StoredCookie>,
    public val profile: StudentProfile,
    public val method: LoginMethod,
    public val obtainedAtMillis: Long = System.currentTimeMillis(),
    public val diagnostics: String = "",
    public val hosts: GdutHosts = GdutHosts.PRODUCTION,
) {
    /** 是否持有 jxfw 的会话 cookie。所有课表/成绩/考试接口都依赖它。 */
    public val hasJxfwSession: Boolean get() = hasSessionCookieFor(hosts.jxfwHost)

    /** 是否持有统一认证的会话 cookie。用于会话续期与跨子系统访问。 */
    public val hasAuthServerSession: Boolean get() = hasSessionCookieFor(hosts.authserverHost)

    /** 是否持有指定主机的 JSESSIONID。 */
    public fun hasSessionCookieFor(host: String): Boolean = cookies.any {
        it.name.equals("JSESSIONID", ignoreCase = true) &&
            host.endsWith(it.domain.removePrefix("."), ignoreCase = true)
    }

    public val studentId: String get() = profile.studentId
    public val userType: UserType get() = profile.userType

    /** 会话已保持的天数。 */
    public fun ageInDays(nowMillis: Long = System.currentTimeMillis()): Long =
        ((nowMillis - obtainedAtMillis) / 86_400_000L).coerceAtLeast(0)

    /** 脱敏后的摘要，可安全写日志。 */
    public fun toSafeString(): String = buildString {
        // 学号是 PII，必须打码：诊断信息会被用户一键复制到公开 issue。
        append("GdutSession(studentId=").append(studentId.maskStudentId())
        append(", userType=").append(userType.name)
        append(", method=").append(method.name)
        append(", cookies=").append(cookies.size)
        append(", jxfw=").append(hasJxfwSession)
        append(", authserver=").append(hasAuthServerSession)
        append(", ageDays=").append(ageInDays())
        append(')')
    }
}

/** 学号脱敏：保留前 3 后 3，中间打码（`3120001234` → `312****234`）。 */
private fun String.maskStudentId(keepHead: Int = 3, keepTail: Int = 3): String =
    if (length <= keepHead + keepTail) {
        "*".repeat(length)
    } else {
        take(keepHead) + "*".repeat(length - keepHead - keepTail) + takeLast(keepTail)
    }
