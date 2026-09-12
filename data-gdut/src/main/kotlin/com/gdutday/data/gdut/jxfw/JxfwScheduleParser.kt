package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.Course
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.Term
import com.gdutday.data.gdut.GdutHosts
import com.gdutday.data.gdut.http.LenientJson
import com.gdutday.data.gdut.http.int
import com.gdutday.data.gdut.http.rowsOf
import com.gdutday.data.gdut.http.str
import com.gdutday.data.gdut.http.totalOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 两个本科课表接口的解析器。
 *
 * 两个接口返回的**格式和粒度完全不同**，但都要归一到 [RawScheduleRow]，
 * 之后交给 [CourseNormalizer] 统一处理。字段对照见各自方法的注释。
 *
 * ## 接口 A：`xsgrkbcx!xsAllKbList.action`（HTML，按教学班聚合）
 *
 * 逆向来源：F# 库 `GDUT.ClassSchedule/Library.fs`（2022 年）。
 * 响应是一个 HTML 页面，课表数据以内嵌 JS 数组的形式存在：
 * ```html
 * <script>var kbxx = [ {"kcmc":"高等数学","kcbh":"…","jxbmc":"…","kcrwdm":"…",
 *                        "jcdm2":"1,2","zcs":"1,2,3,…,16","xq":"1",
 *                        "jxcdmcs":"教5-301","teaxms":"张三"}, … ];</script>
 * ```
 *
 * | 字段 | 含义 | 备注 |
 * |---|---|---|
 * | `kcmc` | 课程名称 | |
 * | `kcbh` | 课程编号 | |
 * | `jxbmc` | 教学班名称 | **可能逗号分隔多值** |
 * | `kcrwdm` | 课程任务代码 | |
 * | `jcdm2` | 节次 | 逗号分隔整数，如 `"1,2"` |
 * | `zcs` | 周次 | 逗号分隔整数，如 `"1,2,…,16"` |
 * | `xq` | 星期 | 整数 1..7 |
 * | `jxcdmcs` | 教学场地 | **可能逗号分隔多值** |
 * | `teaxms` | 授课教师 | **可能逗号分隔多值** |
 *
 * ⚠ **本次未联网验证该接口是否仍存活**（无可用账号）。所以
 * [JxfwClient.fetchSchedule] 默认先试接口 B（按周返回、能还原每周教室），失败自动回退到本接口，并把实际命中的接口
 * 记进 `ScheduleSnapshot.source`。
 *
 * ⚠ 该接口是 **GET**，且必须带
 * `Referer: https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action`
 * （F# 源码里专门写了 `// TODO: 重要！需要记录`）。
 *
 * ## 接口 B：`xsgrkbcx!getDataList.action`（JSON，按周炸开）
 *
 * 逆向来源：Java 后端 `GdutDayServiceImpl.getUnderGraduateSchedule`（2023-2024）。
 * 响应是 EasyUI DataGrid 格式：
 * ```json
 * { "total": 128, "rows": [ {"kcmc":"高等数学","jxcdmc":"教5-301","teaxms":"张三",
 *                            "xq":"1","zc":"3","jcdm":"0102","sknrjj":"…"}, … ] }
 * ```
 *
 * | 字段 | 含义 | 验证状态 |
 * |---|---|---|
 * | `kcmc` | 课程名称 | ✅ Java 后端在用 |
 * | `jxcdmc` | 教学场地名称 | ✅ |
 * | `teaxms` | 授课教师 | ✅ |
 * | `xq` | 星期 | ✅ |
 * | `zc` | 周次，**单个整数** | ✅ Java 后端直接拿它当 map key |
 * | `jcdm` | 节次，**两位拼接** `"0102"` | ✅ |
 * | `sknrjj` | 授课内容简介 | ✅ |
 * | `kcbh` | 课程编号 | ⚠ 未验证是否存在于该接口（考试接口里确认有） |
 * | `jxbmc` | 教学班名称 | ⚠ 未验证 |
 * | `pkrq` | 上课日期 | ⚠ 未验证。**若存在**，是反推开学日期的关键，见下 |
 *
 * 标注 ⚠ 的三个字段，解析时都是"有就用、没有就留空"，缺失不影响主流程。
 * **请务必用 docs/07-verify-login.md 里的脚本 dump 一次真实响应来确认。**
 *
 * ⚠ 该接口是 **POST** `application/x-www-form-urlencoded`，参数
 * `xnxqdm`(长码) `zc=""` `page`(1-based) `rows`(每页条数) `sort=kxh` `order=asc`，
 * 且必须带 `Referer: https://jxfw.gdut.edu.cn/`。
 *
 * ⚠ **分页**：Java 后端固定 `rows=300` 且不翻页。一学期 20 周 × 每周 20 行 = 400 行，
 * 课多的学生会被截断而**静默丢失后半学期的课**。本解析器读 `total` 并由
 * [JxfwClient] 循环翻页直到取满。
 */
