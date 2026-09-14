package com.gdutday.data.gdut.jxfw

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Term
import org.junit.Test

/**
 * 真实 fixture（`.real`，2026-09-14 `:data-gdut:dumpFixtures` 抓取）的解析契约测试。
 *
 * 与 JxfwScheduleParserTest 等用合成 fixture 的测试互补：合成文件只能证明
 * "解析器按我们理解的字段名工作"，这里的 `.real` 文件证明"字段名在学校那边
 * 真的这么拼、结构真的长这样"（T2.2 的闭环）。学校改接口时，最先炸的是这里。
 *
 * fixture 已脱敏：真实人名替换为 `师N老师`，无 cookie 值、无学号。
 */
class RealFixtureParseTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?.bufferedReader()?.readText()
            ?: error("缺 fixture $name —— .real 文件必须提交进仓库（T2.2）")

    private val term = Term(2025, 1)

    @Test
    fun `真实学期列表HTML能解析出当前学期`() {
        val parsed = JxfwTermParser.parse(fixture("jxfw_term_list.real.html"))
        assertThat(parsed.terms).isNotEmpty()
        assertThat(parsed.current).isNotNull()
    }

    @Test
    fun `真实课表getDataList响应能解析出课程行`() {
        val page = JxfwScheduleParser.parseDataListPage(
            fixture("jxfw_schedule_data_list_p1.real.json"),
            term,
        )
        assertThat(page.total).isAtLeast(1)
        assertThat(page.rows).isNotEmpty()
        // 逆向确认的关键字段：每行都有课程名与周次串
        val row = page.rows.first()
        assertThat(row.courseName).isNotEmpty()
        assertThat(row.weeks).isNotEmpty()
    }

    @Test
    fun `真实xsAllKbList响应仍存活且能解析出行`() {
        // T2.4：2026-09-14 实测接口存活（13 行）。此测试变红 = 学校下线了 A 接口，
        // 届时应考虑删除 ALL_KB_LIST 回退路径，AUTO 退化为 getDataList 单一路径。
        val rows = JxfwScheduleParser.parseAllKbList(
            fixture("jxfw_schedule_all_kb_list.real.html"),
            term,
        )
        assertThat(rows).isNotEmpty()
        assertThat(rows.first().courseName).isNotEmpty()
    }

    @Test
    fun `真实考试响应解析为空且不抛`() {
        // 抓取时是非考试周，total=0 的空响应也是地面真相
        val outcome = JxfwExamParser.parse(fixture("jxfw_exam_data_list.real.json"), term)
        assertThat(outcome.exams).isEmpty()
    }

    @Test
    fun `真实成绩响应能解析出成绩行`() {
        val outcome = JxfwGradeParser.parse(fixture("jxfw_score_data_list_all.real.json"))
        assertThat(outcome.grades).isNotEmpty()
        assertThat(outcome.total).isAtLeast(outcome.grades.size)
    }
}
