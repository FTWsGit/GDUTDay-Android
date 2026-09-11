package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.Campus
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.Term
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** 学期列表 / 考试安排 / 成绩三个解析器的测试。样例数据均为人工构造，见 [JxfwScheduleParserTest] 的说明。 */
class JxfwParserTest {

    private val term = Term(2025, 1)

    // ================================================================== 学期列表

    /**
     * 学期下拉框。刻意混入了几个干扰项：
     * - 引号形态不统一（单引号 / 双引号 / 无引号）
     * - `selected` 出现在 `value` **之前**
     * - 非学期的 option（院系代码 `01`、空值）
     */
    private val termListHtml = """
        <html><body>
        <select name="xnxqdm" id="xnxqdm">
          <option value="">全部学期</option>
          <option value='202302'>2023-2024学年第二学期</option>
          <option value="202401">2024-2025学年第一学期</option>
          <option value=202402>2024-2025学年第二学期</option>
          <option selected value='202501'>2025-2026学年第一学期</option>
          <option value='01'>机械工程学院</option>
        </select>
        </body></html>
    """.trimIndent()

    @Test
    fun `学期列表能解析出全部学期并识别当前学期`() {
        val list = JxfwTermParser.parse(termListHtml)

        // 院系代码 "01" 只有 2 位，被 Term.parse 拒掉；空值也拒掉
        assertThat(list.terms.map { it.shortCode })
            .containsExactly("20251", "20242", "20241", "20232")
            .inOrder()   // 倒序，最新在前
        assertThat(list.current).isEqualTo(Term(2025, 1))
        assertThat(list.isEmpty).isFalse()
    }

    @Test
    fun `学期的中文显示名取自页面原文，优先于我们自己的拼接`() {
        val list = JxfwTermParser.parse(termListHtml)
        assertThat(list.nameOf(Term(2025, 1))).isEqualTo("2025-2026学年第一学期")
        assertThat(list.nameOf(Term(2024, 2))).isEqualTo("2024-2025学年第二学期")
        // 没收录的学期回退到 Term.displayName
        assertThat(list.nameOf(Term(2030, 1))).isEqualTo(Term(2030, 1).displayName)
    }

    @Test
    fun `selected 写在 value 前面也能识别`() {
        val html = """<select><option selected value='202502'>春</option>
                      <option value='202501'>秋</option></select>"""
        assertThat(JxfwTermParser.parse(html).current).isEqualTo(Term(2025, 2))
    }

    @Test
    fun `没有 selected 时取最新的学期兜底`() {
        val html = """<select><option value='202401'>秋</option>
                      <option value='202501'>新</option></select>"""
        assertThat(JxfwTermParser.parse(html).current).isEqualTo(Term(2025, 1))
    }

    @Test
    fun `页面里一个学期都没有时抛 Parse`() {
        val e = assertThrows(GdutException.Parse::class.java) {
            JxfwTermParser.parse("<html><body>empty</body></html>")
        }
        assertThat(e.userMessage).contains("学期列表")
    }

    @Test
    fun `被导回登录页时报 SessionExpired`() {
        val e = assertThrows(GdutException.SessionExpired::class.java) {
            JxfwTermParser.parse("<html><body><input id='pwdEncryptSalt'></body></html>")
        }
        assertThat(e.shouldRetryLogin).isTrue()
    }

    @Test
    fun `parseCurrentOnly 在解析失败时返回 null 而不是抛异常`() {
        // 这是可选的优化路径（顺带从别的页面抠学期），不该因为抠不到就炸
        assertThat(JxfwTermParser.parseCurrentOnly("<html></html>")).isNull()
        assertThat(JxfwTermParser.parseCurrentOnly(termListHtml)).isEqualTo(Term(2025, 1))
    }

    @Test
    fun `parseCurrentOnly 遇到登录页时向上抛 SessionExpired 而不是吞掉`() {
        // 会话失效必须走统一的重登流程，静默返回 null 会伪装成"解析不到学期"。
        assertThrows(GdutException.SessionExpired::class.java) {
            JxfwTermParser.parseCurrentOnly("<html><body><input id='pwdEncryptSalt'></body></html>")
        }
    }

    // ================================================================== 考试安排

