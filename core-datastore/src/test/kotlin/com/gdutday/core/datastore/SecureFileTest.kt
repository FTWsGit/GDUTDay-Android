package com.gdutday.core.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * [SecureFile] / [EncryptedValueStore] 的测试 —— 覆盖"落盘损坏时静默丢弃"这条
 * 刻意设计，以及退出登录时的安全擦除。
 */
class SecureFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val stringCodec = object : FileCodec<String> {
        override fun encode(value: String): String = value
        override fun decode(raw: String): String = raw
    }

    private fun key(seed: Byte): SecretKey = SecretKeySpec(ByteArray(32) { seed }, "AES")

    private fun cipher(seed: Byte): KeystoreCipher = AesGcmCipher(object : AesKeyProvider {
        override fun getOrCreateKey(): SecretKey = key(seed)
        override fun getExistingKey(): SecretKey = key(seed)
        override fun deleteKey() = Unit
    })

    @Test
    fun `写入后读取往返一致`() {
        val file = SecureFile(File(folder.root, "a.enc"), cipher(1), stringCodec)
        file.write("hello 世界")
        assertThat(file.read()).isEqualTo("hello 世界")
    }

    @Test
    fun `文件不存在时读取返回 null`() {
        val file = SecureFile(File(folder.root, "missing.enc"), cipher(1), stringCodec)
        assertThat(file.read()).isNull()
    }

    @Test
    fun `密文损坏时删除文件并返回 null`() {
        val path = File(folder.root, "corrupt.enc")
        path.writeText("this is not valid base64 !!!")
        val file = SecureFile(path, cipher(1), stringCodec)

        assertThat(file.read()).isNull()
        assertThat(path.exists()).isFalse()
    }

    @Test
    fun `密钥变了导致解不开时删除文件`() {
        val path = File(folder.root, "wrong-key.enc")
        SecureFile(path, cipher(1), stringCodec).write("secret")
        assertThat(path.exists()).isTrue()

        val decoded = SecureFile(path, cipher(2), stringCodec).read()

        assertThat(decoded).isNull()
        assertThat(path.exists()).isFalse()
    }

    @Test
    fun `安全擦除后文件不存在`() {
        val path = File(folder.root, "erase.enc")
        val file = SecureFile(path, cipher(1), stringCodec)
        file.write("cookie=JSESSIONID-xyz")
        val sizeBefore = path.length()
        assertThat(sizeBefore).isGreaterThan(0)

        file.secureErase()

        assertThat(path.exists()).isFalse()
    }

    @Test
    fun `懒加载：首次读取 null，保存后可读，清除后回到 null`() = runBlocking {
        val path = File(folder.root, "lazy.enc")
        val store = EncryptedValueStore(SecureFile(path, cipher(3), stringCodec))

        // 构造后未读盘
        assertThat(path.exists()).isFalse()
        assertThat(store.current()).isNull()
        // 首次 current() 触发读盘（文件不存在，返回 null）
        assertThat(store.current()).isNull()

        store.save("session")
        assertThat(store.current()).isEqualTo("session")
        assertThat(store.flow.first()).isEqualTo("session")

        store.clear()
        assertThat(store.current()).isNull()
        assertThat(path.exists()).isFalse()
    }
}
