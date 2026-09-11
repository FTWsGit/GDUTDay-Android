package com.gdutday.core.datastore

import android.content.Context
import com.gdutday.core.model.LoginMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.enums.enumEntries

/**
 * [StoredCredentials] 与 JSON 文本的互转。
 *
 * 与 [SessionJson] 同样的理由手写映射（契约模块不能加 `@Serializable`，本模块也没挂
 * 序列化编译器插件）。密码在这个类里是明文，**只应短暂存在于内存**，落盘前由
 * [SecureFile] 交给 [KeystoreCipher] 加密；本对象不做任何日志输出。
 */
internal object CredentialsJson : FileCodec<StoredCredentials> {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun encode(value: StoredCredentials): String = buildJsonObject {
        put("username", value.username)
        put("password", value.password)
        put("method", value.method.name)
        put("rememberPassword", value.rememberPassword)
        put("savedAt", value.savedAt)
    }.toString()

    override fun decode(raw: String): StoredCredentials? = try {
        val obj = json.parseToJsonElement(raw).jsonObject
        val username = obj["username"]?.jsonPrimitive?.contentOrNull
        val password = obj["password"]?.jsonPrimitive?.contentOrNull
        // 账号和密码缺一不可，否则记住的凭据没有意义。
        if (username.isNullOrBlank() || password == null) {
            null
        } else {
            StoredCredentials(
                username = username,
                password = password,
                method = enumByName<LoginMethod>(obj["method"]?.jsonPrimitive?.contentOrNull)
                    ?: LoginMethod.UNIFIED_AUTH,
                rememberPassword = obj["rememberPassword"]?.jsonPrimitive?.booleanOrNull ?: true,
                savedAt = obj["savedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            )
        }
    } catch (e: Exception) {
        null
    }

    private inline fun <reified T : Enum<T>> enumByName(raw: String?): T? =
        raw?.let { name -> enumEntries<T>().firstOrNull { it.name == name } }
}

/**
 * [CredentialStore] 的默认实现，文件名 `gdutday_credentials.enc`。
 *
 * 与 [FileSessionStore] 完全同构，只是编解码对象不同。刻意不复用同一个类，
 * 是因为两者的失效语义不同：会话失效应当静默重登，而凭据失效（密码错/被改）
 * 需要引导用户重新输入；将来加日志或迁移逻辑时分开更清晰。
 */
public class FileCredentialStore(
    context: Context,
    cipher: KeystoreCipher = AndroidKeystoreCipher(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {

    private val store = EncryptedValueStore(
        file = SecureFile(
            file = File(context.applicationContext.filesDir, CREDENTIALS_FILE_NAME),
            cipher = cipher,
            codec = CredentialsJson,
        ),
        dispatcher = dispatcher,
    )

    override val credentials: Flow<StoredCredentials?> get() = store.flow

    override suspend fun current(): StoredCredentials? = store.current()

    override suspend fun save(credentials: StoredCredentials) = store.save(credentials)

    override suspend fun clear() = store.clear()

    public companion object {
        public const val CREDENTIALS_FILE_NAME: String = "gdutday_credentials.enc"
    }
}