    private val examJson = """
        {"total":3,"rows":[
          {"kcmc":"高等数学","kcbh":"1001","ksrq":"2025-12-20","kssj":"08:30--10:05",
           "kscdmc":"教5-301(专用课室)","xqmc":"大学城校区","kslbmc":"正常考试","ksaplxmc":"集中安排"},
          {"kcmc":"大学英语","kcbh":"1002","ksrq":"2025-12-22","kssj":"14:30--16:05",
           "kscdmc":"文科楼-202","xqmc":"大学城校区","kslbmc":"正常考试","ksaplxmc":"随堂考试"},
          {"kcmc":"没有时间的考试","kcbh":"1003","ksrq":"2025-12-25","kssj":"",
           "kscdmc":"教1-101","xqmc":"东风路校区","kslbmc":"重修考试","ksaplxmc":""}
        ]}
    """.trimIndent()

    @Test
    fun `考试安排能解析并按日期排序`() {
        val outcome = JxfwExamParser.parse(examJson, term)
        assertThat(outcome.exams).hasSize(3)
        assertThat(outcome.dropped).isEqualTo(0)
        assertThat(outcome.total).isEqualTo(3)
        assertThat(outcome.exams.map { it.date.dayOfMonth }).containsExactly(20, 22, 25).inOrder()

        val first = outcome.exams[0]
        assertThat(first.courseName).isEqualTo("高等数学")
        assertThat(first.date).isEqualTo(LocalDate.of(2025, 12, 20))
        assertThat(first.startTime).isEqualTo(LocalTime.of(8, 30))
        assertThat(first.endTime).isEqualTo(LocalTime.of(10, 5))
        assertThat(first.timeDisplay).isEqualTo("08:30--10:05")
        // 教室名走同一套归一化
        assertThat(first.classroom).isEqualTo("教5-301")
        assertThat(first.category).isEqualTo("正常考试")
        assertThat(first.arrangementType).isEqualTo("集中安排")
        assertThat(first.courseCode).isEqualTo("1001")
    }

    @Test
    fun `kssj 的两个减号分隔符能正确切开`() {
        // 实测格式是 "08:30--10:05"（**两个**减号），不是 "08:30-10:05" 也不是 "~"
        val (s, e) = com.gdutday.core.model.Exam.parseTimeRange("08:30--10:05")
        assertThat(s).isEqualTo(LocalTime.of(8, 30))
        assertThat(e).isEqualTo(LocalTime.of(10, 5))
        // 但也要容错单减号和波浪号
        assertThat(com.gdutday.core.model.Exam.parseTimeRange("8:30-10:05").first)
            .isEqualTo(LocalTime.of(8, 30))
        assertThat(com.gdutday.core.model.Exam.parseTimeRange("8:30~10:05").second)
            .isEqualTo(LocalTime.of(10, 5))
        assertThat(com.gdutday.core.model.Exam.parseTimeRange("").first).isNull()
        assertThat(com.gdutday.core.model.Exam.parseTimeRange(null).second).isNull()
    }

    @Test
    fun `考试时间为空时 timeDisplay 给出可读文案而不是崩`() {
        val outcome = JxfwExamParser.parse(examJson, term)
        val noTime = outcome.exams.first { it.courseName == "没有时间的考试" }
        assertThat(noTime.startTime).isNull()
        assertThat(noTime.timeDisplay).isEqualTo("时间待定")
        assertThat(noTime.category).isEqualTo("重修考试")
    }

    @Test
    fun `xqmc 用来探测校区，取出现次数最多的`() {
        // 课表接口不返回校区，考试安排的 xqmc 是本科生数据里唯一的校区线索
        val outcome = JxfwExamParser.parse(examJson, term)
        assertThat(outcome.campusHint).isEqualTo(Campus.UNIVERSITY_CITY)  // 2:1 胜过东风路
        assertThat(outcome.exams[2].campus).isEqualTo(Campus.DONGFENG_ROAD)
    }

    @Test
    fun `没有任何 xqmc 时校区为 UNKNOWN，交由用户手选`() {
        val json = """{"total":1,"rows":[{"kcmc":"课","ksrq":"2025-12-20","kssj":"08:30--10:05"}]}"""
        assertThat(JxfwExamParser.parse(json, term).campusHint).isEqualTo(Campus.UNKNOWN)
    }

    @Test
    fun `日期非法的行被丢弃但不影响其它行`() {
        val json = """{"total":2,"rows":[
          {"kcmc":"好课","ksrq":"2025-12-20","kssj":"08:30--10:05"},
          {"kcmc":"坏日期","ksrq":"待定","kssj":"08:30--10:05"}
        ]}"""
        val outcome = JxfwExamParser.parse(json, term)
        assertThat(outcome.exams).hasSize(1)
        assertThat(outcome.dropped).isEqualTo(1)
    }

