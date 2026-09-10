package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.Grade
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.Term
import com.gdutday.core.model.TermGradeSummary
import com.gdutday.data.gdut.http.LenientJson
import com.gdutday.data.gdut.http.double
import com.gdutday.data.gdut.http.rowsOf
import com.gdutday.data.gdut.http.str
import com.gdutday.data.gdut.http.totalOf
import kotlinx.serialization.json.JsonObject

/**
 * 成绩解析器。
 *
 * 数据源：`POST https://jxfw.gdut.edu.cn/xskccjxx!getDataList.action`
 * 表单：`xnxqdm`(空=全部学期 / 长码=指定学期) `jhlxdm=""` `sort=xnxqdm` `order=asc` `page` `rows`
 *
 * 字段对照（来自旧 Java 后端 `ExamScoreServiceImpl` + `ExamScoreDto`）：
 * ```
 * kcmc    课程名称        xnxqmc  学期名称（中文）    xnxqdm  学期代码（长码）
 * zcj     总成绩          cjjd    成绩绩点            xf      学分
 * kcdlmc  课程大类名称     kcflmc  课程分类名称        xdfsmc  修读方式名称
 * ```
 *
 * ## ⚠「劳动教育」的教务处 bug（必须处理，否则绩点算错）
 *
 * 以 `xnxqdm=""`（查询全部学期）请求时，**「劳动教育」这门课的 `zcj` 与 `cjjd` 会返回空**，
 * 但带上该行自己的 `xnxqdm` 再查一次就有值。
 *
 * 旧 Java 后端为此写过两轮修复，注释原文：
 * ```
 * //20240814 修复劳动教育成绩显示问题，问题原因 xnxqdm 为空时，劳动教育不显示成绩，是教务处的问题
 * //但我们还是抹平教务处的问题。。。
 * //20250630 修复劳动教育成绩错误问题，原因：
 * // 第一次修复时，没有设置当前学期的 xnxqdm，而且把所有当前学期的其他课的成绩都 set 到劳动教育的结果对象里了
 * ```
 * 第二次修复说明**第一版写错了**：忘了设 `xnxqdm`，导致遍历到了同学期其它课的成绩。
 * 本实现按修复后的语义来：只接受 `kcmc == "劳动教育"` 的那一行。
 *
 * 兜底由 [JxfwClient.fetchGrades] 执行（需要额外发请求），本解析器只负责**标记**哪些行需要兜底，
 * 见 [Outcome.needsLaborEducationPatch]。这样解析器保持纯函数，可离线测试。
 */
public object JxfwGradeParser {

    /** 触发教务处 bug 的课程名。 */
    public const val LABOR_EDUCATION_COURSE: String = "劳动教育"

    /**
     * 解析结果。
     *
     * @property grades 全部成绩行
     * @property summaries 按学期分组的汇总（含加权绩点）
     * @property total 服务端声明的总条数，用于翻页
     * @property needsLaborEducationPatch 需要兜底重查的"劳动教育"行所属学期（长码）。
     *   空表示不需要。
     */
    public data class Outcome(
        public val grades: List<Grade>,
        public val summaries: List<TermGradeSummary>,
        public val total: Int = -1,
        public val needsLaborEducationPatch: Set<String> = emptySet(),
        public val dropped: Int = 0,
    )

    /**
     * @throws GdutException.SessionExpired 响应是 HTML（会话失效）
     * @throws GdutException.Parse 响应不是合法 JSON 对象
     */
    public fun parse(body: String): Outcome {
        val obj = LenientJson.requireObject(body, what = "成绩")
        val rows = rowsOf(obj)

        val grades = mutableListOf<Grade>()
        val patchTerms = LinkedHashSet<String>()
        var dropped = 0

        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val name = row.str("kcmc").trim()
            if (name.isEmpty()) {
                dropped++
                continue
            }
            val termName = row.str("xnxqmc").trim()
            val termCode = row.str("xnxqdm").trim()
            val scoreText = row.str("zcj").trim()
            val score = Grade.parseScore(scoreText)
            val gpa = Grade.parseDouble(row.str("cjjd"))
            val credit = Grade.parseDouble(row.str("xf"))

            // 劳动教育 + 成绩或绩点缺失 → 记下来，由 JxfwClient 带 xnxqdm 重查一次
            if (name == LABOR_EDUCATION_COURSE && (scoreText.isEmpty() || gpa == null) && termCode.isNotEmpty()) {
                patchTerms += termCode
            }

            grades += Grade(
                termName = termName.ifEmpty { Term.parse(termCode)?.displayName ?: "未知学期" },
                term = Term.parse(termCode),
                courseName = name,
                courseCategory = row.str("kcdlmc").trim(),
                courseSubCategory = row.str("kcflmc").trim(),
                studyMode = row.str("xdfsmc").trim(),
                scoreText = scoreText,
                score = score,
                gpa = gpa,
                credit = credit,
            )
        }