public object JxfwScheduleParser {

    /** 接口 A 响应里内嵌课表数组的 JS 变量名。 */
    public const val KBXX_MARKER: String = "var kbxx"

    /**
     * 解析接口 A（`xsAllKbList`）的 HTML 响应。
     *
     * @throws GdutException.SessionExpired 响应是登录页 HTML（会话失效）
     * @throws GdutException.Parse 找不到 `var kbxx` 或数组为空
     */
    public fun parseAllKbList(html: String, term: Term): List<RawScheduleRow> {
        if (LenientJson.looksLikeHtml(html) && html.contains("authserver", ignoreCase = true) &&
            html.contains("pwdEncryptSalt", ignoreCase = true)
        ) {
            throw GdutException.SessionExpired(
                detail = "请求 xsAllKbList 时被导回统一认证登录页",
            )
        }
        if (!html.contains(KBXX_MARKER)) {
            throw GdutException.Parse(
                what = "课表（xsAllKbList）",
                snippet = "响应中找不到 '$KBXX_MARKER'。${LenientJson.snippet(html, 300)}",
            )
        }
        val array: JsonArray = JsonExtractor.extractArrayAfter(html, KBXX_MARKER)
            ?: throw GdutException.Parse(
                what = "课表（xsAllKbList）",
                snippet = "'$KBXX_MARKER' 后无法提取出合法的 JSON 数组。${LenientJson.snippet(html, 300)}",
            )
        return array.mapNotNull { element -> (element as? JsonObject)?.let { rowFromAllKbList(it) } }
    }

    /** 单行 `kbxx` → [RawScheduleRow]。字段无效时返回 null（该行被丢弃）。 */
    private fun rowFromAllKbList(row: JsonObject): RawScheduleRow? {
        val name = row.str("kcmc").trim()
        if (name.isEmpty()) return null
        val day = row.int("xq") ?: row.str("xq").trim().toIntOrNull() ?: return null
        return RawScheduleRow(
            courseName = name,
            courseCode = row.str("kcrwdm").ifBlank { row.str("kcbh") },
            teachingClass = CourseNormalizer.normalizeMultiValue(row.str("jxbmc")),
            classroom = CourseNormalizer.normalizeMultiValue(
                CourseNormalizer.normalizeClassroom(row.str("jxcdmcs")),
            ),
            teacher = CourseNormalizer.normalizeMultiValue(row.str("teaxms")),
            dayOfWeek = day,
            // jcdm2 是逗号分隔整数，明确告诉归一化器不要用两位拼接格式去猜
            sectionsRaw = row.str("jcdm2"),
            sectionsPaired = false,
            weeks = Course.parseWeeks(row.str("zcs")),
            description = row.str("sknrjj").ifBlank { row.str("kcnrjj") },
            classDate = null, // 该接口不提供具体日期
        )
    }

