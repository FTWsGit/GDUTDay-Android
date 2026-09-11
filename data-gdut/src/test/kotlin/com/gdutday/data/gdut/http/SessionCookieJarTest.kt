package com.gdutday.data.gdut.http

import com.gdutday.core.model.StoredCookie
import com.google.common.truth.Truth.assertThat
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/**
 * [SessionCookieJar] + [StoredCookie] 的单元测试。
 *
 * 重点盯住**路径规范化**：authserver 下发的 `JSESSIONID` 是 `Path=/authserver`
 * （无尾斜杠），若路径形态不规范化，`Cookie.matches` 的前缀匹配可能在
 * `/authserver/login`、`/authserver/checkNeedCaptcha.htl` 上不命中，
 * 表现为"每次冷启动都要重新登录"，且被 `snapshot()`（不看 matches）掩盖，极难排查。
 */
class SessionCookieJarTest {

    private val authHost = "authserver.gdut.edu.cn"

    private fun cookie(path: String, value: String = "abc"): Cookie = Cookie.Builder()
        .name("JSESSIONID")
        .value(value)
        .hostOnlyDomain(authHost)
        .path(path)
        .build()

    // ---- normalizePath ----

    @Test
    fun `normalizePath 空路径归一为根路径`() {
        assertThat(StoredCookie.normalizePath("")).isEqualTo("/")
    }

    @Test
    fun `normalizePath 根路径保持不变`() {
        assertThat(StoredCookie.normalizePath("/")).isEqualTo("/")
    }

    @Test
    fun `normalizePath 缺前导斜杠时补齐`() {
        assertThat(StoredCookie.normalizePath("authserver")).isEqualTo("/authserver/")
    }

    @Test
    fun `normalizePath 缺尾斜杠时补齐`() {
        assertThat(StoredCookie.normalizePath("/authserver")).isEqualTo("/authserver/")
    }

    @Test
    fun `normalizePath 已规范化路径保持不变`() {
        assertThat(StoredCookie.normalizePath("/authserver/")).isEqualTo("/authserver/")
    }

    // ---- 实际匹配 ----

    @Test
    fun `无尾斜杠的 Path 能匹配子路径请求`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse("https://$authHost/".toHttpUrl(), listOf(cookie("/authserver")))

        val sent = jar.loadForRequest("https://$authHost/authserver/login".toHttpUrl())

        assertThat(sent.map { it.name }).contains("JSESSIONID")
    }

    @Test
    fun `根 Path 匹配所有子路径请求`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse("https://$authHost/".toHttpUrl(), listOf(cookie("/")))

        val sent = jar.loadForRequest("https://$authHost/authserver/login".toHttpUrl())

        assertThat(sent.map { it.name }).contains("JSESSIONID")
    }

    @Test
    fun `snapshot 还原后仍能匹配子路径`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse("https://$authHost/".toHttpUrl(), listOf(cookie("/authserver")))

        // 模拟冷启动：导出快照，用快照重建一个新的 jar
        val restored = SessionCookieJar(jar.snapshot())
        val sent = restored.loadForRequest("https://$authHost/authserver/checkNeedCaptcha.htl".toHttpUrl())

        assertThat(sent.map { it.name }).contains("JSESSIONID")
    }

    @Test
    fun `不同 Path 的同名 cookie 不会互相覆盖`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(
            "https://$authHost/".toHttpUrl(),
            listOf(cookie("/authserver", "first"), cookie("/personalInfo", "second")),
        )

        assertThat(jar.size).isEqualTo(2)
    }

    @Test
    fun `restore 后同名不同 Path 的 cookie 并存，且请求头不出现重复键`() {
        // M10 行为钉住：keyOf = name|domain|path，OkHttp 允许同 name+domain 不同 path
        // 的 cookie 并存。restore 含两把同名 cookie 的快照后，对同一 URL 两者都可能命中
        // —— 这里钉住"Cookie 请求头里同名键只出现一份"的底线；
        // 若未来改为合并/覆盖语义，应更新此测试并同步文档。
        val jar = SessionCookieJar()
        jar.saveFromResponse(
            "https://$authHost/".toHttpUrl(),
            listOf(cookie("/authserver", "first"), cookie("/", "second")),
        )
        val restored = SessionCookieJar(jar.snapshot())
        assertThat(restored.size).isEqualTo(2)

        val url = "https://$authHost/authserver/login".toHttpUrl()
        val header = restored.cookieHeaderValue(url)
        val names = header.split("; ").map { it.substringBefore('=') }
        // 请求头不允许同名键重复出现
        assertThat(names).containsNoDuplicates()
        // 两把 cookie 都还在 store 里，只是同一请求只发一份
        assertThat(restored.loadForRequest(url).map { it.name }.distinct()).hasSize(1)
    }
}
