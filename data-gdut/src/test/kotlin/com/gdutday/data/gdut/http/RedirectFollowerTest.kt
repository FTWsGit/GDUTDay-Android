package com.gdutday.data.gdut.http

import com.gdutday.core.model.GdutException
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [RedirectFollower] 的单元测试。
 *
 * 手动跟随重定向是登录流程里最容易出错的一环，而且**错误表现极其隐蔽**：
 * 少跟一跳会停在半路（会话没建起来），多跟会撞死循环，方法降级搞错会让 POST 变成 GET
 * 从而丢掉表单体。这些都在这里逐条钉住。
 */
class RedirectFollowerTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val recorded = mutableListOf<RecordedRequest>()

    /** 每个测试自己装填的路由表：path → 响应。 */
    private val routes = LinkedHashMap<String, () -> MockResponse>()

    private fun base() = "http://localhost:${server.port}"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        routes.clear()
        recorded.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                val path = request.path?.substringBefore('?') ?: "/"
                return routes[path]?.invoke() ?: MockResponse().setResponseCode(404).setBody("no route: $path")
            }
        }
        client = OkHttpClient.Builder()
            // 关键：关掉自动重定向，否则测的就是 OkHttp 而不是我们的实现
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun follower(maxHops: Int = 10) = RedirectFollower(client, maxHops)

    private fun get(path: String) = Request.Builder().url(base() + path).get().build()

    private fun redirect(location: String) = MockResponse().setResponseCode(302)
        .setHeader("Location", location)
        .setHeader("Content-Length", "0")

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/plain")
        .setBody(body)

    // ------------------------------------------------------------------ 基本行为

    @Test
    fun `非重定向响应直接返回，链路只有一跳`() {
        routes["/a"] = { ok("hello") }
        val r = follower().follow(get("/a"))
        r.response.use {
            assertThat(r.finalStatusCode).isEqualTo(200)
            assertThat(it.body.string()).isEqualTo("hello")
        }
        assertThat(r.hopCount).isEqualTo(1)
        assertThat(r.loopDetected).isFalse()
    }

    @Test
    fun `跟随多跳直到 200`() {
        routes["/a"] = { redirect("/b") }
        routes["/b"] = { redirect("/c") }
        routes["/c"] = { ok("end") }

        val r = follower().follow(get("/a"))
        r.response.use { assertThat(it.body.string()).isEqualTo("end") }
        assertThat(r.hopCount).isEqualTo(3)
        assertThat(r.chain.map { it.statusCode }).containsExactly(302, 302, 200).inOrder()
        assertThat(r.finalUrl).endsWith("/c")
    }

    @Test
    fun `相对 Location 按上一跳 URL 解析`() {
        routes["/x/y"] = { redirect("../z") }   // 相对路径，且带 ..
        routes["/z"] = { ok("resolved") }

        val r = follower().follow(get("/x/y"))
        r.response.use { assertThat(it.body.string()).isEqualTo("resolved") }
        assertThat(r.finalUrl).endsWith("/z")
    }

    @Test
    fun `绝对 Location 也能处理`() {
        routes["/a"] = { redirect("${base()}/b") }
        routes["/b"] = { ok("abs") }
        val r = follower().follow(get("/a"))
        r.response.use { assertThat(it.body.string()).isEqualTo("abs") }
    }

    // ------------------------------------------------------------------ 方法降级

    @Test
    fun `302 会把 POST 降级成 GET 并丢弃 body - 与浏览器一致`() {
        routes["/post"] = { redirect("/after") }
        routes["/after"] = { ok("done") }

        val post = Request.Builder()
            .url(base() + "/post")
            .post("account=1&pwd=2".toRequestBody(FormFields.FORM_URL_ENCODED))
            .build()

        val r = follower().follow(post)
        r.response.use { assertThat(it.body.string()).isEqualTo("done") }

        val after = recorded.first { it.path == "/after" }
        assertThat(after.method).isEqualTo("GET")
        // 表单体不该被带过去
        assertThat(after.body.size).isEqualTo(0)
        // 浏览器导航头应该补上
        assertThat(after.getHeader("sec-fetch-dest")).isEqualTo("document")
        assertThat(after.getHeader("sec-fetch-mode")).isEqualTo("navigate")
    }

    @Test
    fun `307 保留原方法和 body`() {
        routes["/post"] = {
            MockResponse().setResponseCode(307).setHeader("Location", "/after").setHeader("Content-Length", "0")
        }
        routes["/after"] = { ok("kept") }

        val post = Request.Builder()
            .url(base() + "/post")
            .post("a=1".toRequestBody(FormFields.FORM_URL_ENCODED))
            .build()

        val r = follower().follow(post)
        r.response.use { assertThat(it.body.string()).isEqualTo("kept") }

        val after = recorded.first { it.path == "/after" }
        assertThat(after.method).isEqualTo("POST")
        assertThat(after.body.readUtf8()).isEqualTo("a=1")
    }

    // ------------------------------------------------------------------ Referer / sec-fetch-site

    @Test
    fun `默认用上一跳 URL 作为 Referer`() {
        routes["/a"] = { redirect("/b") }
        routes["/b"] = { ok("x") }
        follower().follow(get("/a"))
        assertThat(recorded.first { it.path == "/b" }.getHeader("Referer")).endsWith("/a")
    }

    @Test
    fun `显式传入 refererForHops 时每一跳都用它`() {
        // 旧 Java 后端就是在重定向循环里写死了一个 Referer，
        // 因为统一认证的 nginx 对 Referer 敏感。
        routes["/a"] = { redirect("/b") }
        routes["/b"] = { redirect("/c") }
        routes["/c"] = { ok("x") }
        follower().follow(get("/a"), refererForHops = "https://fixed.example/login")

        assertThat(recorded.first { it.path == "/b" }.getHeader("Referer"))
            .isEqualTo("https://fixed.example/login")
        assertThat(recorded.first { it.path == "/c" }.getHeader("Referer"))
            .isEqualTo("https://fixed.example/login")
    }

    @Test
    fun `sec-fetch-site 会区分 same-origin 与 same-site`() {
        // MockWebServer 只有一个 host，所以全程 same-origin。
        // isSameSite 的逻辑单独测（见下）。
        routes["/a"] = { redirect("/b") }
        routes["/b"] = { ok("x") }
        follower().follow(get("/a"))
        assertThat(recorded.first { it.path == "/b" }.getHeader("sec-fetch-site")).isEqualTo("same-origin")
    }

    @Test
    fun `isSameSite 认得同一可注册域下的兄弟子域`() {
        assertThat(RedirectFollower.isSameSite("authserver.gdut.edu.cn", "jxfw.gdut.edu.cn")).isTrue()
        assertThat(RedirectFollower.isSameSite("jxfw.gdut.edu.cn", "jxfw.gdut.edu.cn")).isTrue()
        assertThat(RedirectFollower.isSameSite("authserver.gdut.edu.cn", "example.com")).isFalse()
    }

    // ------------------------------------------------------------------ 循环与上限

    @Test
    fun `跳回同一个 URL 时判定为循环并停下，不抛异常`() {
        // 这正是旧 Java 后端注释里描述的真实情况：
        // "最后会无限重定向，因为 nginx 一直永远返回 302"
        routes["/loop"] = { redirect("/loop") }

        val r = follower(maxHops = 50).follow(get("/loop"))
        r.response.use {
            assertThat(r.loopDetected).isTrue()
            // 停下来之后 response 必须仍然可用（不能被 close 掉）
            assertThat(r.finalStatusCode).isEqualTo(302)
        }
        assertThat(r.chainToString()).contains("[loop]")
        // 只访问了两次：第一次进入循环，第二次为了拿到可用的最终响应
        assertThat(recorded.count { it.path == "/loop" }).isEqualTo(2)
    }

    @Test
    fun `两个地址互相跳转也算循环`() {
        routes["/a"] = { redirect("/b") }
        routes["/b"] = { redirect("/a") }
        val r = follower(maxHops = 50).follow(get("/a"))
        r.response.use { assertThat(r.loopDetected).isTrue() }
    }

    @Test
    fun `每跳 URL 都不同时才会撞上限并抛 TooManyRedirects`() {
        // 递增的 query 让每个 URL 都不一样，绕过去重逻辑
        for (i in 0..20) routes["/hop$i"] = { redirect("/hop${i + 1}") }

        val e = assertThrows(GdutException.TooManyRedirects::class.java) {
            follower(maxHops = 5).follow(get("/hop0"))
        }
        assertThat(e.maxHops).isEqualTo(5)
        assertThat(e.chain.size).isGreaterThan(4)
        assertThat(e.detail).contains("/hop")
        assertThat(e.userMessage).contains("跳转次数过多")
    }

    @Test
    fun `3xx 但没有 Location 头时停下并把响应交给调用方`() {
        routes["/weird"] = { MockResponse().setResponseCode(302).setHeader("Content-Length", "0") }
        val r = follower().follow(get("/weird"))
        r.response.use {
            assertThat(r.finalStatusCode).isEqualTo(302)
            assertThat(r.loopDetected).isFalse()
        }
    }

    @Test
    fun `300 Multiple Choices 也会被跟随 - 用 OkHttp 自带的 isRedirect 判定`() {
        routes["/multi"] = {
            MockResponse().setResponseCode(300).setHeader("Location", "/chosen").setHeader("Content-Length", "0")
        }
        routes["/chosen"] = { ok("picked") }
        val r = follower().follow(get("/multi"))
        r.response.use { assertThat(it.body.string()).isEqualTo("picked") }
    }

    // ------------------------------------------------------------------ 结果对象

    @Test
    fun `visited 能识别链路中途出现过的主机`() {
        // 登录成功的判据之一就是"链路里出现过 jxfw"，即使最终又跳走了
        routes["/a"] = { redirect("/b") }
        routes["/b"] = { ok("x") }
        val r = follower().follow(get("/a"))
        r.response.use {
            assertThat(r.visited("localhost")).isTrue()
            assertThat(r.visited("nowhere.example")).isFalse()
            assertThat(r.landedOn("localhost")).isTrue()
        }
    }

    @Test
    fun `chainToString 给出可读的跳转链`() {
        routes["/a"] = { redirect("/b") }
        routes["/b"] = { ok("x") }
        val r = follower().follow(get("/a"))
        r.response.use {
            assertThat(r.chainToString()).contains("/a(302)")
            assertThat(r.chainToString()).contains("/b(200)")
            assertThat(r.chainToString()).contains(" -> ")
        }
    }

    @Test
    fun `怪异的 Location 不会让跟随器崩掉，而是把最终响应交给调用方`() {
        // `HttpUrl.resolve` 相当宽松：`ht!tp://[invalid` 会被当成相对路径解析，
        // 于是请求打到一个不存在的路径上拿到 404。
        // 关键不是"要抛什么异常"，而是**不能抛出未归一的异常把上层搞崩** ——
        // 调用方拿到一个 404 的 RedirectResult，会走它自己的错误处理。
        routes["/bad"] = { redirect("ht!tp://[invalid") }
        val r = follower().follow(get("/bad"))
        r.response.use {
            assertThat(r.finalStatusCode).isEqualTo(404)
            assertThat(r.loopDetected).isFalse()
        }
        assertThat(r.chainToString()).contains("/bad(302)")
    }

    @Test
    fun `Location 指向不存在的路径时把 404 原样返回`() {
        routes["/a"] = { redirect("/nowhere") }
        val r = follower().follow(get("/a"))
        r.response.use { assertThat(r.finalStatusCode).isEqualTo(404) }
        assertThat(r.hopCount).isEqualTo(2)
    }
}