    @Test
    fun `没有考试安排是正常情况，返回空列表而不是抛异常`() {
        // 大一上学期、非考试周都会是空的。旧小程序在这里会显示空白页，
        // 本项目让 UI 自己决定怎么展示空状态。
        val outcome = JxfwExamParser.parse("""{"total":0,"rows":[]}""", term)
        assertThat(outcome.exams).isEmpty()
        assertThat(outcome.campusHint).isEqualTo(Campus.UNKNOWN)
    }

    @Test
    fun `考试表单参数用长码，排序与旧后端一致`() {
        assertThat(JxfwExamParser.form(term)["xnxqdm"]).isEqualTo("202501")
        assertThat(JxfwExamParser.form(term)["sort"]).isEqualTo("zc,xq,jcdm2")
    }

    // ================================================================== 成绩

    private val gradeJson = """
        {"total":4,"rows":[
          {"kcmc":"高等数学","xnxqmc":"2024-2025学年第一学期","xnxqdm":"202401",
           "zcj":"87","cjjd":"3.7","xf":"5.0","kcdlmc":"数学","kcflmc":"必修","xdfsmc":"必修"},
          {"kcmc":"大学英语","xnxqmc":"2024-2025学年第一学期","xnxqdm":"202401",
           "zcj":"75","cjjd":"2.5","xf":"4.0","kcdlmc":"外语","kcflmc":"必修","xdfsmc":"必修"},
          {"kcmc":"体育","xnxqmc":"2024-2025学年第一学期","xnxqdm":"202401",
           "zcj":"优秀","cjjd":"","xf":"1.0","kcdlmc":"体育","kcflmc":"必修","xdfsmc":"必修"},
          {"kcmc":"劳动教育","xnxqmc":"2024-2025学年第一学期","xnxqdm":"202401",
           "zcj":"","cjjd":"","xf":"0.5","kcdlmc":"","kcflmc":"必修","xdfsmc":"必修"}
        ]}
    """.trimIndent()

    @Test
    fun `成绩能解析并计算加权绩点`() {
        val outcome = JxfwGradeParser.parse(gradeJson)
        assertThat(outcome.grades).hasSize(4)
        assertThat(outcome.summaries).hasSize(1)

        val summary = outcome.summaries.single()
        assertThat(summary.termName).isEqualTo("2024-2025学年第一学期")
        assertThat(summary.term).isEqualTo(Term(2024, 1))
        // 只有数值成绩且有绩点的两门课参与：(3.7*5 + 2.5*4) / (5+4) = 28.5/9 = 3.1666…
        assertThat(summary.weightedGpa).isWithin(0.001).of(3.1667)
        // 总学分含等级制通过的课：高数 5 + 英语 4 + 体育「优秀」1 = 10（劳动教育缺成绩不计）
        assertThat(summary.totalCredit).isWithin(0.001).of(10.0)
        assertThat(summary.failedCount).isEqualTo(0)
    }

    @Test
    fun `等级制成绩不参与绩点但仍然是有效成绩`() {
        val outcome = JxfwGradeParser.parse(gradeJson)
        val pe = outcome.grades.first { it.courseName == "体育" }
        assertThat(pe.scoreText).isEqualTo("优秀")
        assertThat(pe.score).isNull()
        assertThat(pe.gpa).isNull()
        assertThat(pe.countsTowardsGpa).isFalse()
    }

    @Test
    fun `劳动教育缺成绩时被标记为需要兜底重查`() {
        // 教务处的 bug：以 xnxqdm="" 查全部学期时，劳动教育的 zcj/cjjd 会返回空。
        // 旧 Java 后端为此修过两轮（第二轮才修对）。本项目把标记与执行分开：
        // 解析器只负责标记，JxfwClient 负责带具体 xnxqdm 重查。
        val outcome = JxfwGradeParser.parse(gradeJson)
        assertThat(outcome.needsLaborEducationPatch).containsExactly("202401")
    }

