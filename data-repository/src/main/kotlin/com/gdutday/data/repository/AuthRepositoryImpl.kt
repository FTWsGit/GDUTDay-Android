package com.gdutday.data.repository

import com.gdutday.core.database.GdutDatabase
import com.gdutday.core.datastore.CredentialStore
import com.gdutday.core.datastore.SessionStore
import com.gdutday.core.datastore.StoredCredentials
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.UserType
import com.gdutday.data.gdut.auth.AuthServerClient
import com.gdutday.data.gdut.jxfw.CaptchaImage
import com.gdutday.data.gdut.jxfw.JxfwClient
import com.gdutday.data.gdut.jxfw.JxfwDirectLogin
import com.gdutday.data.gdut.session.GdutSession
import com.gdutday.data.gdut.session.LoginMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * [AuthRepository] 的实现：把统一认证与教务直登两条登录路径收口成一种登录态。
 *
 * ## 登录态为什么是懒加载的
 *
 * [session] 用 `stateIn(..., SharingStarted.Lazily, null)` 暴露：构造函数里**不读盘**，
 * 第一个订阅者出现时才解密会话文件。本项目首屏是课表页、直接读 Room，
 * 会话只在真正要同步时才有用，所以未登录用户与只用本地缓存的用户
 * 完全不会为一次文件解密付费 —— 这正是冷启动目标的一部分。
 *
 * ## 为什么登录成功后要再校验一次身份
 *
 * `AuthServerClient` 默认已经拒绝非本科生，但那是 data-gdut 层的策略开关
 * （验证脚本会关掉它）。Repository 是业务边界，**必须自己再挡一次**，
 * 否则一旦将来有人把那个开关关掉，研究生账号就会带着只有本科生才有的会话结构
 * 一路流到课表解析，报出难以定位的错误。
 *
 * @param authClientFactory 每次登录都新建统一认证客户端。Client 内部每登录一次都新建
 *   CookieJar，复用实例会让 CAS 认为 flow 已失效，所以这里只暴露工厂不缓存实例。
 * @param jxfwClientFactory 用会话构造教务客户端，用于探活与静默重登后的验证。
 *   会话为 null 时不应被调用。
 * @param scope 承载 [session] 的共享；生命周期与 Application 相同。
 */