    /**
     * 解析接口 B（`getDataList`）单页的 JSON 响应。
     *
     * @return (本页的行, `total` 声明的总条数)。`total` 为 -1 表示响应里没有该字段。
     * @throws GdutException.SessionExpired 响应是 HTML 而非 JSON
     * @throws GdutException.Parse 响应不是合法的 JSON 对象
     */
    public fun parseDataListPage(body: String, term: Term): DataListPage {
        val obj = LenientJson.requireObject(body, what = "课表（getDataList）")
        val rows = rowsOf(obj)
        val parsed = rows.mapNotNull { element ->
            (element as? JsonObject)?.let { rowFromDataList(it, term) }
        }
        return DataListPage(rows = parsed, total = totalOf(obj), rawRowCount = rows.size)
    }

    /**
     * 接口 B 的一页。
     *
     * @property total 服务端声明的总条数，用于判断是否需要继续翻页。
     * @property rawRowCount 本页 `rows` 数组的原始长度（含被丢弃的行），
     *   用于检测"服务端一直返回同一页"的死循环。
     */
    public data class DataListPage(
        public val rows: List<RawScheduleRow>,
        public val total: Int,
        public val rawRowCount: Int,
    )

    private fun rowFromDataList(row: JsonObject, term: Term): RawScheduleRow? {
        val name = row.str("kcmc").trim()
        if (name.isEmpty()) return null
        val day = row.int("xq") ?: row.str("xq").trim().toIntOrNull() ?: return null
        return RawScheduleRow(
            courseName = name,
            courseCode = row.str("kcbh"),          // ⚠ 未验证该接口是否返回此字段
            teachingClass = CourseNormalizer.normalizeMultiValue(row.str("jxbmc")),
            classroom = CourseNormalizer.normalizeClassroom(row.str("jxcdmc")),
            teacher = CourseNormalizer.normalizeMultiValue(row.str("teaxms")),
            dayOfWeek = day,
            // jcdm 是两位拼接格式（"0102" = 第 1、2 节）
            sectionsRaw = row.str("jcdm"),
            sectionsPaired = true,
            // zc 通常是单个周次，但用 parseWeeks 兼容 "1-16" 之类的写法，
            // 万一服务端改成聚合返回也不会解析失败
            weeks = Course.parseWeeks(row.str("zc")),
            description = row.str("sknrjj"),
            classDate = CourseNormalizer.parseDateLenient(row.str("pkrq").ifBlank { null }),
        )
    }

    /** 构造接口 B 的表单参数。抽出来是为了让翻页逻辑与参数构造分离。 */
    public fun dataListForm(term: Term, page: Int, rowsPerPage: Int): Map<String, String> = linkedMapOf(
        "xnxqdm" to term.xnxqdm,   // ⚠ 长码，不是 shortCode
        "zc" to "",                // 必须存在且为空，表示"不按周筛选"
        "page" to page.toString(),
        "rows" to rowsPerPage.toString(),
        "sort" to "kxh",
        "order" to "asc",
    )

    /** 接口 A 的查询参数。 */
    public fun allKbListQuery(term: Term): Map<String, String> = linkedMapOf(
        "xnxqdm" to term.xnxqdm,
    )

    /**
     * 接口 A 必须的 Referer。
     *
     * ⚠ 注意它**不是**首页地址，而是 `xsgrkbcx!getXsgrbkList.action`。
     * F# 源码 `GDUT.ClassSchedule/Library.fs` 里专门留了注释：
     * `// TODO: 重要！需要记录`。漏掉它接口会拒绝响应。
     */
    public fun allKbListReferer(hosts: GdutHosts): String = hosts.jxfwScheduleAllKbReferer

    /** 接口 B 必须的 Referer（就是首页）。 */
    public fun dataListReferer(hosts: GdutHosts): String = hosts.jxfwDefaultReferer
}
