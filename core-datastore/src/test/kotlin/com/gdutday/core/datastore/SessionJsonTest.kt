package com.gdutday.core.datastore

import com.gdutday.core.model.Campus
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.UserType
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.StoredCookie
import com.gdutday.core.model.GdutSession
import com.gdutday.core.model.LoginMethod
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [SessionJson] 与 [CredentialsJson] 的映射测试。契约模块不能加 @Serializable，这里手写映射。 */
class SessionJsonTest {

    private fun session() = GdutSession(
        cookies = listOf(
            StoredCookie(
                name = "JSESSIONID",
                value = "abc123",
                domain = ".gdut.edu.cn",
                path = "/",
                expiresAtMillis = Long.MAX_VALUE,
                secure = true,
                httpOnly = true,
                hostOnly = false,
            ),
            StoredCookie(
                name = "route",
                value = "node1",
                domain = "jxfw.gdut.edu.cn",
                path = "/new",
                expiresAtMillis = 1_800_000_000_000L,
                secure = false,
                httpOnly = false,
                hostOnly = true,
            ),
        ),
        profile = StudentProfile(
            studentId = "3120012345",
            userType = UserType.UNDERGRADUATE,
            name = "张三",
            campus = Campus.LONGDONG,
        ),
        method = LoginMethod.JXFW_DIRECT,
        obtainedAtMillis = 1_700_000_000_000L,
        diagnostics = "sso -> jxfw",
        hosts = GdutHosts.PRODUCTION,
    )

    @Test
    fun `会话序列化后反序列化完全一致`() {
        val original = session()
        val decoded = SessionJson.decode(SessionJson.encode(original))
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `cookie 的 hostOnly 与 expiresAtMillis 被保留`() {
        val decoded = SessionJson.decode(SessionJson.encode(session()))!!
        val jxfw = decoded.cookies.first { it.name == "route" }
        assertThat(jxfw.hostOnly).isTrue()
        assertThat(jxfw.path).isEqualTo("/new")
        assertThat(jxfw.expiresAtMillis).isEqualTo(1_800_000_000_000L)
        // 会话级 cookie 的 MAX_VALUE 也必须原样保留，否则解码时会被当成已过期
        val jsession = decoded.cookies.first { it.name == "JSESSIONID" }
        assertThat(jsession.expiresAtMillis).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `自定义 hosts 被保留`() {
        val custom = session().copy(hosts = GdutHosts.forTestServer("http://localhost:8080"))
        val decoded = SessionJson.decode(SessionJson.encode(custom))!!
        assertThat(decoded.hosts).isEqualTo(custom.hosts)
    }

    @Test
    fun `无法解析的枚举回退而不会崩`() {
        // 手工构造一份 userType/method 都是垃圾的 JSON
        val raw = """
            {
              "cookies": [],
              "profile": { "studentId": "3120012345", "userType": "MARTIAN", "campus": "火星" },
              "method": "TELEPATHY",
              "obtainedAtMillis": 123
            }
        """.trimIndent()

        val decoded = SessionJson.decode(raw)!!

        // userType 按学号重新推断
        assertThat(decoded.profile.userType).isEqualTo(UserType.UNDERGRADUATE)
        assertThat(decoded.profile.campus).isEqualTo(Campus.UNKNOWN)
        assertThat(decoded.method).isEqualTo(LoginMethod.UNIFIED_AUTH)
    }

    @Test
    fun `缺字段的可选部分是默认值`() {
        val raw = """{ "cookies": [], "profile": { "studentId": "3120012345" } }"""
        val decoded = SessionJson.decode(raw)!!
        assertThat(decoded.cookies).isEmpty()
        assertThat(decoded.diagnostics).isEmpty()
        assertThat(decoded.hosts).isEqualTo(GdutHosts.PRODUCTION)
    }

    @Test
    fun `垃圾输入返回 null`() {
        assertThat(SessionJson.decode("not json")).isNull()
        assertThat(SessionJson.decode("{}")).isNull()
        assertThat(SessionJson.decode("""{"profile":{}}""")).isNull()
        // 非法 host 会触发 GdutHosts 构造器的 require，被 catch 成 null
        assertThat(
            SessionJson.decode(
                """{"profile":{"studentId":"3120012345"},"hosts":{"authserverBase":"not-a-url","jxfwBase":"also-bad"}}""",
            ),
        ).isNull()
    }

    @Test
    fun `缺少必要字段的 cookie 被跳过而不影响整份会话`() {
        val raw = """
            {
              "cookies": [
                { "name": "JSESSIONID", "value": "x", "domain": "jxfw.gdut.edu.cn" },
                { "name": "noValue", "domain": "x" },
                { "value": "noName", "domain": "x" }
              ],
              "profile": { "studentId": "3120012345" }
            }
        """.trimIndent()
        val decoded = SessionJson.decode(raw)!!
        assertThat(decoded.cookies).hasSize(1)
        assertThat(decoded.cookies.single().name).isEqualTo("JSESSIONID")
    }

    @Test
    fun `凭据序列化往返一致`() {
        val original = StoredCredentials(
            username = "3120012345",
            password = "p@ssw0rd",
            method = LoginMethod.UNIFIED_AUTH,
            rememberPassword = true,
            savedAt = 1_700_000_000_000L,
        )
        val decoded = CredentialsJson.decode(CredentialsJson.encode(original))
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `凭据缺少账号或密码时返回 null`() {
        assertThat(CredentialsJson.decode("{}")).isNull()
        assertThat(CredentialsJson.decode("""{"username":"x"}""")).isNull()
        assertThat(CredentialsJson.decode("garbage")).isNull()
    }
}