public class AuthRepositoryImpl(
    private val sessionStore: SessionStore,
    private val credentialStore: CredentialStore,
    private val database: GdutDatabase,
    private val okHttpClient: OkHttpClient,
    private val authClientFactory: () -> AuthServerClient,
    private val jxfwClientFactory: (GdutSession) -> JxfwClient,
    private val scope: CoroutineScope,
) : AuthRepository {

    /**
     * 当前会话。
     *
     * `SharingStarted.Lazily` + 初始 null：无人收集时 `value` 恒为 null（不读盘），
     * 一旦收集就保持订阅，后续 [SessionStore.save] 写入的新会话会立刻反映到 UI。
     */
    override val session: StateFlow<GdutSession?> =
        sessionStore.session.stateIn(scope, SharingStarted.Lazily, null)

    override val isLoggedIn: Flow<Boolean> =
        session.map { it != null }.distinctUntilChanged()

    override val profile: Flow<StudentProfile?> =
        session.map { it?.profile }.distinctUntilChanged()

    override suspend fun login(
        studentId: String,
        password: String,
        rememberPassword: Boolean,
    ): GdutSession = withContext(Dispatchers.IO) {
        val session = authClientFactory().login(studentId, password)
        requireUndergraduate(session)
        persist(session, password, rememberPassword)
        session
    }

    override suspend fun loginViaJxfw(
        studentId: String,
        password: String,
        captcha: String,
        captchaToken: String,
        rememberPassword: Boolean,
    ): GdutSession = withContext(Dispatchers.IO) {
        // captchaToken 就是取验证码时服务端下发的 JSESSIONID，必须原样回传，
        // 否则服务端找不到那次验证码的答案，永远提示"验证码不正确"。
        val session = JxfwDirectLogin.login(
            httpClient = okHttpClient,
            studentId = studentId,
            password = password,
            verifyCode = captcha,
            captchaCookie = captchaToken,
        )
        requireUndergraduate(session)
        persist(session, password, rememberPassword)
        session
    }

    override suspend fun fetchJxfwCaptcha(): JxfwCaptcha = withContext(Dispatchers.IO) {
        val image: CaptchaImage = JxfwDirectLogin.fetchCaptcha(okHttpClient)
        JxfwCaptcha(
            imageBytes = image.bytes,
            // token 语义就是"绑定了那次验证码会话的不透明串"，与 CaptchaImage.cookieHeader 完全一致。
            token = image.cookieHeader.orEmpty(),
            width = CaptchaImage.EXPECTED_WIDTH,
            height = CaptchaImage.EXPECTED_HEIGHT,
        )
    }

    /**
     * 探活教务系统首页。网络异常按"不可用"处理（[JxfwClient.isSessionValid] 已吞掉 IOException），
     * 这里再兜一层 runCatching，保证本方法**永不抛异常**（契约要求）。
     */
    override suspend fun isSessionValid(): Boolean = withContext(Dispatchers.IO) {
        val current = sessionStore.current() ?: return@withContext false
        runCatching { jxfwClientFactory(current).isSessionValid() }.getOrDefault(false)
    }

    /**
     * 用记住的密码静默重登。
     *
     * 只对 [LoginMethod.UNIFIED_AUTH] 生效：教务直登每次都需要用户看一张新的图形验证码，
     * 后台无声重登在物理上不可能。没有记住密码、或密码已被用户改掉时返回 null，
     * 由调用方决定是否跳登录页。
     */
    override suspend fun reloginSilently(): GdutSession? = withContext(Dispatchers.IO) {
        val credentials = credentialStore.current() ?: return@withContext null
        if (credentials.method != LoginMethod.UNIFIED_AUTH) return@withContext null

        // 先探活：会话仍有效就直接复用，避免每次冷启动都发起一次完整密码登录。
        // 这既减少触发学校风控，也避免用户改密后在我们这里累积"密码错误"计数。
        if (isSessionValid()) return@withContext sessionStore.current()

        runCatching {
            val session = authClientFactory().login(credentials.username, credentials.password)
            requireUndergraduate(session)
            sessionStore.save(session)
            session
        }.getOrNull()
    }

    override suspend fun logout(clearLocalData: Boolean) {
        sessionStore.clear()
        credentialStore.clear()
        if (clearLocalData) {
            // 退出登录但换账号的场景很常见，默认保留课表；只有用户显式要求才清库。
            withContext(Dispatchers.IO) { database.clearAllTables() }
        }
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun persist(session: GdutSession, password: String, rememberPassword: Boolean) {
        sessionStore.save(session)
        if (rememberPassword) {
            credentialStore.save(
                StoredCredentials(
                    username = session.studentId,
                    password = password,
                    method = session.method,
                    rememberPassword = true,
                ),
            )
        } else {
            // 用户取消勾选"记住密码"（或换账号时未勾选）：必须清掉磁盘上的旧凭据，
            // 否则下次冷启动仍会用旧密码静默重登，与用户意愿相反。
            credentialStore.clear()
        }
    }

    /**
     * 挡住研究生 / 教师账号。
     *
     * 注意 [UserType.UNKNOWN] 也一并拒绝：学号首位不是 3/2/0 时会得到 UNKNOWN，
     * 而开发阶段"无法识别"比"带着一半解析失败的数据继续跑"更好排查。
     */
    private fun requireUndergraduate(session: GdutSession) {
        if (session.userType != UserType.UNDERGRADUATE) {
            throw GdutException.UnsupportedUserType(session.userType)
        }
    }
}
