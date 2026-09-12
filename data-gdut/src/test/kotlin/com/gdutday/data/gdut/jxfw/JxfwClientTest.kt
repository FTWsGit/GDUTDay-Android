package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.LoginMethod
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.Term
import com.gdutday.data.gdut.session.GdutSession
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
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
 * [JxfwClient] **编排逻辑**的端到端测试，跑在 MockWebServer 上。
 *
 * 纯解析器（`JxfwScheduleParser` / `JxfwGradeParser`）由 [JxfwScheduleParserTest]、
 * [JxfwParserTest] 覆盖；这里测的是它们**之间的编排** —— 解析器单测看不到的那部分：
 *
 * - AUTO 策略的回退顺序（xsAllKbList 失败 → getDataList），以及"会话失效不该被回退吞掉"；
 * - `getDataList` 的分页循环（旧 Java 后端固定 rows=300 不翻页，课多会被静默截断）；
 * - 「劳动教育」兜底重查（教务处 bug，`xnxqdm=""` 时成绩为空，要带长码再查一次）。
 *
 * 沿用 `AuthServerClientTest` 的做法：一个 [Dispatcher] 按 **path** 路由
 * （`GdutHosts.forTestServer` 把两个域都指向同一个端口），并记录每个请求，
 * 供测试断言请求顺序与表单参数。
 */
class JxfwClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var hosts: GdutHosts

    /** 服务端收到的全部请求，按顺序。用于断言请求顺序与 POST body。 */
    private val recorded = mutableListOf<CapturedRequest>()

    /**
     * 请求 + 已读出的 body 文本。
     *
     * ⚠ [RecordedRequest.body] 是只能读一次的 buffer，必须在 dispatcher 里**当场**读出来，
     * 否则测试侧再读就只剩空串（`AuthServerClientTest` 的 dispatcher 不读 body 所以没踩坑，
     * 这里要按表单参数路由，必须先读）。
     */
    private class CapturedRequest(val request: RecordedRequest, val body: String)

    /** 每个测试可以覆盖的剧本。 */
    private lateinit var script: Script

    /** 路由用的 path 常量，与 [GdutHosts] 的端点定义一致。 */
    private companion object {
        const val ALL_KB_LIST_PATH = "/xsgrkbcx!xsAllKbList.action"
        const val SCHEDULE_DATA_LIST_PATH = "/xsgrkbcx!getDataList.action"
        const val SCORE_DATA_LIST_PATH = "/xskccjxx!getDataList.action"
    }

    /** 一次测试会触发的接口响应。默认全部成功，各测试只改自己关心的那一环。 */
    private inner class Script(
        /** 课表接口 A（xsAllKbList，HTML）。默认返回一门课（AUTO 策略可直接命中）。 */
        var allKbList: () -> MockResponse = { ok(allKbListHtml(allKbListRow())) },
        /** 课表接口 B（getDataList，JSON 分页）。 */
        var scheduleDataList: (page: Int) -> MockResponse = { ok(dataListBody(rows = emptyList(), total = 0)) },
        /** 成绩接口（JSON 分页）。 */
        var scoreDataList: (page: Int) -> MockResponse = { ok(dataListBody(rows = emptyList(), total = 0)) },
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        hosts = GdutHosts.forTestServer(baseUrl())
        script = Script()
        recorded.clear()   // 防跨测试串扰：recorded 是类字段，不清会残留上一个用例的请求
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.path ?: return MockResponse().setResponseCode(404)
        val body = request.body.readUtf8()   // 当场读出，后面测试侧只能用 CapturedRequest.body
        recorded += CapturedRequest(request, body)
        return when {
            path.startsWith(ALL_KB_LIST_PATH) -> script.allKbList()
            path.startsWith(SCHEDULE_DATA_LIST_PATH) ->
                script.scheduleDataList(parseForm(body)["page"]?.toIntOrNull() ?: -1)
            path.startsWith(SCORE_DATA_LIST_PATH) ->
                script.scoreDataList(parseForm(body)["page"]?.toIntOrNull() ?: -1)
            else -> MockResponse().setResponseCode(404).setBody("unexpected: ${request.method} $path")
        }
    }

    private fun baseUrl(): String = "http://localhost:${server.port}"

    // ================================================================== 辅助

    private fun newClient(config: JxfwConfig = JxfwConfig(hosts = hosts)): JxfwClient =
        JxfwClient(client, fakeSession(), config)

    private fun fakeSession() = GdutSession(
        cookies = emptyList(),
        profile = StudentProfile(studentId = "3120012345"),
        method = LoginMethod.UNIFIED_AUTH,
        hosts = hosts,
    )

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/html;charset=UTF-8")
        .setBody(body)

    /** EasyUI DataGrid 风格的分页响应体。 */
    private fun dataListBody(rows: List<String>, total: Int): String =
        """{"total":$total,"rows":[${rows.joinToString(",")}]}"""

    /** 构造一条 `getDataList` 课表行（按周炸开的粒度，每行一个周次）。 */
    private fun scheduleRow(name: String = "高等数学", week: Int, day: Int = 1): String =
        """{"kcmc":"$name","jxcdmc":"教5-301","teaxms":"张三","xq":"$day","zc":"$week","jcdm":"0102"}"""

    /** 接口 A 的 HTML 响应：课表数据在内嵌 JS 数组里。 */
    private fun allKbListHtml(vararg rows: String): String = """
        <html><body><script>var kbxx = [${rows.joinToString(",")}];</script></body></html>
    """.trimIndent()

    /** 构造一条 `kbxx` 行（接口 A 的粒度：节次逗号分隔、周次聚合）。 */
    private fun allKbListRow(name: String = "高等数学", sections: String = "1,2", weeks: String = "1,2,3"): String =
        """{"kcmc":"$name","kcbh":"1001","jxbmc":"${name}1班","kcrwdm":"T1","jcdm2":"$sections","zcs":"$weeks","xq":"1","jxcdmcs":"教5-301","teaxms":"张三"}"""

    private fun gradeRow(
        name: String,
        termCode: String,
        score: String,
        gpa: String = "",
    ): String =
        """{"kcmc":"$name","xnxqmc":"2025-2026学年第一学期","xnxqdm":"$termCode","zcj":"$score","cjjd":"$gpa","xf":"0.5"}"""

    /** 解析 `a=1&b=2` 形式的表单，**保留空值的键**。 */
    private fun parseForm(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            out[java.net.URLDecoder.decode(k, Charsets.UTF_8)] = java.net.URLDecoder.decode(v, Charsets.UTF_8)
        }
        return out
    }

    private val term = Term(2025, 1)

    // ================================================================== 课表：AUTO 策略

    @Test
    fun `AUTO 策略下 xsAllKbList 可用时直接命中且不再请求 getDataList`() {
        val result = newClient().fetchSchedule(term)

        assertThat(result.endpoint).isEqualTo(ScheduleEndpoint.ALL_KB_LIST)
        assertThat(result.pagesFetched).isEqualTo(1)
        assertThat(result.rows).hasSize(1)
        assertThat(result.courses).hasSize(1)
        assertThat(result.courses[0].weeks).containsExactly(1, 2, 3)

        // 回退接口一次都不该碰
        assertThat(recorded.none { it.request.path?.startsWith(SCHEDULE_DATA_LIST_PATH) == true }).isTrue()
        // 接口 A 必须带专用 Referer（F# 源码里标了"重要！需要记录"）
        val req = recorded.single { it.request.path?.startsWith(ALL_KB_LIST_PATH) == true }
        assertThat(req.request.getHeader("Referer")).isEqualTo("${baseUrl()}/xsgrkbcx!getXsgrbkList.action")
    }

    @Test
    fun `AUTO 策略先试 xsAllKbList 失败后回退 getDataList`() {
        // xsAllKbList 返回 500（接口下线 / 改版的模拟）
        script.allKbList = { MockResponse().setResponseCode(500) }
        // getDataList 返回一页成功数据
        script.scheduleDataList = {
            ok(dataListBody(rows = listOf(scheduleRow(week = 3)), total = 1))
        }

        val result = newClient().fetchSchedule(term)

        assertThat(result.endpoint).isEqualTo(ScheduleEndpoint.DATA_LIST)
        assertThat(result.pagesFetched).isEqualTo(1)
        assertThat(result.courses).hasSize(1)
        assertThat(result.courses[0].name).isEqualTo("高等数学")

        // 请求顺序：先 A 后 B
        val paths = recorded.map { it.request.path?.substringBefore('?') }
        assertThat(paths).containsExactly(ALL_KB_LIST_PATH, SCHEDULE_DATA_LIST_PATH).inOrder()
    }

    @Test
    fun `AUTO 策略下 xsAllKbList 返回空数据时也回退 getDataList`() {
        // 接口 A"活着"但解析不出任何行 —— 回退语义同样要覆盖
        script.allKbList = { ok(allKbListHtml()) }   // var kbxx = [];
        script.scheduleDataList = {
            ok(dataListBody(rows = listOf(scheduleRow(week = 3)), total = 1))
        }

        val result = newClient().fetchSchedule(term)

        assertThat(result.endpoint).isEqualTo(ScheduleEndpoint.DATA_LIST)
        assertThat(result.courses).hasSize(1)
    }

    @Test
    fun `两个课表接口都失败时抛 Parse 并同时附上两次失败原因`() {
        script.allKbList = { MockResponse().setResponseCode(500) }
        script.scheduleDataList = { MockResponse().setResponseCode(503) }

        val e = assertThrows(GdutException.Parse::class.java) {
            newClient().fetchSchedule(term)
        }
        // 诊断信息必须能同时看到两个接口各自的失败原因，否则排查改版问题只能靠猜
        assertThat(e.detail).contains("[xsAllKbList]")
        assertThat(e.detail).contains("[getDataList]")
    }

    @Test
    fun `会话失效时不回退直接抛 SessionExpired`() {
        // 被导回统一认证登录页 = 会话失效。换接口也一样失效，
        // 此时回退是纯浪费（还会对失效会话再发一次请求）。
        script.allKbList = {
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://authserver.gdut.edu.cn/authserver/login?service=x")
        }

        val e = assertThrows(GdutException.SessionExpired::class.java) {
            newClient().fetchSchedule(term)
        }
        assertThat(e.shouldRetryLogin).isTrue()
        assertThat(recorded.none { it.request.path?.startsWith(SCHEDULE_DATA_LIST_PATH) == true }).isTrue()
    }

    @Test
    fun `强制 DATA_LIST 策略时不请求 xsAllKbList`() {
        val config = JxfwConfig(hosts = hosts, scheduleEndpoint = ScheduleEndpoint.DATA_LIST)
        script.scheduleDataList = {
            ok(dataListBody(rows = listOf(scheduleRow(week = 3)), total = 1))
        }

        val result = newClient(config).fetchSchedule(term)

        assertThat(result.endpoint).isEqualTo(ScheduleEndpoint.DATA_LIST)
        assertThat(recorded.none { it.request.path?.startsWith(ALL_KB_LIST_PATH) == true }).isTrue()
    }

    // ================================================================== 课表：分页

    @Test
    fun `getDataList 分页取满 total 声明的全部数据`() {
        // rowsPerPage=3，服务端声明 total=5：第 1 页满 3 条（要继续翻），第 2 页 2 条（不满 → 到底）
        // 显式走 DATA_LIST：AUTO 下接口 A 默认成功会直接命中，根本不进分页逻辑
        val config = JxfwConfig(hosts = hosts, rowsPerPage = 3, scheduleEndpoint = ScheduleEndpoint.DATA_LIST)
        script.scheduleDataList = { page ->
            when (page) {
                1 -> ok(dataListBody(rows = listOf(scheduleRow(week = 1), scheduleRow(week = 2), scheduleRow(week = 3)), total = 5))
                2 -> ok(dataListBody(rows = listOf(scheduleRow(week = 4), scheduleRow(week = 5)), total = 5))
                else -> throw IllegalStateException("不该请求第 $page 页")
            }
        }

        val result = newClient(config).fetchSchedule(term)

        assertThat(result.endpoint).isEqualTo(ScheduleEndpoint.DATA_LIST)
        assertThat(result.pagesFetched).isEqualTo(2)
        // 按周炸开的 5 行应全部取到，并聚合成一门 weeks={1..5} 的课
        assertThat(result.rows).hasSize(5)
        assertThat(result.courses).hasSize(1)
        assertThat(result.courses[0].weeks).containsExactly(1, 2, 3, 4, 5)

        // 第二页请求的 page 参数必须是 2
        val second = recorded.last { it.request.path?.startsWith(SCHEDULE_DATA_LIST_PATH) == true }
        assertThat(parseForm(second.body)["page"]).isEqualTo("2")
    }

    @Test
    fun `getDataList 分页请求的表单参数与旧后端一致`() {
        // 同上：显式 DATA_LIST，避免 AUTO 命中接口 A 导致这里拿不到 B 的表单
        val config = JxfwConfig(hosts = hosts, scheduleEndpoint = ScheduleEndpoint.DATA_LIST)
        script.scheduleDataList = {
            ok(dataListBody(rows = listOf(scheduleRow(week = 3)), total = 1))
        }

        newClient(config).fetchSchedule(term)

        val form = parseForm(recorded.single { it.request.path?.startsWith(SCHEDULE_DATA_LIST_PATH) == true }.body)
        // ⚠ xnxqdm 用长码；zc 必须存在且为空（表示"不按周筛选"）
        assertThat(form["xnxqdm"]).isEqualTo("202501")
        assertThat(form).containsEntry("zc", "")
        assertThat(form["rows"]).isEqualTo("200")
        assertThat(form["sort"]).isEqualTo("kxh")
        assertThat(form["order"]).isEqualTo("asc")
    }

    // ================================================================== 成绩：劳动教育兜底

    @Test
    fun `劳动教育成绩为空时触发兜底重查并合并补回`() {
        // 第一次（xnxqdm="" 查全部学期）：劳动教育 zcj 为空 —— 教务处的 bug
        // 第二次（带 xnxqdm=202501 重查）：劳动教育有值 —— 由第一次的 needsLaborEducationPatch 触发
        var firstCall = true
        script.scoreDataList = { page ->
            when {
                page == 1 && firstCall -> {
                    firstCall = false
                    ok(dataListBody(rows = listOf(gradeRow("劳动教育", "202501", score = "")), total = 1))
                }
                page == 1 -> ok(dataListBody(rows = listOf(gradeRow("劳动教育", "202501", score = "85", gpa = "3.5")), total = 1))
                else -> throw IllegalStateException("不该请求第 $page 页")
            }
        }

        val result = newClient().fetchGrades(term = null)

        // 兜底重查确实发了一次（共两次请求）
        assertThat(recorded.count { it.request.path?.startsWith(SCORE_DATA_LIST_PATH) == true }).isEqualTo(2)
        assertThat(result.grades).hasSize(1)
        assertThat(result.grades.first().scoreText).isEqualTo("85")
        assertThat(result.grades.first().gpa).isEqualTo(3.5)
        // 补丁已消费，不该再标记为需要兜底
        assertThat(result.needsLaborEducationPatch).isEmpty()
    }

    @Test
    fun `查全部学期时成绩表单的 xnxqdm 传空串且带 jhlxdm`() {
        // ⚠ xnxqdm 为空正是触发劳动教育 bug 的条件 —— 这里锁定主查询的表单形态
        script.scoreDataList = { ok(dataListBody(rows = listOf(gradeRow("劳动教育", "202501", score = "")), total = 1)) }
        val config = JxfwConfig(hosts = hosts, patchLaborEducation = false)
        newClient(config).fetchGrades(term = null)

        val form = parseForm(recorded.single { it.request.path?.startsWith(SCORE_DATA_LIST_PATH) == true }.body)
        assertThat(form["xnxqdm"]).isEmpty()
        assertThat(form).containsEntry("jhlxdm", "")
    }

    @Test
    fun `关闭 patchLaborEducation 时不发兜底重查请求`() {
        val config = JxfwConfig(hosts = hosts, patchLaborEducation = false)
        script.scoreDataList = {
            ok(dataListBody(rows = listOf(gradeRow("劳动教育", "202501", score = "")), total = 1))
        }

        val result = newClient(config).fetchGrades(term = null)

        // 成绩保持为空（缺口如实呈现），且只发了一次请求
        assertThat(result.grades.single().scoreText).isEmpty()
        assertThat(recorded.count { it.request.path?.startsWith(SCORE_DATA_LIST_PATH) == true }).isEqualTo(1)
    }
}
