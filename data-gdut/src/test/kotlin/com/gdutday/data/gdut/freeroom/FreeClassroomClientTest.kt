package com.gdutday.data.gdut.freeroom

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.LoginMethod
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.GdutSession
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
 * [FreeClassroomClient] 的端到端测试，跑在 MockWebServer 上。
 *
 * 沿用 `JxfwClientTest` 的做法：一个 [Dispatcher] 按 **path** 路由
 * （`GdutHosts.forTestServer` 把所有域都指向同一个端口），并记录每个请求。
 * 响应体是合成 fixture（字段名来自真实抓包，见协议文档 §4.8，实测 2026-09-17）。
 */
class FreeClassroomClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var hosts: GdutHosts

    private val recorded = mutableListOf<RecordedRequest>()

    private lateinit var script: Script

    private companion object {
        const val BUILDINGS_PATH = "/free-class-room/buildingData"
        const val USED_DATA_PATH = "/free-class-room/classroomUsedData"
    }

    private inner class Script(
        var buildings: () -> MockResponse = { ok(buildingsBody()) },
        var usedData: () -> MockResponse = { ok(usedDataBody()) },
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        hosts = GdutHosts.forTestServer(baseUrl())
        script = Script()
        recorded.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    path.startsWith(BUILDINGS_PATH) -> script.buildings()
                    path.startsWith(USED_DATA_PATH) -> script.usedData()
                    else -> MockResponse().setResponseCode(404).setBody("unexpected: ${request.method} $path")
                }
            }
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

    private fun baseUrl(): String = "http://localhost:${server.port}"

    private fun newClient(hosts: GdutHosts = this.hosts): FreeClassroomClient =
        FreeClassroomClient(client, fakeSession(hosts), hosts)

    private fun fakeSession(hosts: GdutHosts) = GdutSession(
        cookies = emptyList(),
        profile = StudentProfile(studentId = "3120012345"),
        method = LoginMethod.UNIFIED_AUTH,
        hosts = hosts,
    )

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/html;charset=UTF-8")   // 真实接口 Content-Type 不可信
        .setBody(body)

    /** 教学楼列表响应（字段名来自真实抓包，实测 2026-09-17）。 */
    private fun buildingsBody(): String =
        """{"code":0,"data":""" +
            """[{"jzwbh":"45","jzwdm":"109541938","jzwmc":"实验二号楼(城)","szxqdm":"1","xqmc":"大学城校区"},""" +
            """{"jzwbh":"05","jzwdm":"0005","jzwmc":"教学三号楼(城)","szxqdm":"1","xqmc":"大学城校区"},""" +
            """{"jzwbh":"32","jzwdm":"0032","jzwmc":"实验楼(龙)","szxqdm":"3","xqmc":"龙洞校区"}],""" +
            """"message":"查询数据成功"}"""

    /** 教室占用响应（字段名来自真实抓包，实测 2026-09-17；教师/教学班已脱敏）。 */
    private fun usedDataBody(vararg rows: String = arrayOf(defaultRow())): String =
        """{"code":0,"data":[${rows.joinToString(",")}],"message":"查询数据成功"}"""

    private fun defaultRow(): String =
        """{"dm":"3403654","flfzmc":null,"jcdm":"0607","jcdm2":"06,07","jxbmc":"班级A",""" +
            """"jxcddm":"019010101","jxcdmc":"教3-101","jxhjmc":"理论教学","kcmc":"形势与政策",""" +
            """"kxh":3,"pkrs":189,"rs":189,"shztdm":"3","sknrjj":null,"sytype":"1","teaxms":"某老师",""" +
            """"xnxqdm":"202601","xq":"4","zc":"3","zxs":24}"""

    // ================================================================== 教学楼列表

    @Test
    fun `教学楼列表能解析出楼栋与校区`() {
        val buildings = newClient().fetchBuildings()

        assertThat(buildings).hasSize(3)
        assertThat(buildings[1].code).isEqualTo("0005")
        assertThat(buildings[1].name).isEqualTo("教学三号楼(城)")
        assertThat(buildings[1].campusName).isEqualTo("大学城校区")
        assertThat(recorded.single().path).startsWith(BUILDINGS_PATH)
    }

    @Test
    fun `教学楼列表缺 jzwdm 的行被丢弃而不抛异常`() {
        script.buildings = {
            ok("""{"code":0,"data":[{"jzwmc":"无名楼"},{"jzwdm":"0005","jzwmc":"教学三号楼(城)","szxqdm":"1","xqmc":"大学城校区"}]}""")
        }

        val buildings = newClient().fetchBuildings()

        assertThat(buildings).hasSize(1)
        assertThat(buildings.single().code).isEqualTo("0005")
    }

    // ================================================================== 教室占用

    @Test
    fun `占用查询带上楼代码与日期参数`() {
        newClient().fetchRoomUsage("0005", "2026-09-17")

        val path = recorded.single().path.orEmpty()
        assertThat(path).startsWith(USED_DATA_PATH)
        assertThat(path).contains("jzwdm=0005")
        assertThat(path).contains("rq=2026-09-17")
    }

    @Test
    fun `占用行能解析出教室课程节次`() {
        val result = newClient().fetchRoomUsage("0005", "2026-09-17")

        assertThat(result.rows).hasSize(1)
        val row = result.rows.single()
        assertThat(row.room).isEqualTo("教3-101")
        assertThat(row.courseName).isEqualTo("形势与政策")
        assertThat(row.sectionCode).isEqualTo("0607")
        assertThat(row.dayOfWeek).isEqualTo(4)
        assertThat(row.week).isEqualTo(3)
        assertThat(row.termCode).isEqualTo("202601")
        assertThat(row.attendees).isEqualTo(189)
        assertThat(row.capacity).isEqualTo(189)
    }

    @Test
    fun `byRoom 按教室名聚合多行`() {
        script.usedData = {
            ok(
                usedDataBody(
                    defaultRow(),
                    """{"jxcdmc":"教3-101","kcmc":"复变函数","teaxms":"某老师","jxbmc":"班级B","jcdm":"0102","jcdm2":"01,02","xq":"4","zc":"3","xnxqdm":"202601","rs":134,"pkrs":134}""",
                    """{"jxcdmc":"教3-102","kcmc":"毛概","teaxms":"某老师","jxbmc":"班级C","jcdm":"0809","jcdm2":"08,09","xq":"4","zc":"3","xnxqdm":"202601","rs":144,"pkrs":144}""",
                ),
            )
        }

        val byRoom = newClient().fetchRoomUsage("0005", "2026-09-17").byRoom

        assertThat(byRoom.keys).containsExactly("教3-101", "教3-102").inOrder()
        assertThat(byRoom["教3-101"]).hasSize(2)
        assertThat(byRoom["教3-101"]!!.map { it.sectionCode }).containsExactly("0607", "0102")
    }

    @Test
    fun `无效日期或楼号时静默返回空数组不报错`() {
        script.usedData = { ok("""{"code":0,"data":[],"message":"查询数据成功"}""") }

        val result = newClient().fetchRoomUsage("9999", "2026-01-01")

        assertThat(result.rows).isEmpty()
    }

    @Test
    fun `非法日期格式直接拒绝不发请求`() {
        assertThrows(IllegalArgumentException::class.java) {
            newClient().fetchRoomUsage("0005", "2026/09/17")
        }
        assertThat(recorded).isEmpty()
    }

    @Test
    fun `业务 code 非零时抛 Parse 并附服务端 message`() {
        script.usedData = { ok("""{"code":-1,"data":null,"message":"非法参数"}""") }

        val e = assertThrows(GdutException.Parse::class.java) {
            newClient().fetchRoomUsage("0005", "2026-09-17")
        }
        assertThat(e.what).isEqualTo("教室占用")
        assertThat(e.snippet).contains("非法参数")
    }

    // ================================================================== 会话失效

    @Test
    fun `未登录被重定向到微信 OAuth 时抛 SessionExpired`() {
        script.usedData = {
            MockResponse().setResponseCode(302)
                .setHeader("Location", "${hosts.jwcwxBase}/login/openid")
        }

        assertThrows(GdutException.SessionExpired::class.java) {
            newClient().fetchRoomUsage("0005", "2026-09-17")
        }
    }

    @Test
    fun `服务端返回登录页 HTML 时抛 SessionExpired 而不是 Parse`() {
        script.usedData = { ok("<!DOCTYPE html><html><body>登录</body></html>") }

        assertThrows(GdutException.SessionExpired::class.java) {
            newClient().fetchRoomUsage("0005", "2026-09-17")
        }
    }

    @Test
    fun `HTTP 500 抛 Http 异常`() {
        script.usedData = { MockResponse().setResponseCode(500) }

        assertThrows(GdutException.Http::class.java) {
            newClient().fetchRoomUsage("0005", "2026-09-17")
        }
    }

    // ================================================================== Cookie

    @Test
    fun `服务端新下发的 cookie 能被 currentCookies 取出`() {
        script.usedData = {
            ok(usedDataBody()).apply {
                addHeader("Set-Cookie", "JSESSIONID=WCWX-NEW; Path=/; HttpOnly")
            }
        }

        val c = newClient()
        c.fetchRoomUsage("0005", "2026-09-17")

        assertThat(c.currentCookies().any { it.name == "JSESSIONID" && it.value == "WCWX-NEW" }).isTrue()
    }
}
