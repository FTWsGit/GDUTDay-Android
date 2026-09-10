package com.gdutday.feature.auth

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.UserType

/**
 * 登录页的**纯逻辑**。
 *
 * 这些规则（学号边界、按钮可点条件、异常到用户可读结果的映射）都直接决定
 * "用户会不会把注定失败的请求打到学校服务器上"。服务器侧的每一次失败登录
 * 都在累积风控计数（见 [GdutException.CaptchaRequired]），所以这些判断值得
 * 单独抽出来用 JVM 测试钉死，而不是埋在 Composable 的 `when` 里。
 *
 * 这里刻意不依赖任何 Android / Compose 类型，因此 `LoginLogicTest` 不需要 Robolectric。
 */

/** 两条登录路径。UI 用 Tab 切换，默认 [UNIFIED_AUTH]。 */
public enum class LoginMethodTab {
    /** 统一身份认证，默认路径。 */
    UNIFIED_AUTH,

    /** 教务系统直登，统一认证触发滑块时的逃生通道。 */
    JXFW_DIRECT,
}

/** 学号输入状态的分类结果。 */
public enum class StudentIdStatus {
    EMPTY,
    TOO_SHORT,
    TOO_LONG,
    NOT_DIGITS,
    NOT_UNDERGRADUATE,
    VALID,
}

/** 登录失败的语义分类。UI 用它挑对应的 strings.xml 文案。 */
public enum class LoginErrorKind {
    BAD_CREDENTIALS,

    /** 触发滑块风控。UI 必须**自动切到教务系统直登**，而不是让用户重试。 */
    CAPTCHA_REQUIRED,
    UNSUPPORTED_GRADUATE,
    UNSUPPORTED_TEACHER,
    UNSUPPORTED_UNKNOWN,
    BAD_CAPTCHA,
    NETWORK,
    SESSION_EXPIRED,
    OTHER,
}

/**
 * 一次登录失败的完整信息。
 *
 * @property message 默认展示文本，通常直接来自 `GdutException.userMessage`。
 *   只有 [LoginErrorKind.CAPTCHA_REQUIRED] / `UNSUPPORTED_*` 会被 UI 换成
 *   strings.xml 里的专用文案。
 */
public data class LoginErrorInfo(
    public val kind: LoginErrorKind,
    public val message: String,
)

/**
 * 登录页纯函数集合。
 */
public object LoginLogic {

    /** 本科生学号固定 10 位，与 [StudentProfile.UNDERGRADUATE_ID_LENGTH] 同源。 */
    public const val STUDENT_ID_LENGTH: Int = StudentProfile.UNDERGRADUATE_ID_LENGTH

    /**
     * 清洗学号输入：只保留数字并截断到 10 位。
     *
     * 在 `onValueChange` 里调用，保证用户永远不可能输入字母或超长学号 ——
     * 这比提交时再报错体验好，也省掉一次无谓的服务端请求。
     */
    public fun sanitizeStudentId(raw: String): String =
        raw.filter { it.isDigit() }.take(STUDENT_ID_LENGTH)

    /** 判定学号是否可提交。 */
    public fun validateStudentId(raw: String): StudentIdStatus {
        val s = raw.trim()
        return when {
            s.isEmpty() -> StudentIdStatus.EMPTY
            !s.all { it.isDigit() } -> StudentIdStatus.NOT_DIGITS
            s.length < STUDENT_ID_LENGTH -> StudentIdStatus.TOO_SHORT
            s.length > STUDENT_ID_LENGTH -> StudentIdStatus.TOO_LONG
            StudentProfile.looksLikeUndergraduateId(s) -> StudentIdStatus.VALID
            // 10 位纯数字但首位不是 3：很可能是研究生/教师学号，或用户输错了。
            else -> StudentIdStatus.NOT_UNDERGRADUATE
        }
    }

    /**
     * 登录按钮是否可点。
     *
     * 教务系统直登路径额外要求验证码与 token 都就绪：缺少 token 时提交必然
     * 返回"验证码不正确"，不如直接禁用。
     */
    public fun canSubmit(
        studentId: String,
        password: String,
        method: LoginMethodTab,
        captcha: String,
        captchaToken: String?,
        busy: Boolean,
    ): Boolean {
        if (busy) return false
        if (validateStudentId(studentId) != StudentIdStatus.VALID) return false
        if (password.isEmpty()) return false
        if (method == LoginMethodTab.JXFW_DIRECT) {
            if (captcha.isBlank()) return false
            if (captchaToken.isNullOrBlank()) return false
        }
        return true
    }

    /**
     * 把任意异常映射成 UI 可用的 [LoginErrorInfo]。
     *
     * 注意 [GdutException.BadCredentials] 直接沿用 `userMessage` —— 它可能包含
     * 服务端原文（如"密码错误次数过多，账号已锁定"），比笼统的"登录失败"有用得多。
     */
    public fun mapLoginError(error: Throwable): LoginErrorInfo = when (error) {
        is GdutException.CaptchaRequired ->
            LoginErrorInfo(LoginErrorKind.CAPTCHA_REQUIRED, error.userMessage)

        is GdutException.UnsupportedUserType -> when (error.userType) {
            UserType.GRADUATE ->
                LoginErrorInfo(LoginErrorKind.UNSUPPORTED_GRADUATE, error.userMessage)
            UserType.TEACHER ->
                LoginErrorInfo(LoginErrorKind.UNSUPPORTED_TEACHER, error.userMessage)
            else ->
                LoginErrorInfo(LoginErrorKind.UNSUPPORTED_UNKNOWN, error.userMessage)
        }

        is GdutException.BadCredentials ->
            LoginErrorInfo(LoginErrorKind.BAD_CREDENTIALS, error.userMessage)

        is GdutException.BadCaptcha ->
            LoginErrorInfo(LoginErrorKind.BAD_CAPTCHA, error.userMessage)

        is GdutException.Network ->
            LoginErrorInfo(LoginErrorKind.NETWORK, error.userMessage)

        is GdutException.SessionExpired ->
            LoginErrorInfo(LoginErrorKind.SESSION_EXPIRED, error.userMessage)

        is GdutException ->
            LoginErrorInfo(LoginErrorKind.OTHER, error.userMessage)

        else ->
            LoginErrorInfo(LoginErrorKind.OTHER, error.message ?: "登录失败，请稍后重试")
    }
}
