package com.gdutday.core.datastore

import com.gdutday.data.gdut.session.GdutSession
import kotlinx.coroutines.flow.Flow

/**
 * 登录态（cookie）的加密存储。
 *
 * ## ⚠ 为什么 cookie 是"最高机密"，比密码还敏感
 *
 * `GdutSession.cookies` 里的 `JSESSIONID`（jxfw 域）**就是会话本身**。
 * 拿到它的人不需要密码就能直接访问教务系统，而且：
 * - 密码泄露用户可以改，**cookie 泄露用户根本不知道**（没有任何提示）；
 * - cookie 的有效期比一次登录长得多（`rememberMe=true` 时更久）；
 * - 它同时代表 authserver 的 TGT，能横向访问其它子系统。
 *
 * 所以：
 * 1. 存在 `filesDir` 下的独立文件，**Keystore AES/GCM 加密**，不进 SharedPreferences；
 * 2. `toString()` / 日志 / 异常信息里只允许出现 [GdutSession.toSafeString]；
 * 3. **绝不上传**到任何第三方（本项目没有后端，天然满足）；
 * 4. 用户点"退出登录"时调用 [clear] 做安全擦除（覆盖后删除，见实现）。
 *
 * ## 为什么不用 EncryptedSharedPreferences
 *
 * `androidx.security:security-crypto` 处于维护模式，且内部封装有两个实际踩过的坑：
 * - 首次创建 master key 在部分机型上耗时数秒（直接拖慢冷启动，与本项目目标冲突）；
 * - master key 一旦损坏（OTA、备份恢复、多用户切换）就无法恢复，
 *   整个 SharedPreferences 文件报废，App 会**每次启动都崩溃**，只能靠用户手动清数据。
 *
 * 自己用 `AndroidKeyStore` + AES/GCM 写 60 行，行为完全可控，
 * 而且解密失败时可以**静默丢弃**（当作未登录）而不是崩溃。见 [KeystoreCipher]。
 */
public interface SessionStore {

    /**
     * 当前会话流。
     *
     * 首次订阅时读一次文件并解密。**解密失败必须发 null 而不是抛异常** ——
     * 密钥失效（恢复出厂、跨设备迁移）时用户应该看到"请登录"，而不是闪退。
     */
    public val session: Flow<GdutSession?>

    /** 一次性读取。WorkManager / Widget 进程里用，避免为了读一次而起协程收集 Flow。 */
    public suspend fun current(): GdutSession?

    public suspend fun save(session: GdutSession)

    /** 清除会话（退出登录、会话过期）。 */
    public suspend fun clear()
}

/**
 * 记住的账号密码。
 *
 * ## 默认**不**记住密码
 *
 * 旧小程序把 `{ID, password}` 以**明文 JSON** 存在 `uni.storage` 里
 * （`commonFun.js` 的 `getStorageSync('userAndPwd')`），并且每周用这份明文密码自动重登。
 * 在 Android 上，`uni.storage` 对应的 SharedPreferences 文件在 root 设备上、
 * 在 `adb backup` 里、在很多"数据恢复"工具下都是可读的。
 *
 * 本项目的立场：
 * - `rememberPassword` **默认 false**，用户必须显式勾选，且勾选处要有风险说明文案；
 * - 勾选后密码经 [KeystoreCipher] 加密落盘，硬件级密钥不出 TEE；
 * - 即使记住了，也**只用于**会话过期后的静默重登，不用于任何其它用途。
 *
 * 更好的替代方案（已实现）：优先靠 cookie 保活。`rememberMe=true` 的会话
 * 通常能撑很久，绝大多数情况下根本不需要存密码。
 */
public interface CredentialStore {

    public val credentials: Flow<StoredCredentials?>

    public suspend fun current(): StoredCredentials?

    public suspend fun save(credentials: StoredCredentials)

    public suspend fun clear()
}

/**
 * 已记住的凭据。
 *
 * @property password **明文**。它只应存在于内存中，落盘前必须经 [KeystoreCipher.encrypt]。
 *   这个字段刻意不加 `toString()` 覆写来隐藏 —— 因为 `data class` 的 `copy`/解构要用，
 *   隐藏了反而容易让人以为它安全。**纪律靠 [SessionStore] 的注释和 code review 保证。**
 * @property savedAt 保存时刻。用于设置页显示"密码已记住 N 天"，
 *   提醒用户这是一个长期存在的敏感数据。
 */
public data class StoredCredentials(
    public val username: String,
    public val password: String,
    public val method: com.gdutday.data.gdut.session.LoginMethod,
    public val rememberPassword: Boolean = true,
    public val savedAt: Long = System.currentTimeMillis(),
) {
    /** 脱敏摘要，可安全写日志。 */
    override fun toString(): String =
        "StoredCredentials(username=${username.maskMiddle()}, method=$method, pwdLen=${password.length})"
}

/** 把字符串中间部分打码：`3120001234` → `312****234`。用于日志与设置页展示。 */
public fun String.maskMiddle(keepHead: Int = 3, keepTail: Int = 3): String = when {
    length <= keepHead + keepTail -> "*".repeat(length)
    else -> take(keepHead) + "*".repeat(length - keepHead - keepTail) + takeLast(keepTail)
}

/**
 * AndroidKeyStore 的 AES/GCM 封装。实现见 `AndroidKeystoreCipher`。
 *
 * ## 密钥管理约定
 *
 * - 密钥别名固定为 [KEY_ALIAS]，`setUserAuthenticationRequired(false)`
 *   （要求用户认证会让后台同步和 Widget 进程无法解密）；
 * - `setIsStrongBoxBacked(true)` 在支持的机型上启用，不支持时静默降级；
 * - **不设过期时间**。设了会导致某天突然全部解密失败；
 * - 解密抛 `AEADBadTagException` 时，调用方**必须**当作"数据不存在"处理并清除文件，
 *   绝不能崩溃。这是 EncryptedSharedPreferences 那个"永久崩溃"坑的正解。
 */
public interface KeystoreCipher {

    /**
     * 加密。返回 `IV(12 字节) || 密文+Tag`，整体再做 Base64。
     *
     * GCM 的 IV **绝不能重复**（同一密钥下重用 IV 会直接泄露明文异或），
     * 所以每次调用都生成新的随机 IV 并前置存放。
     */
    public fun encrypt(plaintext: ByteArray): ByteArray

    /**
     * 解密。
     *
     * @return 明文；密钥不可用、数据被篡改、IV 损坏时返回 **null**（不抛异常）。
     *   "解密失败"和"没存过"对调用方来说处理完全一样（当作未登录），
     *   所以用 null 比用异常更贴切，也避免了在冷启动路径上抛异常。
     */
    public fun decrypt(ciphertext: ByteArray): ByteArray?

    /** 明文字符串的便捷封装。 */
    public fun encryptToString(plaintext: String): String?

    public fun decryptFromString(ciphertext: String): String?

    /** 删除密钥。仅用于设置页的"重置加密密钥"（会同时清空所有加密数据）。 */
    public fun destroyKey()

    public companion object {
        public const val KEY_ALIAS: String = "gdutday_session_key"
        public const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
        /** GCM 推荐 IV 长度 12 字节。 */
        public const val GCM_IV_LENGTH: Int = 12
        public const val TRANSFORMATION: String = "AES/GCM/NoPadding"
    }
}
