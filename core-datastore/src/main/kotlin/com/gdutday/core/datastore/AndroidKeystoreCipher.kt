package com.gdutday.core.datastore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 提供 AES 密钥的抽象。
 *
 * ## 为什么要把"密钥从哪来"单独抽出来
 *
 * [KeystoreCipher] 的策略（IV 前置、解密失败一律返回 null、Base64 编码）是纯逻辑，
 * 与 AndroidKeyStore 毫无关系。把密钥来源抽象成一个只返回 [SecretKey] 的接口后，
 * [AesGcmCipher] 就能在普通 JVM 单元测试里用一个假的 [SecretKey] 完整验证：
 * 往返一致性、IV 每次不同、篡改必被 GCM Tag 拒绝、换密钥必失败。
 *
 * 真正依赖 AndroidKeyStore 的只剩 [AndroidKeystoreKeyProvider] 这一个类，
 * 它没有任何逻辑分支值得用模拟器去测。
 *
 * ## 加密与解密为什么要分开两个方法
 *
 * 解密时**绝不能**顺手生成密钥：如果密钥因恢复出厂 / 跨设备迁移而丢失，
 * 生成一把新密钥再拿去解密只会失败，但会留下一个"看起来一切正常"的新密钥，
 * 让调用方误以为数据还在。让 [getExistingKey] 返回 null，语义上就等价于"没存过"，
 * 与 [KeystoreCipher] 的契约一致。
 */
public interface AesKeyProvider {

    /** 加密路径：优先取已有密钥，不存在则生成。生成失败返回 null（不抛异常）。 */
    public fun getOrCreateKey(): SecretKey?

    /** 解密路径：只取已有密钥，**绝不生成**。不存在返回 null。 */
    public fun getExistingKey(): SecretKey?

    /** 删除密钥。 */
    public fun deleteKey()
}

/**
 * [KeystoreCipher] 的纯 JVM 实现：AES/GCM/NoPadding，输出 `IV(12 字节) || 密文+Tag`。
 *
 * 之所以不把这个类写成 internal：androidTest 与将来的跨模块复用都可能需要它，
 * 而且它本身不含任何平台代码，作为公开 API 没有副作用。
 *
 * @param secureRandom 可注入以便测试确定性；默认使用系统安全随机源。
 */
