package com.gdutday.core.datastore

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/** 把领域对象与落盘的 JSON 文本互相转换。 */
internal interface FileCodec<T> {
    fun encode(value: T): String
    fun decode(raw: String): T?
}

/**
 * 一个"加密 + 安全擦除"的本地文件。
 *
 * ## 为什么读失败时要顺手删文件
 *
 * 解密失败只有两种可能：密钥换了（恢复出厂 / 换机）或文件损坏。两种情况下这份密文
 * 都永远解不开了，留着只会让每次冷启动都重复一次注定失败的解密。删掉它，下一次
 * 写入就是干净状态，用户看到的也是明确的"请重新登录"。
 *
 * ## 为什么删除要"覆盖"
 *
 * cookie 等同于账号：`JSESSIONID` 泄露后无需密码即可登录，而用户不会收到任何提示。
 * 普通 `File.delete()` 只摘除目录项，数据块仍留在闪存上，在 root、取证工具、
 * 未加密备份下都可能被恢复。写入随机字节后 `fsync` 再删除，能显著提高恢复门槛。
 * 这**不是**密码学意义上的可靠擦除（闪存磨损均衡无法保证原地覆盖），
 * 但比不覆盖强得多，且成本只有几毫秒。
 */
internal class SecureFile<T>(
    private val file: File,
    private val cipher: KeystoreCipher,
    private val codec: FileCodec<T>,
) {

    /** 读并解密、解码。任何一步失败都返回 null（必要时删除损坏文件）。 */
    fun read(): T? {
        val raw = try {
            if (!file.exists()) return null
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        if (raw.isBlank()) return null

        val plain = cipher.decryptFromString(raw)
        if (plain == null) {
            // 密钥失效 / 文件被篡改：留下也没用，清掉，避免每次冷启动重复失败。
            deleteQuietly()
            return null
        }

        val value = codec.decode(plain)
        if (value == null) {
            // 能解密但 JSON 结构不合法（多半是写入中断），同样视为损坏。
            deleteQuietly()
        }
        return value
    }

    /** 加密并原子写入。加密失败会抛异常 —— 这是"数据没存上"，不能静默。 */
    fun write(value: T) {
        val encrypted = cipher.encryptToString(codec.encode(value))
            ?: throw IllegalStateException("加密失败，无法写入 ${file.name}")

        file.parentFile?.mkdirs()
        // 先写临时文件再改名：避免写到一半进程被杀，留下半截密文让下次解密失败。
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encrypted, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 少数文件系统 rename 会失败，退化为直接覆盖写。
            file.writeText(encrypted, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /**
     * 随机覆盖后删除。文件不存在时是空操作。
     *
     * 覆盖失败（例如文件被占用）也会继续删除：至少等价于普通删除，不能让"退出登录"
     * 因为擦除失败而卡住。
     */
    fun secureErase() {
        if (!file.exists()) return
        val length = file.length()
        try {
            RandomAccessFile(file, "rws").use { raf ->
                val chunk = ByteArray(OVERWRITE_CHUNK_BYTES)
                var remaining = length
                raf.seek(0)
                while (remaining > 0) {
                    secureRandom.nextBytes(chunk)
                    val count = minOf(remaining, chunk.size.toLong()).toInt()
                    raf.write(chunk, 0, count)
                    remaining -= count
                }
                raf.fd.sync()
            }
        } catch (e: Exception) {
            // 忽略，继续删除。
        }
        deleteQuietly()
    }

    private fun deleteQuietly() {
        try {
            file.delete()
        } catch (e: Exception) {
            // 忽略。
        }
    }

    private companion object {
        const val OVERWRITE_CHUNK_BYTES: Int = 4 * 1024
        val secureRandom: SecureRandom = SecureRandom()
    }
}

/**
 * 把 [SecureFile] 包装成"懒加载 + 内存缓存"的值。
 *
 * ## 为什么不在构造时读盘
 *
 * 本项目的头号目标是冷启动快。`SessionStore` / `CredentialStore` 往往在
 * Application 初始化时就被创建（供 DI 与 WorkManager 使用），如果构造即读盘 + 解密，
 * 就把一次文件 IO + Keystore 解密塞进了冷启动的关键路径。改为首次 [flow] 订阅或
 * [current] 调用时才读，未登录用户和只用本地课表的用户完全不会付出这个代价。
 *
 * ## 线程安全
 *
 * [Mutex] 保证"读盘"只发生一次，且不会与 [save]/[clear] 交错；
 * 快速路径用 [loaded] 标志避免每次都抢锁。缓存用 [MutableStateFlow]，
 * 订阅者天然拿到最新值。
 */
internal class EncryptedValueStore<T : Any>(
    private val file: SecureFile<T>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val state = MutableStateFlow<T?>(null)
    private val lock = Mutex()

    @Volatile
    private var loaded = false

    val flow: Flow<T?> = flow {
        ensureLoaded()
        emitAll(state)
    }

    suspend fun current(): T? {
        ensureLoaded()
        return state.value
    }

    suspend fun save(value: T) {
        lock.withLock {
            withContext(dispatcher) { file.write(value) }
            loaded = true
            state.value = value
        }
    }

    suspend fun clear() {
        lock.withLock {
            withContext(dispatcher) { file.secureErase() }
            loaded = true
            state.value = null
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        lock.withLock {
            if (loaded) return
            val value = withContext(dispatcher) { file.read() }
            state.value = value
            loaded = true
        }
    }
}
