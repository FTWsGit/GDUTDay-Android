package com.gdutday.core.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * [AesGcmCipher] 的纯 JVM 测试。
 *
 * 用假 [AesKeyProvider] 注入固定密钥，从而把 AES/GCM 的全部安全性质
 * （IV 唯一、往返一致、篡改必败、换密钥必败）在不需要模拟器的情况下验证掉。
 * 真实 AndroidKeyStore 的交互留给 androidTest。
 */
class AesGcmCipherTest {

    private fun key(vararg seed: Byte): SecretKey =
        SecretKeySpec(ByteArray(32) { seed.firstOrNull() ?: 0.toByte() }, "AES")

    private fun provider(key: SecretKey?) = object : AesKeyProvider {
        override fun getOrCreateKey(): SecretKey? = key
        override fun getExistingKey(): SecretKey? = key
        override fun deleteKey() = Unit
    }

    @Test
    fun `加密解密往返一致`() {
        val cipher = AesGcmCipher(provider(key(1)))
        val plaintext = "JSESSIONID=abc123; route=node1".toByteArray(Charsets.UTF_8)

        val decrypted = cipher.decrypt(cipher.encrypt(plaintext))

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `字符串便捷方法往返一致`() {
        val cipher = AesGcmCipher(provider(key(2)))
        val plaintext = "{\"username\":\"3120012345\",\"password\":\"secret\"}"

        val encoded = cipher.encryptToString(plaintext)

        assertThat(encoded).isNotNull()
        assertThat(cipher.decryptFromString(encoded!!)).isEqualTo(plaintext)
    }

    @Test
    fun `每次加密生成不同 IV，因此相同明文密文不同`() {
        val cipher = AesGcmCipher(provider(key(3)))
        val plaintext = "same-plaintext".toByteArray(Charsets.UTF_8)

        val a = cipher.encrypt(plaintext)
        val b = cipher.encrypt(plaintext)

        // 密文不同（否则等于固定指纹，见 AuthServerCrypto 的 IV 复用教训）
        assertThat(a).isNotEqualTo(b)
        // 前置的 12 字节 IV 必须不同
        assertThat(a.copyOfRange(0, KeystoreCipher.GCM_IV_LENGTH))
            .isNotEqualTo(b.copyOfRange(0, KeystoreCipher.GCM_IV_LENGTH))
        // 但两者都能解回原值
        assertThat(cipher.decrypt(a)).isEqualTo(plaintext)
        assertThat(cipher.decrypt(b)).isEqualTo(plaintext)
    }

    @Test
    fun `篡改密文会被 GCM Tag 拒绝并返回 null`() {
        val cipher = AesGcmCipher(provider(key(4)))
        val encrypted = cipher.encrypt("hello".toByteArray(Charsets.UTF_8))

        // 翻转密文体（IV 之后）的一个字节
        val tampered = encrypted.copyOf()
        tampered[KeystoreCipher.GCM_IV_LENGTH] = (tampered[KeystoreCipher.GCM_IV_LENGTH].toInt() xor 0x01).toByte()

        assertThat(cipher.decrypt(tampered)).isNull()
    }

    @Test
    fun `篡改 IV 会返回 null`() {
        val cipher = AesGcmCipher(provider(key(5)))
        val encrypted = cipher.encrypt("hello".toByteArray(Charsets.UTF_8))

        val tampered = encrypted.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        assertThat(cipher.decrypt(tampered)).isNull()
    }

    @Test
    fun `用另一把密钥解密返回 null`() {
        val encryptCipher = AesGcmCipher(provider(key(6)))
        val otherCipher = AesGcmCipher(provider(key(7)))

        val encrypted = encryptCipher.encrypt("top-secret".toByteArray(Charsets.UTF_8))

        assertThat(otherCipher.decrypt(encrypted)).isNull()
    }

    @Test
    fun `密钥不存在时解密返回 null 而不是抛异常`() {
        val cipher = AesGcmCipher(provider(null))
        // 自己造一段合法长度但无密钥可解的密文
        val fake = ByteArray(KeystoreCipher.GCM_IV_LENGTH + 16) { 0x7f }
        assertThat(cipher.decrypt(fake)).isNull()
    }

    @Test
    fun `过短或空的密文返回 null`() {
        val cipher = AesGcmCipher(provider(key(8)))
        assertThat(cipher.decrypt(ByteArray(0))).isNull()
        assertThat(cipher.decrypt(ByteArray(KeystoreCipher.GCM_IV_LENGTH))).isNull()
        assertThat(cipher.decryptFromString("not-base64!!!")).isNull()
        assertThat(cipher.decryptFromString("")).isNull()
    }

    @Test
    fun `加密时密钥不可用会抛异常，避免静默丢数据`() {
        val cipher = AesGcmCipher(provider(null))
        assertThrows(IllegalStateException::class.java) {
            cipher.encrypt("data".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `destroyKey 委托给 provider`() {
        var deleted = false
        val cipher = AesGcmCipher(object : AesKeyProvider {
            override fun getOrCreateKey(): SecretKey = key(9)
            override fun getExistingKey(): SecretKey = key(9)
            override fun deleteKey() {
                deleted = true
            }
        })
        cipher.destroyKey()
        assertThat(deleted).isTrue()
    }
}
