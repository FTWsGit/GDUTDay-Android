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
     *
     * ## 坏密钥自动恢复
     *
     * 生产环境上常见的失败路径：系统升级 / StrongBox 状态变化 / 厂商 ROM bug
     * 导致旧密钥处于"能从 KeyStore 取到 Key 对象，但 cipher.init 抛 KeyPermanentlyInvalidatedException
     * 或 ProviderException"的状态。此时静默重试一次：删旧密钥、生成新密钥、再加密。
     * 代价是旧数据（cookie / 记住的密码）丢失，用户会看到重新登录 —— 但这比"永远登不上"好得多。
     */
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keys.getOrCreateKey()
            ?: throw IllegalStateException("AndroidKeyStore 无法获取或创建 AES 密钥")

        return try {
            doEncrypt(key, plaintext)
        } catch (first: Exception) {
            // 只对"坏密钥"类异常重建重试；OOM 等瞬时故障（RuntimeException 而非安全异常）
            // 必须原样抛出——把瞬时故障放大成"删钥重建"会永久丢失用户登录态。
            if (first !is java.security.GeneralSecurityException && first !is ProviderException) {
                throw first
            }
            // 可能是坏密钥：删除重建重试一次。
            try {
                keys.deleteKey()
            } catch (_: Exception) {
            }
            val newKey = keys.getOrCreateKey()
                ?: throw IllegalStateException(
                    "AES/GCM 加密失败（第一次失败：${first.message}，重建密钥也失败）", first
                )
            try {
                doEncrypt(newKey, plaintext)
            } catch (second: Exception) {
                throw IllegalStateException(
                    "AES/GCM 加密失败（重建密钥后仍失败，first=${first::class.java.simpleName}:${first.message}，" +
                        "second=${second::class.java.simpleName}:${second.message}）", second
                )
            }
        }
    }

    private fun doEncrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(KeystoreCipher.GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(KeystoreCipher.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val sealed = cipher.doFinal(plaintext)
        return ByteArray(iv.size + sealed.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(sealed, 0, it, iv.size, sealed.size)
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
            // 部分 ROM 上 deleteEntry 后需要 reload，否则紧接着生成的新密钥
            // 会报"别名已存在"或取到僵尸句柄。
            keyStore.load(null)
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
                // 必须设为 false，否则小米/MIUI 等严格执行 Keystore2 规范的 ROM 会拒绝
                // 我们显式传入的 IV（GCMParameterSpec），报 CALLER_NONCE_PROHIBITED。
                // 我们仍然用 SecureRandom 生成每次不同的 12 字节 IV，安全性不变，
                // 只是让 Cipher 自己从 GCMParameterSpec 里取 IV，而不是让 Keystore 生成。
                .setRandomizedEncryptionRequired(false)
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
        } catch (e: java.security.GeneralSecurityException) {
            // 部分厂商 ROM 把强盒失败包成 InvalidAlgorithmParameterException 等非 ProviderException，
            // 同样静默降级到普通 TEE。
            null
        } catch (e: RuntimeException) {
            // 极少数厂商 ROM 的 KeyGenerator 会直接抛 RuntimeException（如 IncompatibleClassChangeError）。
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
