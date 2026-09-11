package com.gdutday.data.gdut.http

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
}
