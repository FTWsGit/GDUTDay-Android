package com.gdutday.core.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AndroidKeystoreCipher] 在"KeyStore 不可用"环境下的行为验证（Robolectric）。
 *
 * ## Robolectric 没有 AndroidKeyStore provider
 *
 * Robolectric 的 JVM 环境不模拟 AndroidKeyStore（`KeyStore.getInstance("AndroidKeyStore")`
 * 直接抛 KeyStoreException），真实硬件密钥的生成/加解密只能靠真机抽查。但 T2.1 关心的
 * "密钥丢失后的静默丢弃"恰好可以在这里完整验证：拿一个空载的普通 KeyStore 实例
 * （取不到密钥、删除是空操作）当作"密钥已丢失的 KeyStore"，契约要求：
 *
 * - key provider 的取钥/删钥全程不抛；
 * - 解密路径**绝不抛异常**，一律返回 null（冷启动当作未登录）；
 * - 加密路径抛 [IllegalStateException]（写入失败必须让调用方感知，不静默丢数据）。
 */
// Robolectric 4.16 最高支持 SDK 36，而项目 targetSdk=37，显式钉一个受支持的 SDK。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidKeystoreCipherUnavailableKeystoreTest {

    /** Robolectric 环境没有 AndroidKeyStore provider —— 本测试类其余用例的前提。 */
    @Test
    fun `前提确认-Robolectric环境没有AndroidKeyStore provider`() {
        val e = runCatching {
            java.security.KeyStore.getInstance(KeystoreCipher.ANDROID_KEYSTORE).load(null)
        }.exceptionOrNull()

        assertThat(e).isNotNull()
    }

    /** 空载 KeyStore = "密钥已丢失"（恢复出厂 / 换机后）的等价形态。 */
    private fun emptyKeyStore(): java.security.KeyStore =
        java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            .apply { load(null) }

    @Test
    fun `密钥丢失时取钥返回null且不抛`() {
        val provider = AndroidKeystoreKeyProvider(emptyKeyStore())

        assertThat(provider.getExistingKey()).isNull()
        // 删除一个不存在的别名同样是空操作而非异常
        provider.deleteKey()
    }

    @Test
    fun `密钥丢失时解密静默返回null而不是崩溃`() {
        val cipher = AesGcmCipher(AndroidKeystoreKeyProvider(emptyKeyStore()))
        val ciphertext = ByteArray(KeystoreCipher.GCM_IV_LENGTH + 32) { 0x42 }

        assertThat(cipher.decrypt(ciphertext)).isNull()
        assertThat(cipher.decryptFromString("AAAA")).isNull()
    }

    @Test
    fun `密钥不可用时加密抛IllegalStateException而不是静默`() {
        val cipher = AesGcmCipher(AndroidKeystoreKeyProvider(emptyKeyStore()))

        assertThrows(IllegalStateException::class.java) {
            cipher.encrypt("cookie=JSESSIONID".toByteArray(Charsets.UTF_8))
        }
        // encryptToString 是便捷封装，把异常折叠成 null（调用方按"没存上"处理）
        assertThat(cipher.encryptToString("secret")).isNull()
    }
}
