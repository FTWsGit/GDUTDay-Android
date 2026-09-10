package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.Campus
import com.gdutday.core.model.Exam
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.Term
import com.gdutday.data.gdut.http.LenientJson
import com.gdutday.data.gdut.http.rowsOf
import com.gdutday.data.gdut.http.str
import com.gdutday.data.gdut.http.totalOf
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * 考试安排解析器。
 *
 * 数据源：`POST https://jxfw.gdut.edu.cn/xsksap!getDataList.action`
 * 表单：`xnxqdm`(长码) `page=1` `rows=200` `sort=zc,xq,jcdm2` `order=asc`
 *
 * 字段对照（来自旧 Java 后端 `GdutDayServiceImpl.getExaminationInfo`）：
 * ```
 * kcmc      课程名称          kcbh     课程编号（用于关联 Course）
 * ksrq      考试日期          kssj     考试时段，形如 "08:30--10:05"（两个减号！）
 * kscdmc    考试场地名称       xqmc     校区名称   ← 本科生数据里唯一的校区线索
 * kslbmc    考试类别名称       ksaplxmc 考试安排类型名称
 * ```
 *
 * ## `xqmc` 的额外价值
 *
 * 课表接口**不返回校区**，但作息表是按校区区分的。`xqmc` 是唯一能自动探测校区的地方，
 * 所以 [campusHint] 会把解析过程中见到的校区名收集起来。
 * 没有考试安排的学期（大一上）就探测不到，此时回退到用户在设置里手选，默认大学城。
 *
 * ## 旧实现的一个 bug
 *
 * 旧 Java 后端用 `idMap.get(kcbh)` 把考试关联到"该课程在课表里的序号"，
 * 但 `idMap` 是用**考试列表自己的下标**填的，跟课表毫无关系，
 * 结果前端拿到的 `position` 字段是个没有意义的数字。
 * 本项目改用 `courseCode`(kcbh) 做关联键，语义正确。
 */
public object JxfwExamParser {

    /**
     * 解析结果。
     *
     * @property exams 考试列表，按日期升序
     * @property campusHint 从 `xqmc` 探测到的校区（取出现次数最多的），可能为 [Campus.UNKNOWN]
     * @property dropped 因日期缺失/非法而被丢弃的行数
     */
    public data class Outcome(
        public val exams: List<Exam>,
        public val campusHint: Campus = Campus.UNKNOWN,
        public val total: Int = -1,
        public val dropped: Int = 0,
    )

    /**
     * @throws GdutException.SessionExpired 响应是 HTML（会话失效）
     * @throws GdutException.Parse 响应不是合法 JSON 对象
     */
    public fun parse(body: String, term: Term): Outcome {
        val obj = LenientJson.requireObject(body, what = "考试安排")
        val rows = rowsOf(obj)

        val campusVotes = LinkedHashMap<Campus, Int>()
        val exams = mutableListOf<Exam>()
        var dropped = 0

        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val date = parseDate(row.str("ksrq"))
            if (date == null) {
                dropped++
                continue
            }
            val name = row.str("kcmc").trim()
            if (name.isEmpty()) {
                dropped++
                continue
            }
            val campusName = row.str("xqmc")
            val campus = Campus.fromRawName(campusName)
            if (campus != Campus.UNKNOWN) campusVotes[campus] = (campusVotes[campus] ?: 0) + 1

            val (start, end) = Exam.parseTimeRange(row.str("kssj"))
            exams += Exam(
                term = term,
                courseName = name,
                courseCode = row.str("kcbh").trim(),
                date = date,
                startTime = start,
                endTime = end,
                classroom = CourseNormalizer.normalizeClassroom(row.str("kscdmc")),
                campus = campus,
                category = row.str("kslbmc").trim(),
                arrangementType = row.str("ksaplxmc").trim(),
            )
        }

        val campusHint = campusVotes.entries.maxByOrNull { it.value }?.key ?: Campus.UNKNOWN

        return Outcome(
            exams = exams.sortedWith(compareBy({ it.date }, { it.startTime }, { it.courseName })),
            campusHint = campusHint,
            total = totalOf(obj),
            dropped = dropped,
        )
    }

    /**
     * 解析 `ksrq`。
     *
     * 格式未在本次实测中确认，旧后端直接把它当字符串透传给前端（`examDate`），
     * 前端再用 `split("-")` 取月日。所以它大概率是 `yyyy-MM-dd`。
     * 这里用 [CourseNormalizer.parseDateLenient] 兼容多种写法。
     */
    private fun parseDate(raw: String): LocalDate? = CourseNormalizer.parseDateLenient(raw)

    /** 构造表单参数。 */
    public fun form(term: Term, page: Int = 1, rowsPerPage: Int = 200): Map<String, String> = linkedMapOf(
        "xnxqdm" to term.xnxqdm,
        "page" to page.toString(),
        "rows" to rowsPerPage.toString(),
        // 旧后端用的就是这个排序，保持与服务端缓存/索引友好
        "sort" to "zc,xq,jcdm2",
        "order" to "asc",
    )
}
