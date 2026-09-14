package com.gdutday.core.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [SecureFile] 在 Android 文件系统路径上的行为验证（Robolectric）。
 *
 * [SecureFileTest] 已在普通 JVM 的临时目录上验证了核心契约；这里换到
 * `context.filesDir`（App 真实运行时的目录形态）补充三类 JVM 测试没盯住的行为：
 * 擦除后目录状态、孤儿 tmp 清理、大文件（多 chunk 覆盖）擦除。
 */
// Robolectric 4.16 最高支持 SDK 36，而项目 targetSdk=37，显式钉一个受支持的 SDK。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureFileAndroidFsTest {

    private lateinit var filesDir: File

    private val stringCodec = object : FileCodec<String> {
        override fun encode(value: String): String = value
        override fun decode(raw: String): String = raw
    }

    private fun cipher(): KeystoreCipher = AesGcmCipher(object : AesKeyProvider {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
        override fun getOrCreateKey(): javax.crypto.SecretKey = key
        override fun getExistingKey(): javax.crypto.SecretKey = key
        override fun deleteKey() = Unit
    })

    @Before
    fun setUp() {
        filesDir = ApplicationProvider.getApplicationContext<Context>().filesDir
        filesDir.mkdirs()
    }

    @Test
    fun `filesDir下写入读取与擦除全链路`() {
        val path = File(filesDir, "session.enc")
        val file = SecureFile(path, cipher(), stringCodec)

        file.write("JSESSIONID=abc; route=node1")
        assertThat(file.read()).isEqualTo("JSESSIONID=abc; route=node1")
        // 写入只留一个密文文件，不残留 tmp
        assertThat(filesDir.listFiles().orEmpty().map { it.name }).containsExactly("session.enc")

        file.secureErase()
        assertThat(path.exists()).isFalse()
        assertThat(filesDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `擦除不存在的文件是空操作`() {
        val path = File(filesDir, "ghost.enc")

        SecureFile(path, cipher(), stringCodec).secureErase()

        assertThat(path.exists()).isFalse()
    }

    @Test
    fun `首次读盘清掉上次写失败残留的孤儿tmp`() {
        val path = File(filesDir, "session.enc")
        val orphan = File(filesDir, "session.enc.tmp.123456789")
        orphan.writeText("half-written ciphertext")

        val file = SecureFile(path, cipher(), stringCodec)
        assertThat(file.read()).isNull() // 文件不存在，但顺路清了 tmp

        assertThat(orphan.exists()).isFalse()
    }

    @Test
    fun `大于单chunk的文件擦除也能完整删除`() {
        val path = File(filesDir, "big.enc")
        // 4MiB：远超 secureErase 的 4KiB 覆盖块，逼出多轮 chunk 写入路径
        path.writeBytes(ByteArray(4 * 1024 * 1024) { 0x55 })
        assertThat(path.exists()).isTrue()

        SecureFile(path, cipher(), stringCodec).secureErase()

        assertThat(path.exists()).isFalse()
        // WAL 式伴生文件不存在，但要确认没有留下同名残留
        assertThat(File(filesDir, "big.enc.tmp").exists()).isFalse()
    }
}
