package com.gdutday.data.gdut.freeroom

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.GdutSession
import com.gdutday.core.model.StoredCookie
import com.gdutday.data.gdut.GdutEndpoints
import com.gdutday.data.gdut.http.LenientJson
import com.gdutday.data.gdut.http.SessionCookieJar
import com.gdutday.data.gdut.http.asBrowserNavigation
import com.gdutday.data.gdut.http.arr
import com.gdutday.data.gdut.http.int
import com.gdutday.data.gdut.http.str
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** 一栋教学楼。 */
public data class FreeRoomBuilding(
    /** 楼栋代码（`jzwdm`），查占用时回传。 */
    public val code: String,
    /** 楼栋名称（`jzwmc`），如"教学三号楼(城)"。 */
    public val name: String,
    /** 校区代码（`szxqdm`）。 */
    public val campusCode: String,
    /** 校区名（`xqmc`），如"大学城校区"。 */
    public val campusName: String,
)

/** 一条教室占用记录。 */
public data class RoomOccupancy(
    /** 教室名（`jxcdmc`），如"教3-101"。 */
    public val room: String,
    /** 课程名（`kcmc`）。 */
    public val courseName: String,
    /** 教师（`teaxms`），可能为空。 */
    public val teacher: String,
    /** 教学班（`jxbmc`）。 */
    public val teachingClass: String,
    /** 两位拼接节次（`jcdm`），如 `"0607"` = 第 6、7 节。 */
    public val sectionCode: String,
    /** 星期（`xq`，1..7）。 */
    public val dayOfWeek: Int?,
    /** 周次（`zc`）。 */
    public val week: Int?,
    /** 学期长码（`xnxqdm`）。 */
    public val termCode: String,
    /** 实际人数（`rs`）。 */
    public val attendees: Int?,
    /** 容量（`pkrs`）。 */
    public val capacity: Int?,
)

/** 一次教室占用查询的结果。 */
public data class RoomUsageResult(
    public val rows: List<RoomOccupancy>,
) {
    /** 按教室名聚合的占用行。 */
    public val byRoom: Map<String, List<RoomOccupancy>>
        get() = rows.groupBy { it.room }.toSortedMap(naturalRoomOrder)

    public companion object {
        /** 教室名排序：数字结尾的按编号比较（教3-9 < 教3-101），其余按字典序。 */
        private val naturalRoomOrder =
            compareBy<String> { it.length }.thenBy { it }
    }
}

/**
 * 空闲教室查询客户端（jwcwx.gdut.edu.cn，微信公众号教务 Web 端）。
 *
 * 会话来自统一认证 CAS：登录时 service 指向 jwcwx 的 `/login/cas`，
 * 拿到的 jwcwx JSESSIONID 存进 [GdutSession.cookies] 后由此客户端复用。
 * 一个实例绑定一个会话，会话更新后应新建实例（与 [com.gdutday.data.gdut.jxfw.JxfwClient] 一致）。
 *
 * 协议细节（实测 2026-09-17）见 `docs/reference/spec/gdut-protocol.mdc` §4.8。
 *
 * ## 线程模型
 *
 * 全部方法阻塞，必须在 IO 线程调用。
 *
 * @param httpClient 外部注入。**不要在内部 new**。
 */
