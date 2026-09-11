package com.gdutday.core.datastore

import android.content.Context
import com.gdutday.core.model.Campus
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.UserType
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.StoredCookie
import com.gdutday.core.model.GdutSession
import com.gdutday.core.model.LoginMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.enums.enumEntries

/**
 * [GdutSession] 与 JSON 文本的互转。
 *
 * ## 为什么手写而不是 `@Serializable`
 *
 * `GdutSession` / `StoredCookie` 定义在 `data-gdut` 这个纯 JVM 契约模块里，
 * 本模块无权给它们加注解（会改动别的模块的契约）。而且本模块的
 * `build.gradle.kts` 也没有 apply kotlinx.serialization 编译器插件。
 * 直接用 JSON 树 API 手动映射，既不动别人的文件，也省掉一个编译器插件依赖。
 *
 * ## 解码的宽容策略
 *
 * 缺失的可选字段一律用默认值补齐，只有 `studentId`（会话的主体）缺失才判定整份数据
 * 不可用。这样即使将来给 [GdutSession] 加了新字段，旧文件也仍能读出来。
 * 解码过程中任何异常都返回 null，由 [SecureFile] 删除损坏文件。
 */
internal object SessionJson : FileCodec<GdutSession> {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun encode(value: GdutSession): String = buildJsonObject {
        put("cookies", buildJsonArray { value.cookies.forEach { add(encodeCookie(it)) } })
        put(
            "profile",
            buildJsonObject {
                put("studentId", value.profile.studentId)
                put("userType", value.profile.userType.name)
                put("name", value.profile.name)
                put("campus", value.profile.campus.name)
            },
        )
        put("method", value.method.name)
        put("obtainedAtMillis", value.obtainedAtMillis)
        put("diagnostics", value.diagnostics)
        put(
            "hosts",
            buildJsonObject {
                put("authserverBase", value.hosts.authserverBase)
                put("jxfwBase", value.hosts.jxfwBase)
            },
        )
    }.toString()

    override fun decode(raw: String): GdutSession? {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val profile = root["profile"]?.jsonObject
            val studentId = profile?.get("studentId")?.jsonPrimitive?.contentOrNull
            if (profile == null || studentId.isNullOrBlank()) {
                null
            } else {
                GdutSession(
                    cookies = root["cookies"]?.jsonArray?.mapNotNull { decodeCookie(it) }.orEmpty(),
                    profile = StudentProfile(
                        studentId = studentId,
                        // 存的是枚举名；认不出来时按学号重新推断，比固定给一个身份更合理。
                        userType = enumByName<UserType>(profile["userType"]?.jsonPrimitive?.contentOrNull)
                            ?: UserType.fromStudentId(studentId),
                        name = profile["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        campus = enumByName<Campus>(profile["campus"]?.jsonPrimitive?.contentOrNull)
                            ?: Campus.UNKNOWN,
                    ),
                    method = enumByName<LoginMethod>(root["method"]?.jsonPrimitive?.contentOrNull)
                        ?: LoginMethod.UNIFIED_AUTH,
                    obtainedAtMillis = root["obtainedAtMillis"]?.jsonPrimitive?.longOrNull
                        ?: System.currentTimeMillis(),
                    diagnostics = root["diagnostics"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    hosts = decodeHosts(root["hosts"]?.jsonObject),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeCookie(cookie: StoredCookie) = buildJsonObject {
        put("name", cookie.name)
        put("value", cookie.value)
        put("domain", cookie.domain)
        put("path", cookie.path)
        put("expiresAtMillis", cookie.expiresAtMillis)
        put("secure", cookie.secure)
        put("httpOnly", cookie.httpOnly)
        put("hostOnly", cookie.hostOnly)
    }

    private fun decodeCookie(element: JsonElement): StoredCookie? = try {
        val obj = element.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val value = obj["value"]?.jsonPrimitive?.contentOrNull
        val domain = obj["domain"]?.jsonPrimitive?.contentOrNull
        // 这三个字段缺失的 cookie 没有意义，跳过它而不是让整份会话失败。
        if (name.isNullOrBlank() || value == null || domain.isNullOrBlank()) {
            null
        } else {
            StoredCookie(
                name = name,
                value = value,
                domain = domain,
                path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "/",
                expiresAtMillis = obj["expiresAtMillis"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE,
                secure = obj["secure"]?.jsonPrimitive?.booleanOrNull ?: false,
                httpOnly = obj["httpOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                hostOnly = obj["hostOnly"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 主机地址是会话的一部分，但读不到时应回退到生产环境而不是丢弃整个会话：
     * 老版本可能没写这个字段，而回退到生产环境恰好是绝大多数用户的实际情形。
     */
    private fun decodeHosts(obj: kotlinx.serialization.json.JsonObject?): GdutHosts {
        if (obj == null) return GdutHosts.PRODUCTION
        val authserver = obj["authserverBase"]?.jsonPrimitive?.contentOrNull
        val jxfw = obj["jxfwBase"]?.jsonPrimitive?.contentOrNull
        // GdutHosts 的构造器会校验 URL；非法值会抛异常，被外层 catch 变成"整份会话损坏"。
        return if (authserver.isNullOrBlank() || jxfw.isNullOrBlank()) {
            GdutHosts.PRODUCTION
        } else {
            GdutHosts(authserverBase = authserver, jxfwBase = jxfw)
        }
    }

    private inline fun <reified T : Enum<T>> enumByName(raw: String?): T? =
        raw?.let { name -> enumEntries<T>().firstOrNull { it.name == name } }
}

/**
 * [SessionStore] 的默认实现：Keystore 加密的独立文件。
 *
 * 具体读写与擦除细节在 [EncryptedValueStore] / [SecureFile]，本类只负责
 * 把契约方法转发过去并固定文件名。默认注入 [AndroidKeystoreCipher]，
 * 测试与 androidTest 可以替换成假实现。
 */
public class FileSessionStore(
    context: Context,
    cipher: KeystoreCipher = AndroidKeystoreCipher(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionStore {

    private val store = EncryptedValueStore(
        file = SecureFile(
            file = File(context.applicationContext.filesDir, SESSION_FILE_NAME),
            cipher = cipher,
            codec = SessionJson,
        ),
        dispatcher = dispatcher,
    )

    override val session: Flow<GdutSession?> get() = store.flow

    override suspend fun current(): GdutSession? = store.current()

    override suspend fun save(session: GdutSession) = store.save(session)

    override suspend fun clear() = store.clear()

    public companion object {
        public const val SESSION_FILE_NAME: String = "gdutday_session.enc"
    }
}