        return Outcome(
            grades = grades,
            summaries = summarize(grades),
            total = totalOf(obj),
            needsLaborEducationPatch = patchTerms,
            dropped = dropped,
        )
    }

    /**
     * 按学期分组并计算加权绩点。
     *
     * 分组键用 `xnxqmc`（中文名）而不是 `xnxqdm`：
     * 实测部分行的 `xnxqdm` 会缺失（这正是劳动教育 bug 的根源之一），
     * 但 `xnxqmc` 一直有值。旧 Java 后端也是按 `xnxqmc` 分组的。
     *
     * 学期按 [Term] 倒序排列；没有 [Term] 的分组（无法解析学期码）排在最后。
     */
    public fun summarize(grades: List<Grade>): List<TermGradeSummary> {
        val grouped = grades.groupBy { it.termName }
        return grouped.map { (termName, rows) ->
            TermGradeSummary(
                termName = termName,
                term = rows.mapNotNull { it.term }.maxOrNull(),
                grades = rows.sortedBy { it.courseName },
            )
        }.sortedWith(
            compareByDescending<TermGradeSummary> { it.term != null }
                .thenByDescending { it.term }
                .thenBy { it.termName },
        )
    }

    /**
     * 构造表单参数。
     *
     * @param term 指定学期；null 表示查全部（`xnxqdm=""`）。
     *   ⚠ 查全部会触发劳动教育 bug，调用方需处理 [Outcome.needsLaborEducationPatch]。
     */
    public fun form(term: Term? = null, page: Int = 1, rowsPerPage: Int = 200): Map<String, String> = linkedMapOf(
        "xnxqdm" to (term?.xnxqdm ?: ""),
        "jhlxdm" to "",      // 计划类型代码，必须存在且为空
        "sort" to "xnxqdm",
        "page" to page.toString(),
        "rows" to rowsPerPage.toString(),
        "order" to "asc",
    )

    /**
     * 把兜底重查得到的成绩合并回主结果。
     *
     * 只覆盖「劳动教育」这一门课的 `scoreText` / `score` / `gpa`，
     * 其它字段（学分、课程类别）保持主查询的值 —— 这正是旧后端第二次修复的要点。
     *
     * @param base 主查询（`xnxqdm=""`）的结果
     * @param patchRows 兜底查询（带具体 `xnxqdm`）返回的成绩行
     * @param termCode 兜底查询用的学期长码
     */
    public fun mergeLaborEducationPatch(
        base: Outcome,
        patchRows: List<Grade>,
        termCode: String,
    ): Outcome {
        if (patchRows.isEmpty()) return base
        val patch = patchRows.firstOrNull { it.courseName == LABOR_EDUCATION_COURSE } ?: return base
        if (patch.scoreText.isEmpty() && patch.gpa == null) return base

        val merged = base.grades.map { g ->
            val isTarget = g.courseName == LABOR_EDUCATION_COURSE &&
                (g.term?.xnxqdm == termCode || g.term == null) &&
                (g.scoreText.isEmpty() || g.gpa == null)
            if (!isTarget) g
            else g.copy(
                scoreText = patch.scoreText.ifEmpty { g.scoreText },
                score = patch.score ?: g.score,
                gpa = patch.gpa ?: g.gpa,
                // 学分与课程属性以主查询为准，只有主查询缺失时才补
                credit = g.credit ?: patch.credit,
                courseCategory = g.courseCategory.ifEmpty { patch.courseCategory },
                courseSubCategory = g.courseSubCategory.ifEmpty { patch.courseSubCategory },
                studyMode = g.studyMode.ifEmpty { patch.studyMode },
            )
        }
        return base.copy(
            grades = merged,
            summaries = summarize(merged),
            needsLaborEducationPatch = base.needsLaborEducationPatch - termCode,
        )
    }
}