public class FreeClassroomClient(
    httpClient: OkHttpClient,
    session: GdutSession,
    hosts: GdutHosts = GdutHosts.PRODUCTION,
) {

    public val cookieJar: SessionCookieJar = SessionCookieJar(session.cookies)

    private val client: OkHttpClient = httpClient.newBuilder()
        .cookieJar(cookieJar)
        // 被导回 /login/openid 正是"会话失效"的信号，自动跟随会把它变成微信 OAuth 页面。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val base: String = hosts.jwcwxBase
    private val buildingsUrl: String = hosts.jwcwxFreeRoomBuildings
    private val usedDataUrl: String = hosts.jwcwxFreeRoomUsedData

    /** 取出当前全部 cookie（含服务端新下发的），用于回写持久化。 */
    public fun currentCookies(): List<StoredCookie> = cookieJar.snapshot()

    /**
     * 取全部教学楼（覆盖五校区，一次约 60 栋）。
     *
     * @throws GdutException.SessionExpired 会话失效（未登录时 302 → /login/openid）
     * @throws GdutException.Parse 响应结构变了
     */
    public fun fetchBuildings(): List<FreeRoomBuilding> {
        val body = get(buildingsUrl, "教学楼列表")
        val json = LenientJson.requireObject(body, "教学楼列表")
        requireSuccess(json, "教学楼列表", body)
        val data = json.arr("data") ?: return emptyList()
        return data.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val code = obj.str("jzwdm")
            if (code.isBlank()) return@mapNotNull null
            FreeRoomBuilding(
                code = code,
                name = obj.str("jzwmc"),
                campusCode = obj.str("szxqdm"),
                campusName = obj.str("xqmc"),
            )
        }
    }

    /**
     * 查某栋楼某天的全部教室占用行。空闲教室 = 该楼全部教室 − 占用行。
     *
     * ⚠ 学校接口对无效日期/楼号**静默返回空数组不报错**，调用方需自行校验参数。
     *
     * @param buildingCode 教学楼代码（[FreeRoomBuilding.code]，即 `jzwdm`）
     * @param date 日期，`yyyy-MM-dd`
     * @throws GdutException.SessionExpired 会话失效
     * @throws GdutException.Parse 响应结构变了
     */
    public fun fetchRoomUsage(buildingCode: String, date: String): RoomUsageResult {
        require(buildingCode.isNotBlank()) { "教学楼代码不能为空" }
        require(date.matches(DATE_REGEX)) { "日期格式必须为 yyyy-MM-dd: $date" }
        val url = "$usedDataUrl?jwCode=&jzwdm=${enc(buildingCode)}&rq=${enc(date)}"
        val body = get(url, "教室占用")
        val json = LenientJson.requireObject(body, "教室占用")
        requireSuccess(json, "教室占用", body)
        val data = json.arr("data") ?: return RoomUsageResult(emptyList())
        val rows = data.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val room = obj.str("jxcdmc")
            if (room.isBlank()) return@mapNotNull null
            RoomOccupancy(
                room = room,
                courseName = obj.str("kcmc"),
                teacher = obj.str("teaxms"),
                teachingClass = obj.str("jxbmc"),
                sectionCode = obj.str("jcdm"),
                dayOfWeek = obj.int("xq"),
                week = obj.int("zc"),
                termCode = obj.str("xnxqdm"),
                attendees = obj.int("rs"),
                capacity = obj.int("pkrs"),
            )
        }
        return RoomUsageResult(rows)
    }

    // ------------------------------------------------------------------ 内部

    private fun requireSuccess(json: kotlinx.serialization.json.JsonObject, what: String, body: String) {
        val code = json.int("code")
        if (code != null && code != 0) {
            throw GdutException.Parse(
                what = what,
                snippet = "code=$code message='${json.str("message")}' body=${LenientJson.snippet(body, 200)}",
            )
        }
    }

    private fun get(url: String, what: String): String {
        val request = Request.Builder()
            .url(url)
            .asBrowserNavigation(referer = "$base/", sameSite = "same-origin")
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw GdutException.Network(detail = "请求$what 失败: ${e.message}", cause = e)
        }
        return response.use { r ->
            if (r.isRedirect) {
                // 未登录时 302 → /login/openid（微信 OAuth），没有匿名访问
                throw GdutException.SessionExpired(detail = "请求$what 被重定向到 ${r.header("Location")}")
            }
            if (r.code == 401 || r.code == 403) {
                throw GdutException.SessionExpired(detail = "请求$what 返回 ${r.code}")
            }
            if (r.code != 200) {
                throw GdutException.Http(r.code, url, detail = "请求$what")
            }
            try {
                r.body.string()
            } catch (e: IOException) {
                throw GdutException.Network(detail = "读取$what 响应体失败: ${e.message}", cause = e)
            }
        }
    }

    private fun enc(v: String): String = com.gdutday.data.gdut.http.FormFields.encodeComponent(v)

    private companion object {
        val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
