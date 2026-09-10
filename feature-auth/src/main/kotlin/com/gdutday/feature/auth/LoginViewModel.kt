package com.gdutday.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gdutday.data.repository.AppContainer
import com.gdutday.data.repository.AuthRepository
import com.gdutday.data.repository.JxfwCaptcha
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录页的交互状态。
 *
 * @property busy 登录/取验证码进行中。登录要 2~8 秒，没有进度态用户会狂点，
 *   而重复登录请求正是触发风控滑块的常见原因。
 * @property captchaToken 必须与 [captchaImage] 同批次下发，提交时原样带回，
 *   否则教务系统永远回"验证码不正确"。
 * @property rememberPassword 默认 false。开启后展开风险说明（见 CredentialStore 注释）。
 */
public data class LoginUiState(
    public val method: LoginMethodTab = LoginMethodTab.UNIFIED_AUTH,
    public val studentId: String = "",
    public val password: String = "",
    public val rememberPassword: Boolean = false,
    public val captcha: String = "",
    public val captchaImage: JxfwCaptcha? = null,
    public val captchaLoading: Boolean = false,
    public val busy: Boolean = false,
    public val errorKind: LoginErrorKind? = null,
    public val errorMessage: String? = null,
) {
    /** 学号校验结果，Composable 用来决定是否显示内联提示。 */
    public val studentIdStatus: StudentIdStatus get() = LoginLogic.validateStudentId(studentId)

    /** 登录按钮是否可点。 */
    public val canSubmit: Boolean
        get() = LoginLogic.canSubmit(
            studentId = studentId,
            password = password,
            method = method,
            captcha = captcha,
            captchaToken = captchaImage?.token,
            busy = busy,
        )
}

/**
 * 登录页 ViewModel。
 *
 * ## 线程
 *
 * 所有网络调用只是 `viewModelScope.launch`，**不额外 withContext(IO)** ——
 * [AuthRepository] 的每个 suspend 方法内部已经 `withContext(Dispatchers.IO)`。
 * 多切一层除了增加一次线程调度没有任何收益，反而让"到底在哪个线程"更难读。
 *
 * ## 安全
 *
 * 这个类里**没有任何日志调用**，密码只作为局部变量短暂存在于内存中，
 * 提交给 Repository 后不再被读取。
 */
public class LoginViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    public val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** 登录成功的一次性信号。UI 用 LaunchedEffect 收集后调用 onLoggedIn。 */
    private val _loggedIn = MutableStateFlow(false)
    public val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    public fun onStudentIdChange(raw: String) {
        _uiState.update { it.copy(studentId = LoginLogic.sanitizeStudentId(raw), errorKind = null, errorMessage = null) }
    }

    public fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorKind = null, errorMessage = null) }
    }

    public fun onRememberPasswordChange(checked: Boolean) {
        _uiState.update { it.copy(rememberPassword = checked) }
    }

    public fun onCaptchaChange(value: String) {
        _uiState.update { it.copy(captcha = value.filter { c -> !c.isWhitespace() }.take(8)) }
    }

    /** 手选登录路径。切到教务系统时若还没有验证码就自动取一张。 */
    public fun onMethodChange(method: LoginMethodTab) {
        val needCaptcha = method == LoginMethodTab.JXFW_DIRECT && _uiState.value.captchaImage == null
        _uiState.update { it.copy(method = method, errorKind = null, errorMessage = null) }
        if (needCaptcha) refreshCaptcha()
    }

    /** 点击验证码图片刷新。 */
    public fun refreshCaptcha() {
        if (_uiState.value.captchaLoading) return
        _uiState.update { it.copy(captchaLoading = true, captchaImage = null, captcha = "") }
        viewModelScope.launch {
            try {
                val captcha = authRepository.fetchJxfwCaptcha()
                _uiState.update { it.copy(captchaLoading = false, captchaImage = captcha) }
            } catch (e: Throwable) {
                val info = LoginLogic.mapLoginError(e)
                _uiState.update {
                    it.copy(captchaLoading = false, errorKind = info.kind, errorMessage = info.message)
                }
            }
        }
    }

    public fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(busy = true, errorKind = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (state.method == LoginMethodTab.UNIFIED_AUTH) {
                    authRepository.login(
                        studentId = state.studentId,
                        password = state.password,
                        rememberPassword = state.rememberPassword,
                    )
                } else {
                    authRepository.loginViaJxfw(
                        studentId = state.studentId,
                        password = state.password,
                        captcha = state.captcha.trim(),
                        // token 原样回传，不能二次加工。
                        captchaToken = state.captchaImage!!.token,
                        rememberPassword = state.rememberPassword,
                    )
                }
                _uiState.update { it.copy(busy = false) }
                _loggedIn.value = true
            } catch (e: Throwable) {
                val info = LoginLogic.mapLoginError(e)
                // 滑块风控是设计好的逃生通道：不提供"重试"，直接切到教务系统直登。
                val switchToJxfw = info.kind == LoginErrorKind.CAPTCHA_REQUIRED
                _uiState.update {
                    it.copy(
                        busy = false,
                        errorKind = info.kind,
                        errorMessage = info.message,
                        method = if (switchToJxfw) LoginMethodTab.JXFW_DIRECT else it.method,
                    )
                }
                if (switchToJxfw) refreshCaptcha()
            }
        }
    }

    public companion object {
        /** 手写工厂，从 [AppContainer] 取依赖（本项目不用 Hilt）。 */
        public fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(container.authRepository) }
        }
    }
}