    @Test
    fun `劳动教育兜底合并只覆盖成绩与绩点，不动其它字段`() {
        val base = JxfwGradeParser.parse(gradeJson)
        // 兜底查询返回同学期的全部课程（旧后端第一版 bug 就是把这些全都写进了劳动教育）
        val patch = JxfwGradeParser.parse(
            """{"total":2,"rows":[
               {"kcmc":"高等数学","xnxqmc":"2024-2025学年第一学期","xnxqdm":"202401","zcj":"99","cjjd":"5.0","xf":"9.9"},
               {"kcmc":"劳动教育","xnxqmc":"2024-2025学年第一学期","xnxqdm":"202401","zcj":"合格","cjjd":"","xf":"0.5","kcdlmc":"劳育"}
             ]}""",
        )

        val merged = JxfwGradeParser.mergeLaborEducationPatch(base, patch.grades, "202401")

        val labor = merged.grades.first { it.courseName == "劳动教育" }
        assertThat(labor.scoreText).isEqualTo("合格")
        assertThat(labor.courseCategory).isEqualTo("劳育")   // 主查询为空，补上
        // 关键：高等数学的成绩**不能**被兜底查询污染
        val math = merged.grades.first { it.courseName == "高等数学" }
        assertThat(math.scoreText).isEqualTo("87")
        assertThat(math.score).isEqualTo(87.0)
        assertThat(math.credit).isEqualTo(5.0)
        // 补丁已消费，不该再触发重查
        assertThat(merged.needsLaborEducationPatch).isEmpty()
    }

    @Test
    fun `兜底查询也没拿到成绩时保持原样`() {
        val base = JxfwGradeParser.parse(gradeJson)
        val emptyPatch = JxfwGradeParser.parse(
            """{"total":1,"rows":[{"kcmc":"劳动教育","xnxqdm":"202401","zcj":"","cjjd":""}]}""",
        )
        val merged = JxfwGradeParser.mergeLaborEducationPatch(base, emptyPatch.grades, "202401")
        assertThat(merged.grades).isEqualTo(base.grades)
    }

    @Test
    fun `按学期分组，学期码缺失时用中文名兜底`() {
        val json = """{"total":3,"rows":[
          {"kcmc":"A","xnxqmc":"2024-2025学年第二学期","xnxqdm":"202402","zcj":"90","cjjd":"4.0","xf":"2"},
          {"kcmc":"B","xnxqmc":"2024-2025学年第一学期","xnxqdm":"202401","zcj":"80","cjjd":"3.0","xf":"2"},
          {"kcmc":"C","xnxqmc":"未知学期","xnxqdm":"","zcj":"70","cjjd":"2.0","xf":"2"}
        ]}"""
        val outcome = JxfwGradeParser.parse(json)
        assertThat(outcome.summaries).hasSize(3)
        // 有 Term 的排在前面，且按 Term 倒序；没有 Term 的排最后
        assertThat(outcome.summaries.map { it.termName })
            .containsExactly("2024-2025学年第二学期", "2024-2025学年第一学期", "未知学期")
            .inOrder()
        assertThat(outcome.summaries.last().term).isNull()
        // 学期码缺失时用 xnxqmc 作为分组键仍然有效，termName 也不会变空
        assertThat(outcome.grades.first { it.courseName == "C" }.termName).isEqualTo("未知学期")
    }

    @Test
    fun `查全部学期时 xnxqdm 传空串，查指定学期时传长码`() {
        assertThat(JxfwGradeParser.form(null)["xnxqdm"]).isEmpty()
        assertThat(JxfwGradeParser.form(term)["xnxqdm"]).isEqualTo("202501")
        // jhlxdm 必须存在且为空，缺了会被拒
        assertThat(JxfwGradeParser.form(null)).containsEntry("jhlxdm", "")
    }

    @Test
    fun `挂科统计正确`() {
        val json = """{"total":3,"rows":[
          {"kcmc":"过","xnxqmc":"T","xnxqdm":"202401","zcj":"60","cjjd":"1.0","xf":"2"},
          {"kcmc":"挂","xnxqmc":"T","xnxqdm":"202401","zcj":"59","cjjd":"0","xf":"2"},
          {"kcmc":"缺考","xnxqmc":"T","xnxqdm":"202401","zcj":"缺考","cjjd":"","xf":"2"}
        ]}"""
        val summary = JxfwGradeParser.parse(json).summaries.single()
        // 挂科 = 数值 59 + 等级制「缺考」；60 分那门及格
        assertThat(summary.failedCount).isEqualTo(2)
        assertThat(summary.totalCredit).isWithin(0.001).of(2.0)   // 只有 60 分那门
    }
}