public open class AesGcmCipher(
    private val keys: AesKeyProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) : KeystoreCipher {

    /**
     * @throws IllegalStateException 密钥无法获取或生成，或底层 Cipher 异常。
     *   加密失败是"要写入的数据没写成功"，必须让调用方感知，否则会静默丢数据；
     *   这与解密失败（当作未登录）的处理方式刻意不同。
     */
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keys.getOrCreateKey()
            ?: throw IllegalStateException("AndroidKeyStore 无法获取或创建 AES 密钥，加密中止")

        // GCM 下同一密钥重用 IV 会直接泄露两段明文的异或。每次加密都重新生成，
        // 并把它前置存放，解密方无需额外通道即可拿到。
        val iv = ByteArray(KeystoreCipher.GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        return try {
            val cipher = Cipher.getInstance(KeystoreCipher.TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val sealed = cipher.doFinal(plaintext)
            ByteArray(iv.size + sealed.size).also {
                System.arraycopy(iv, 0, it, 0, iv.size)
                System.arraycopy(sealed, 0, it, iv.size, sealed.size)
            }
        } catch (e: Exception) {
            throw IllegalStateException("AES/GCM 加密失败", e)
        }
    }

    /**
     * 解密失败**一律**返回 null，不抛异常。
     *
     * 覆盖的失败面：密钥不存在（恢复出厂 / 跨设备迁移）、GCM Tag 校验失败（文件被篡改或截断）、
     * 密文长度非法、Keystore 内部异常。对调用方来说这些的处理完全一样（当作未登录），
     * 用 null 比用异常更贴切，也避免在冷启动路径上抛异常。
     */
    override fun decrypt(ciphertext: ByteArray): ByteArray? {
        // 至少要有 IV 和一个 GCM Tag（16 字节），否则连 Cipher.init 都会抛。
        if (ciphertext.size <= KeystoreCipher.GCM_IV_LENGTH) return null
        val key = keys.getExistingKey() ?: return null

        return try {
            val cipher = Cipher.getInstance(KeystoreCipher.TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_LENGTH_BITS, ciphertext, 0, KeystoreCipher.GCM_IV_LENGTH),
            )
            cipher.doFinal(
                ciphertext,
                KeystoreCipher.GCM_IV_LENGTH,
                ciphertext.size - KeystoreCipher.GCM_IV_LENGTH,
            )
        } catch (e: Exception) {
            // 刻意吞掉：AEADBadTagException / IllegalBlockSizeException / 密钥失效……
            // 调用方只需要知道"解不开"。
            null
        }
    }

    override fun encryptToString(plaintext: String): String? = try {
        // java.util.Base64 而不是 android.util.Base64：前者从 API 26 起可用（与 minSdk 一致），
        // 且能在 JVM 单元测试里跑；其编码器默认就不换行，等价于 android 的 NO_WRAP。
        Base64.getEncoder().encodeToString(encrypt(plaintext.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        null
    }

    override fun decryptFromString(ciphertext: String): String? = try {
        decrypt(Base64.getDecoder().decode(ciphertext))?.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    override fun destroyKey() {
        keys.deleteKey()
    }

    private companion object {
        /** GCM 认证标签长度。128 位是推荐值，也是 AndroidKeyStore 默认值。 */
        const val TAG_LENGTH_BITS: Int = 128
    }
}

/**
 * [AesKeyProvider] 的 AndroidKeyStore 实现。
 *
 * ## 密钥参数里的每一个选择及其理由
 *
 * - `setUserAuthenticationRequired(false)`：一旦设为 true，后台同步（WorkManager）
 *   与桌面 Widget 进程在设备锁屏时都无法解密，登录态会在用户完全无感的情况下失效。
 * - `setIsStrongBoxBacked(true)`：强盒（独立安全芯片）能抵抗物理提取，优先启用；
 *   但**必须静默降级**——大量中低端机、模拟器、以及强盒密钥槽被占满时都会失败。
 *   这里不引用 API 28 的 `StrongBoxUnavailableException` 类本身（minSdk 26 上类加载有风险），
 *   而是捕获它的父类 [ProviderException]。
 * - **不设** `setKeyValidityForOrigination/Consumption`：设了过期时间会导致某天
 *   全部加密数据突然无法解密，是比"密钥被物理提取"离用户更近的灾难。
 */
internal class AndroidKeystoreKeyProvider(
    private val keyStore: KeyStore,
) : AesKeyProvider {

    @Synchronized
    override fun getOrCreateKey(): SecretKey? {
        getExistingKey()?.let { return it }
        // 先试强盒，失败再退普通 TEE。两次都失败说明 KeyStore 本身有问题，返回 null 由上层处理。
        return tryGenerate(strongBox = true) ?: tryGenerate(strongBox = false)
    }

    @Synchronized
    override fun getExistingKey(): SecretKey? = try {
        keyStore.getKey(KeystoreCipher.KEY_ALIAS, null) as? SecretKey
    } catch (e: Exception) {
        null
    }

    @Synchronized
    override fun deleteKey() {
        try {
            keyStore.deleteEntry(KeystoreCipher.KEY_ALIAS)
        } catch (e: Exception) {
            // 删除失败不应阻断"重置加密数据"这个动作，调用方随后会清空密文文件。
        }
    }

    private fun tryGenerate(strongBox: Boolean): SecretKey? {
        if (strongBox && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val spec = KeyGenParameterSpec.Builder(
                KeystoreCipher.KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // 显式写出：后台同步与 Widget 都要能解密。
                .setUserAuthenticationRequired(false)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KeystoreCipher.ANDROID_KEYSTORE,
            )
            generator.init(spec)
            generator.generateKey()
        } catch (e: ProviderException) {
            // 强盒不可用 / 槽位占满 / 厂商实现异常，一律降级。
            null
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val KEY_SIZE_BITS: Int = 256
    }
}

/**
 * [KeystoreCipher] 的 Android 实现。委托给纯逻辑的 [AesGcmCipher] 与
 * 平台相关的 [AndroidKeystoreKeyProvider]。
 *
 * 构造时只 `load` 一次 KeyStore（很快）；真正的密钥生成推迟到首次 [encrypt]，
 * 这样即使 App 从未登录，也不会在冷启动时付出建密钥的代价。
 * 这一点正是弃用 `EncryptedSharedPreferences` 的核心原因之一。
 */
public class AndroidKeystoreCipher private constructor(
    private val delegate: KeystoreCipher,
) : KeystoreCipher by delegate {

    public constructor() : this(AesGcmCipher(AndroidKeystoreKeyProvider(loadKeyStore())))

    /** 供 androidTest 注入，验证真 KeyStore 行为。 */
    internal constructor(keyStore: KeyStore) : this(AesGcmCipher(AndroidKeystoreKeyProvider(keyStore)))

    private companion object {
        fun loadKeyStore(): KeyStore =
            KeyStore.getInstance(KeystoreCipher.ANDROID_KEYSTORE).apply { load(null) }
    }
}
