package com.gdutday.feature.auth

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdutday.data.repository.AppContainer

/**
 * 登录页。
 *
 * ## ⚠ 这是一个契约文件
 *
 * [LoginScreen] 的签名被 `app` 模块的 NavHost 直接调用，改签名会连带编译失败。
 *
 * ## KDoc 七条要求的落点
 *
 * 1. **两条路径**：顶部 [FilterChip] 切换 [LoginMethodTab]，默认统一身份认证；
 *    教务系统直登展示 140×60 验证码并可点击刷新（[CaptchaImage]）。
 * 2. **滑块降级**：[LoginViewModel.submit] 捕获 `CaptchaRequired` 后把 method
 *    切到教务系统直登并自动取验证码，本页只负责把 [LoginErrorKind.CAPTCHA_REQUIRED]
 *    映射成引导文案，**不显示重试按钮**（按钮文案仍是"登录"，但路径已切换）。
 * 3. **学号前置校验**：[LoginUiState.canSubmit] 里调用 `looksLikeUndergraduateId`；
 *    不合法时按钮禁用并在输入框下方给内联提示，避免把注定失败的请求打到学校服务器。
 * 4. **研究生/教师**：按 [LoginErrorKind] 分别给 `login_unsupported_graduate/teacher`。
 * 5. **记住密码默认关闭**：Checkbox 初值 false，勾选后才展开风险说明。
 * 6. **进度态**：[LoginUiState.busy] 时按钮禁用并显示进度圈。
 * 7. **密码安全**：`PasswordVisualTransformation`；本文件与 ViewModel 均无任何日志调用。
 *
 * @param onLoggedIn 登录成功后的回调。由 NavHost 决定跳到课表页。
 */
@Composable
fun LoginScreen(
    container: AppContainer,
    onLoggedIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: LoginViewModel = viewModel(factory = LoginViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()

    LaunchedEffect(loggedIn) {
        if (loggedIn) onLoggedIn()
    }

    val errorText = when (state.errorKind) {
        LoginErrorKind.CAPTCHA_REQUIRED -> stringResource(R.string.login_captcha_required)
        LoginErrorKind.UNSUPPORTED_GRADUATE -> stringResource(R.string.login_unsupported_graduate)
        LoginErrorKind.UNSUPPORTED_TEACHER -> stringResource(R.string.login_unsupported_teacher)
        else -> state.errorMessage
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)

            // 两条登录路径的切换入口。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.method == LoginMethodTab.UNIFIED_AUTH,
                    onClick = { viewModel.onMethodChange(LoginMethodTab.UNIFIED_AUTH) },
                    label = { Text(stringResource(R.string.login_tab_unified)) },
                )
                FilterChip(
                    selected = state.method == LoginMethodTab.JXFW_DIRECT,
                    onClick = { viewModel.onMethodChange(LoginMethodTab.JXFW_DIRECT) },
                    label = { Text(stringResource(R.string.login_tab_jxfw)) },
                )
            }

            OutlinedTextField(
                value = state.studentId,
                onValueChange = viewModel::onStudentIdChange,
                label = { Text(stringResource(R.string.login_hint_student_id)) },
                singleLine = true,
                isError = state.studentIdStatus != StudentIdStatus.VALID &&
                    state.studentIdStatus != StudentIdStatus.EMPTY,
                supportingText = {
                    if (state.studentIdStatus != StudentIdStatus.VALID &&
                        state.studentIdStatus != StudentIdStatus.EMPTY
                    ) {
                        Text(stringResource(R.string.login_student_id_invalid))
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(stringResource(R.string.login_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(R.string.login_hint_password)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.method == LoginMethodTab.JXFW_DIRECT) {
                CaptchaImage(
                    state = state,
                    onRefresh = viewModel::refreshCaptcha,
                    onCaptchaChange = viewModel::onCaptchaChange,
                )
            }

            // 记住密码：默认关闭，勾选后才展开风险说明。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.rememberPassword,
                    onCheckedChange = viewModel::onRememberPasswordChange,
                )
                Text(
                    text = stringResource(R.string.login_remember_password),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.rememberPassword) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.login_remember_password_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            errorText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.login_action_busy))
                } else {
                    Text(stringResource(R.string.login_action))
                }
            }
        }
    }
}

/**
 * 教务系统的图形验证码（140×60 JPEG）。
 *
 * 用 [BitmapFactory.decodeByteArray] 直接解码，不做采样 —— 图很小，
 * 采样反而会糊掉数字。点击整块区域重新获取。
 */
@Composable
private fun CaptchaImage(
    state: LoginUiState,
    onRefresh: () -> Unit,
    onCaptchaChange: (String) -> Unit,
) {
    val bitmap = remember(state.captchaImage) {
        state.captchaImage?.let { captcha ->
            val bytes = captcha.imageBytes
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 140.dp, height = 60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !state.captchaLoading, onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.captchaLoading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = null)
                    else -> Text(
                        text = stringResource(R.string.login_captcha_tap_retry),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = state.captcha,
                onValueChange = onCaptchaChange,
                label = { Text(stringResource(R.string.login_captcha_label)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.login_captcha_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
