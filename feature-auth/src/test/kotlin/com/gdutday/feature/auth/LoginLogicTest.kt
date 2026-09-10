package com.gdutday.feature.auth

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.UserType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 登录纯逻辑测试。
 *
 * 重点钉住两件事：
 * 1. **学号边界**：多一位、少一位、首位不是 3 都不能让按钮亮起来 ——
 *    每一次无效请求都会增加学校侧的风控计数。
 * 2. **异常映射**：滑块风控必须映射到 [LoginErrorKind.CAPTCHA_REQUIRED]，
 *    UI 依赖它决定"自动切到教务系统"这条逃生通道。
 */
class LoginLogicTest {

    // ------------------------------------------------------------ 学号清洗/校验

    @Test
    fun `清洗只保留数字并截断到 10 位`() {
        assertThat(LoginLogic.sanitizeStudentId("31a20 00123 456")).isEqualTo("3120001234")
        assertThat(LoginLogic.sanitizeStudentId("abcdef")).isEmpty()
        // 超过 10 位截断，不会把 11 位的输入带进校验
        assertThat(LoginLogic.sanitizeStudentId("31200012345")).isEqualTo("3120001234")
    }

    @Test
    fun `学号边界判定`() {
        assertThat(LoginLogic.validateStudentId("")).isEqualTo(StudentIdStatus.EMPTY)
        assertThat(LoginLogic.validateStudentId("312000123")).isEqualTo(StudentIdStatus.TOO_SHORT)
        assertThat(LoginLogic.validateStudentId("31200012345")).isEqualTo(StudentIdStatus.TOO_LONG)
        assertThat(LoginLogic.validateStudentId("312000123a")).isEqualTo(StudentIdStatus.NOT_DIGITS)
        // 10 位纯数字但首位不是 3：研究生/教师或输错
        assertThat(LoginLogic.validateStudentId("2120001234")).isEqualTo(StudentIdStatus.NOT_UNDERGRADUATE)
        assertThat(LoginLogic.validateStudentId("0120001234")).isEqualTo(StudentIdStatus.NOT_UNDERGRADUATE)
        assertThat(LoginLogic.validateStudentId("3120001234")).isEqualTo(StudentIdStatus.VALID)
    }

    @Test
    fun `登录按钮可点条件`() {
        val id = "3120001234"
        // 统一认证：学号合法 + 密码非空即可
        assertThat(LoginLogic.canSubmit(id, "pwd", LoginMethodTab.UNIFIED_AUTH, "", null, false)).isTrue()
        // 学号不合法、密码为空、busy 都禁用
        assertThat(LoginLogic.canSubmit("2120001234", "pwd", LoginMethodTab.UNIFIED_AUTH, "", null, false)).isFalse()
        assertThat(LoginLogic.canSubmit(id, "", LoginMethodTab.UNIFIED_AUTH, "", null, false)).isFalse()
        assertThat(LoginLogic.canSubmit(id, "pwd", LoginMethodTab.UNIFIED_AUTH, "", null, true)).isFalse()
        // 教务系统直登：验证码与 token 都必需
        assertThat(LoginLogic.canSubmit(id, "pwd", LoginMethodTab.JXFW_DIRECT, "", null, false)).isFalse()
        assertThat(LoginLogic.canSubmit(id, "pwd", LoginMethodTab.JXFW_DIRECT, "1234", "", false)).isFalse()
        assertThat(LoginLogic.canSubmit(id, "pwd", LoginMethodTab.JXFW_DIRECT, "1234", "tok", false)).isTrue()
    }

    // ------------------------------------------------------------ 异常映射

    @Test
    fun `滑块风控映射为 CaptchaRequired`() {
        val info = LoginLogic.mapLoginError(GdutException.CaptchaRequired("isNeed=true"))
        assertThat(info.kind).isEqualTo(LoginErrorKind.CAPTCHA_REQUIRED)
    }

    @Test
    fun `密码错误沿用服务端原文`() {
        val info = LoginLogic.mapLoginError(GdutException.BadCredentials("密码错误次数过多，账号已锁定"))
        assertThat(info.kind).isEqualTo(LoginErrorKind.BAD_CREDENTIALS)
        assertThat(info.message).contains("密码错误次数过多")
    }

    @Test
    fun `密码错误没有服务端原文时回退默认文案`() {
        val info = LoginLogic.mapLoginError(GdutException.BadCredentials())
        assertThat(info.kind).isEqualTo(LoginErrorKind.BAD_CREDENTIALS)
        assertThat(info.message).isEqualTo("学号或密码错误")
    }

    @Test
    fun `研究生与教师分别映射`() {
        assertThat(LoginLogic.mapLoginError(GdutException.UnsupportedUserType(UserType.GRADUATE)).kind)
            .isEqualTo(LoginErrorKind.UNSUPPORTED_GRADUATE)
        assertThat(LoginLogic.mapLoginError(GdutException.UnsupportedUserType(UserType.TEACHER)).kind)
            .isEqualTo(LoginErrorKind.UNSUPPORTED_TEACHER)
        assertThat(LoginLogic.mapLoginError(GdutException.UnsupportedUserType(UserType.UNKNOWN)).kind)
            .isEqualTo(LoginErrorKind.UNSUPPORTED_UNKNOWN)
    }

    @Test
    fun `验证码错误映射为 BadCaptcha`() {
        val info = LoginLogic.mapLoginError(GdutException.BadCaptcha("验证码已过期"))
        assertThat(info.kind).isEqualTo(LoginErrorKind.BAD_CAPTCHA)
        assertThat(info.message).isEqualTo("验证码已过期")
    }

    @Test
    fun `非 Gdut 异常兜底为 Other`() {
        val info = LoginLogic.mapLoginError(IllegalStateException("boom"))
        assertThat(info.kind).isEqualTo(LoginErrorKind.OTHER)
        assertThat(info.message).isEqualTo("boom")
    }
}
