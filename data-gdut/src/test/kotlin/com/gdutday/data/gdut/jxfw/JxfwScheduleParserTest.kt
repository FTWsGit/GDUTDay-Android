package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.Term
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 两个课表接口的解析 + 归一化测试。
 *
 * ## ⚠ 这里的样例数据是**人工构造**的，不是抓包
 *
 * 字段名来自旧 Java 后端（`GdutDayServiceImpl.getUnderGraduateSchedule`，2024 年在用）
 * 和旧 F# 库（`GDUT.ClassSchedule/Library.fs`，2022 年逆向）的解析代码，
 * 可信度高；但**具体的值是我编的**，没有真实账号就无法核对。
 *
 * 请按 `docs/07-verify-login.md` 跑一次验证脚本，把真实响应 dump 出来替换掉这里的样例。
 * 特别需要确认的三个字段（旧后端没读，本项目按"有就用、没有就留空"处理）：
 * `kcbh`(课程编号) `jxbmc`(教学班) `pkrq`(上课日期)。
 */
class JxfwScheduleParserTest {

    private val term = Term(2025, 1)   // 短码 20251，长码 202501

    // ================================================================== 接口 A：xsAllKbList

    /**
     * `xsAllKbList` 返回的是一个 HTML 页面，课表数据以 `var kbxx = [...]` 的形式内嵌。
     * 这里刻意构造了几个真实的麻烦情况：
     * - 第 3 条的 `jcdm2 = "1,2,5,6"`（**一天两段**，必须拆成两个色块）
     * - 第 4 条的 `jxcdmcs` / `teaxms` / `jxbmc` 是逗号分隔多值
     * - 数组最后一个元素后面跟着 `];` 和一个 `</script>`
     */
    private val allKbListHtml = """
        <!DOCTYPE html>
        <html><head><title>个人课表</title></head>
        <body>
        <script type="text/javascript">
            var kbxx = [{"kcmc":"高等数学","kcbh":"1001","jxbmc":"高数A班","kcrwdm":"RW001",
                         "jcdm2":"1,2","zcs":"1,2,3,4,5,6,7,8","xq":"1",
                         "jxcdmcs":"教5-301","teaxms":"张三"},
                        {"kcmc":"大学英语","kcbh":"1002","jxbmc":"英语2班","kcrwdm":"RW002",
                         "jcdm2":"3,4","zcs":"2,4,6,8,10","xq":"3",
                         "jxcdmcs":"文科楼-202","teaxms":"李四"},
                        {"kcmc":"程序设计","kcbh":"1003","jxbmc":"计科1班","kcrwdm":"RV003",
                         "jcdm2":"1,2,5,6","zcs":"1,2,3","xq":"5",
                         "jxcdmcs":"实验楼-401","teaxms":"王五"},
                        {"kcmc":"体育课;含\"引号\"","kcbh":"1004","jxbmc":"篮球A,篮球B","kcrwdm":"RW004",
                         "jcdm2":"7,8","zcs":"1-16","xq":"2",
                         "jxcdmcs":"体育馆,室外场地","teaxms":"赵六,钱七"}];
            var other = {"not":"related"};
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `xsAllKbList 能从 HTML 里抠出 kbxx 数组`() {
        val rows = JxfwScheduleParser.parseAllKbList(allKbListHtml, term)
        assertThat(rows).hasSize(4)
        assertThat(rows[0].courseName).isEqualTo("高等数学")
        assertThat(rows[0].dayOfWeek).isEqualTo(1)
        assertThat(rows[0].sectionsRaw).isEqualTo("1,2")
        assertThat(rows[0].sectionsPaired).isFalse()
        assertThat(rows[0].weeks).containsExactlyElementsIn(1..8)
        assertThat(rows[0].classroom).isEqualTo("教5-301")
        assertThat(rows[0].teacher).isEqualTo("张三")
    }

    @Test
    fun `xsAllKbList 的多值字段用顿号归一`() {
        val rows = JxfwScheduleParser.parseAllKbList(allKbListHtml, term)
        val pe = rows.first { it.courseName.startsWith("体育课") }
        assertThat(pe.classroom).isEqualTo("体育馆、室外场地")
        assertThat(pe.teacher).isEqualTo("赵六、钱七")
        assertThat(pe.teachingClass).isEqualTo("篮球A、篮球B")
        // zcs="1-16" 这种区间写法也要能展开
        assertThat(pe.weeks).containsExactlyElementsIn(1..16)
        // 字符串里带分号和引号都不该破坏解析
        assertThat(pe.courseName).contains("引号")
    }

    @Test
    fun `xsAllKbList 归一化后一天两段的课被拆成两条`() {
        val rows = JxfwScheduleParser.parseAllKbList(allKbListHtml, term)
        val outcome = CourseNormalizer.normalize(rows, term)

        // "程序设计" jcdm2="1,2,5,6" → 两条：第1-2节 和 第5-6节
        val prog = outcome.courses.filter { it.name == "程序设计" }
        assertThat(prog).hasSize(2)
        assertThat(prog.map { it.startSection to it.sectionCount })
            .containsExactly(1 to 2, 5 to 2)
        // 两条都带完整的周次
        assertThat(prog.all { it.weeks == setOf(1, 2, 3) }).isTrue()
        // 一条绝不能跨越午休（第 1 节到第 6 节）
        assertThat(prog.none { it.startSection == 1 && it.sectionCount == 6 }).isTrue()
    }

    @Test
    fun `xsAllKbList 归一化结果的基本属性正确`() {
        val outcome = CourseNormalizer.normalize(
            JxfwScheduleParser.parseAllKbList(allKbListHtml, term), term,
        )
        // 4 行原始数据，其中 1 行被拆成 2 条 → 5 条课程
        assertThat(outcome.courses).hasSize(5)
        assertThat(outcome.droppedRows).isEqualTo(0)
        assertThat(outcome.courses.all { it.term == term }).isTrue()
        assertThat(outcome.courses.all { it.source == CourseSource.SCHOOL }).isTrue()
        // 稳定排序：星期 → 起始节 → 课程名。
        // 注意不能用 Truth 的 isInOrder()：它要求元素实现 Comparable，而 Pair 没有。
        val keys = outcome.courses.map { it.dayOfWeek to it.startSection }
        assertThat(keys).isEqualTo(keys.sortedWith(compareBy({ it.first }, { it.second })))
        // 而且顺序必须与接口返回顺序无关：把输入行打乱，结果应当完全一样
        val shuffled = CourseNormalizer.normalize(
            JxfwScheduleParser.parseAllKbList(allKbListHtml, term).shuffled(), term,
        )
        assertThat(shuffled.courses).isEqualTo(outcome.courses)
    }

    @Test
    fun `xsAllKbList 找不到 kbxx 时抛 Parse 异常`() {
        val e = assertThrows(GdutException.Parse::class.java) {
            JxfwScheduleParser.parseAllKbList("<html><body>课表维护中</body></html>", term)
        }
        assertThat(e.userMessage).contains("课表")
        assertThat(e.detail).contains("var kbxx")
    }

    @Test
    fun `xsAllKbList 被导回登录页时报 SessionExpired 而不是 Parse`() {
        val authPage = readFixture("authserver_login_page.real.html")
        val e = assertThrows(GdutException.SessionExpired::class.java) {
            JxfwScheduleParser.parseAllKbList(authPage, term)
        }
        assertThat(e.shouldRetryLogin).isTrue()
    }

    @Test
    fun `kbxx 是空数组时返回空列表，由上层决定怎么提示`() {
        val rows = JxfwScheduleParser.parseAllKbList(
            "<script>var kbxx = [];</script>", term,
        )
        assertThat(rows).isEmpty()
    }

    // ================================================================== 接口 B：getDataList

    /**
     * `getDataList` 是**按周炸开**的：一门 3 周的课会有 3 行，只有 `zc` 不同。
     * 归一化时必须合并回一条 `weeks = {1,2,3}`。
     *
     * `jcdm` 是两位拼接格式（`"0102"` = 第 1、2 节）。
     */
    private fun dataListJson(vararg weeks: Int) = buildString {
        // 这里刻意用普通字符串而不是 raw string：JSON 里满是双引号，
        // 而 Kotlin 的 raw string 以引号结尾时（`...,"""`）会产生 """ 的歧义，编译不过。
        append("{\"total\":").append(weeks.size).append(",\"rows\":[")
        weeks.forEachIndexed { i, w ->
            if (i > 0) append(',')
            append("{\"kcmc\":\"高等数学\",\"kcbh\":\"1001\",\"jxbmc\":\"高数A班\",\"jxcdmc\":\"教5-301\",")
            append("\"teaxms\":\"张三\",\"xq\":\"1\",\"zc\":\"$w\",\"jcdm\":\"0102\",")
            append("\"sknrjj\":\"极限与连续\",\"pkrq\":\"2025-09-${(w + 1).toString().padStart(2, '0')}\"}")
        }
        append("]}")
    }

    @Test
    fun `getDataList 按周炸开的行会被合并成一条并聚合周次`() {
        val rows = mutableListOf<RawScheduleRow>()
        var total = -1
        // 模拟两页：第 1 页 3 行，第 2 页 2 行
        JxfwScheduleParser.parseDataListPage(dataListJson(1, 2, 3), term).let { rows += it.rows; total = it.total }

        assertThat(rows).hasSize(3)
        assertThat(total).isEqualTo(3)
        // 每行只有一个周次
        assertThat(rows.map { it.weeks.single() }).containsExactly(1, 2, 3)
        // jcdm 是两位拼接格式
        assertThat(rows[0].sectionsPaired).isTrue()
        assertThat(rows[0].sectionsRaw).isEqualTo("0102")

        val outcome = CourseNormalizer.normalize(rows, term)
        assertThat(outcome.courses).hasSize(1)
        val course = outcome.courses.single()
        assertThat(course.weeks).containsExactly(1, 2, 3)
        assertThat(course.startSection).isEqualTo(1)
        assertThat(course.sectionCount).isEqualTo(2)
        assertThat(course.name).isEqualTo("高等数学")
        assertThat(course.classroom).isEqualTo("教5-301")
        assertThat(course.description).isEqualTo("极限与连续")
        // pkrq 被解析成日期，且与周次同序 —— 这是反推开学日期的输入
        assertThat(course.classDates).hasSize(3)
        assertThat(course.classDates.map { it.dayOfMonth }).containsExactly(2, 3, 4)
    }

    @Test
    fun `getDataList 的两位拼接节次能正确解析含第 10 节以上的情况`() {
        // "08091011" = 第 8,9,10,11 节。旧小程序的字符串戏法在这里会算错：
        // 它用 parseInt(time[0]+time[1]) 取起始节，但 length/2 当节数，
        // 对两位数节次是对的，可读性却极差；更糟的是它把 courseTime 当字符串存，
        // 无法表达"第 1 节和第 10 节"这种非连续情况。
        val json = """{"total":1,"rows":[{"kcmc":"晚课","xq":"1","zc":"1","jcdm":"08091011","jxcdmc":"教1-101"}]}"""
        val rows = JxfwScheduleParser.parseDataListPage(json, term).rows
        val outcome = CourseNormalizer.normalize(rows, term)

        assertThat(outcome.courses).hasSize(1)
        assertThat(outcome.courses.single().startSection).isEqualTo(8)
        assertThat(outcome.courses.single().sectionCount).isEqualTo(4)
        assertThat(outcome.courses.single().endSection).isEqualTo(11)
    }

    @Test
    fun `getDataList 换教室的同一门课不会被错误合并`() {
        // 前 8 周在 A 楼、后 8 周在 B 楼，是两行；
        // 聚合键里包含 classroom，所以必须保持两条，否则用户看不到换教室。
        val json = """{"total":2,"rows":[
          {"kcmc":"专业课","xq":"2","zc":"1","jcdm":"0304","jxcdmc":"教5-301"},
          {"kcmc":"专业课","xq":"2","zc":"9","jcdm":"0304","jxcdmc":"教6-101"}
        ]}"""
        val outcome = CourseNormalizer.normalize(
            JxfwScheduleParser.parseDataListPage(json, term).rows, term,
        )
        assertThat(outcome.courses).hasSize(2)
        assertThat(outcome.courses.map { it.classroom }).containsExactly("教5-301", "教6-101")
    }

    @Test
    fun `缺失的可选字段不会让解析失败`() {
        // kcbh / jxbmc / pkrq 是否存在未经联网验证，所以必须容错
        val json = """{"total":1,"rows":[{"kcmc":"只有名字的课","xq":"3","zc":"5","jcdm":"01"}]}"""
        val rows = JxfwScheduleParser.parseDataListPage(json, term).rows
        assertThat(rows).hasSize(1)
        assertThat(rows[0].courseCode).isEmpty()
        assertThat(rows[0].teachingClass).isEmpty()
        assertThat(rows[0].classDate).isNull()

        val outcome = CourseNormalizer.normalize(rows, term)
        assertThat(outcome.courses).hasSize(1)
        // 没有 pkrq 就没有 classDates，反推开学日期会退回到内置表/猜测，但课表本身照常显示
        assertThat(outcome.courses.single().classDates).isEmpty()
    }

    @Test
    fun `脏数据行被丢弃并记录原因，不抛异常`() {
        val json = """{"total":5,"rows":[
          {"kcmc":"正常课","xq":"1","zc":"1","jcdm":"0102"},
          {"kcmc":"","xq":"1","zc":"1","jcdm":"0102"},
          {"kcmc":"星期越界","xq":"9","zc":"1","jcdm":"0102"},
          {"kcmc":"节次非法","xq":"1","zc":"1","jcdm":""},
          {"kcmc":"周次为空","xq":"1","zc":"","jcdm":"0102"}
        ]}"""
        val page = JxfwScheduleParser.parseDataListPage(json, term)
        // 课程名为空的行在**解析阶段**就被 `rowFromDataList` 返回 null 丢掉了，
        // 根本到不了 CourseNormalizer，所以不计入 droppedRows。
        // 这是有意的：一个连名字都没有的行不值得记录诊断信息。
        assertThat(page.rawRowCount).isEqualTo(5)
        assertThat(page.rows).hasSize(4)

        val outcome = CourseNormalizer.normalize(page.rows, term)
        assertThat(outcome.courses).hasSize(1)
        assertThat(outcome.courses.single().name).isEqualTo("正常课")
        // 归一化阶段丢掉 3 行：星期越界、节次为空、周次为空
        assertThat(outcome.droppedRows).isEqualTo(3)
        assertThat(outcome.dropReasons.keys.joinToString()).contains("dayOfWeek=9")
        assertThat(outcome.dropReasons.keys.joinToString()).contains("节次无法解析")
        assertThat(outcome.dropReasons.keys.joinToString()).contains("周次为空")
        assertThat(outcome.hasDropped).isTrue()
    }

    @Test
    fun `xq 是数字类型而不是字符串时也能解析`() {
        // 教务系统同一字段在不同接口/学期可能是数字也可能是字符串，
        // LenientJson 的访问器要两种都吃
        val json = """{"total":1,"rows":[{"kcmc":"数字星期","xq":4,"zc":6,"jcdm":"0102"}]}"""
        val rows = JxfwScheduleParser.parseDataListPage(json, term).rows
        assertThat(rows.single().dayOfWeek).isEqualTo(4)
        assertThat(rows.single().weeks).containsExactly(6)
    }

    @Test
    fun `响应是登录页 HTML 时报 SessionExpired`() {
        val e = assertThrows(GdutException.SessionExpired::class.java) {
            JxfwScheduleParser.parseDataListPage(
                "<!DOCTYPE html><html><body>请登录</body></html>", term,
            )
        }
        assertThat(e.shouldRetryLogin).isTrue()
    }

    @Test
    fun `rows 缺失时返回空而不是抛异常，但要能从 total 看出异常`() {
        val page = JxfwScheduleParser.parseDataListPage("""{"total":42}""", term)
        assertThat(page.rows).isEmpty()
        assertThat(page.rawRowCount).isEqualTo(0)
        // total > 0 但 rows 为空 = 真的出问题了，调用方据此判断
        assertThat(page.total).isEqualTo(42)
    }

    // ================================================================== 表单参数

    @Test
    fun `课表接口用的是长码 xnxqdm 而不是短码`() {
        // 这是旧后端最容易搞混的地方：页面 <option> 给的是 202501（长码），
        // 内部 DTO 用的是 20251（短码），接口参数必须传长码。
        assertThat(term.shortCode).isEqualTo("20251")
        assertThat(term.xnxqdm).isEqualTo("202501")
        assertThat(JxfwScheduleParser.dataListForm(term, 1, 200)["xnxqdm"]).isEqualTo("202501")
        assertThat(JxfwScheduleParser.allKbListQuery(term)["xnxqdm"]).isEqualTo("202501")
    }

    @Test
    fun `getDataList 的表单参数与旧后端一致`() {
        val form = JxfwScheduleParser.dataListForm(term, 2, 200)
        assertThat(form).containsExactlyEntriesIn(
            linkedMapOf(
                "xnxqdm" to "202501",
                "zc" to "",
                "page" to "2",
                "rows" to "200",
                "sort" to "kxh",
                "order" to "asc",
            ),
        )
    }

    @Test
    fun `Referer 常量符合逆向结论`() {
        val hosts = com.gdutday.data.gdut.GdutHosts.PRODUCTION
        // xsAllKbList 的 Referer 不是首页，而是 getXsgrbkList（F# 源码标了 "TODO: 重要！需要记录"）
        assertThat(JxfwScheduleParser.allKbListReferer(hosts))
            .isEqualTo("https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action")
        assertThat(JxfwScheduleParser.dataListReferer(hosts))
            .isEqualTo("https://jxfw.gdut.edu.cn/")
    }

    // ================================================================== 教室名归一

    @Test
    fun `教室名去掉括号与"专用课室"后缀`() {
        // 研究生接口的 JASMC 形如 "教5-301(专用课室)"，旧后端做了这套替换。
        // 本科生的 jxcdmc 通常已经干净，但统一处理能保证"同一间教室"字符串一致，
        // 这对课表搜索和颜色聚合都有意义。
        assertThat(CourseNormalizer.normalizeClassroom("教5-301(专用课室)")).isEqualTo("教5-301")
        assertThat(CourseNormalizer.normalizeClassroom("教5-301（专用课室）")).isEqualTo("教5-301")
        assertThat(CourseNormalizer.normalizeClassroom(" 教 5-301 ")).isEqualTo("教5-301")
        assertThat(CourseNormalizer.normalizeClassroom(null)).isEmpty()
    }

    // ================================================================== 班级课表：getKbRq / xsAllKbList

    @Test
    fun `班级课表getKbRq能解析两元素数组并取课表行`() {
        val result = JxfwScheduleParser.parseClassScheduleGetKbRq(classGetKbRqJson, term)
        // 4 行课表：高数 ×2（第1、2周）、英语 ×1、程序设计 ×1
        assertThat(result.rows).hasSize(4)
        val math = result.rows.first()
        assertThat(math.courseName).isEqualTo("高等数学A(上)")
        assertThat(math.courseCode).isEqualTo("1001")
        assertThat(math.dayOfWeek).isEqualTo(1)
        assertThat(math.weeks).containsExactly(1)
        // jcdm 是两位拼接格式
        assertThat(math.sectionsPaired).isTrue()
        assertThat(math.sectionsRaw).isEqualTo("0102")
        assertThat(math.classroom).isEqualTo("教5-301")
        assertThat(math.teacher).isEqualTo("张三")
        assertThat(math.description).isEqualTo("极限与连续")
        // pkrs 上课日期被解析
        assertThat(math.classDate).isEqualTo(java.time.LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `班级课表getKbRq的周日期数组被完整保留`() {
        val result = JxfwScheduleParser.parseClassScheduleGetKbRq(classGetKbRqJson, term)
        assertThat(result.weekDates).hasSize(7)
        // 周一的 rq 即该周开学日 —— 反推开学日期的关键输入
        assertThat(result.weekDates.first().week).isEqualTo(1)
        assertThat(result.weekDates.first().date).isEqualTo(java.time.LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `班级课表getKbRq从fixture文件解析成功`() {
        val body = readFixture("class_schedule_get_kb_rq.json")
        val result = JxfwScheduleParser.parseClassScheduleGetKbRq(body, Term(2026, 1))
        assertThat(result.rows).hasSize(4)
        assertThat(result.weekDates).hasSize(7)
    }

    @Test
    fun `班级课表getKbRq缺失周日期数组时返回空而不抛异常`() {
        val body = """[[{"kcmc":"高等数学","xq":"1","zc":"1","jcdm":"0102"}]]"""
        val result = JxfwScheduleParser.parseClassScheduleGetKbRq(body, term)
        assertThat(result.rows).hasSize(1)
        assertThat(result.weekDates).isEmpty()
    }

    @Test
    fun `班级课表getKbRq响应不是数组时抛Parse`() {
        val e = assertThrows(GdutException.Parse::class.java) {
            JxfwScheduleParser.parseClassScheduleGetKbRq("""{"total":1}""", term)
        }
        assertThat(e.userMessage).contains("班级课表")
    }

    @Test
    fun `班级课表归一化结果与个人课表一致`() {
        val rows = JxfwScheduleParser.parseClassScheduleGetKbRq(classGetKbRqJson, term).rows
        val outcome = CourseNormalizer.normalize(rows, term)
        assertThat(outcome.courses).hasSize(3)
        assertThat(outcome.droppedRows).isEqualTo(0)
        assertThat(outcome.courses.all { it.source == CourseSource.SCHOOL }).isTrue()
        val math = outcome.courses.single { it.name.startsWith("高等数学") }
        // 两行（第 1、2 周）被合并成一条，周次聚合
        assertThat(math.weeks).containsExactly(1, 2)
        assertThat(math.startSection).isEqualTo(1)
        assertThat(math.sectionCount).isEqualTo(2)
        // pkrs 与周次同序，可参与开学日期反推
        assertThat(math.classDates).hasSize(2)
    }

    @Test
    fun `班级课表xsAllKbList复用个人课表的kbxx解析`() {
        val html = readFixture("class_schedule_all_kb_list.html")
        val rows = JxfwScheduleParser.parseClassScheduleAllKbList(html, term)
        assertThat(rows).hasSize(2)
        assertThat(rows[0].courseName).isEqualTo("高等数学A(上)")
        assertThat(rows[0].sectionsPaired).isFalse()
        assertThat(rows[0].weeks).containsExactlyElementsIn(1..16)
    }

    @Test
    fun `班级课表查询参数使用长码与班级代码`() {
        val query = JxfwScheduleParser.classScheduleGetKbRqQuery(term, "116523137", zc = 1)
        assertThat(query["xnxqdm"]).isEqualTo("202501")
        assertThat(query["bjdm"]).isEqualTo("116523137")
        assertThat(query["zc"]).isEqualTo("1")
        // zc 缺省 = 全学期
        val full = JxfwScheduleParser.classScheduleGetKbRqQuery(term, "116523137")
        assertThat(full.containsKey("zc")).isFalse()
        assertThat(JxfwScheduleParser.classScheduleAllKbListQuery(term, "116523137"))
            .containsExactlyEntriesIn(linkedMapOf("xnxqdm" to "202501", "bjdm" to "116523137"))
    }

    @Test
    fun `班级课表Referer实测非必需走首页`() {
        val hosts = com.gdutday.data.gdut.GdutHosts.PRODUCTION
        assertThat(JxfwScheduleParser.classScheduleGetKbRqReferer(hosts)).isEqualTo("https://jxfw.gdut.edu.cn/")
        assertThat(JxfwScheduleParser.classScheduleAllKbListReferer(hosts)).isEqualTo("https://jxfw.gdut.edu.cn/")
    }

    private val classGetKbRqJson = """
        [
          [
            {"kcmc":"高等数学A(上)","kcbh":"1001","kcdm":"MATH1001","teaxms":"张三","jxbmc":"高数A-01",
             "xnxqdm":"202501","zc":"1","jcdm":"0102","jcdm2":"01,02","xq":"1","jxcdmc":"教5-301",
             "sknrjj":"极限与连续","pkrs":"2025-09-01"},
            {"kcmc":"高等数学A(上)","kcbh":"1001","kcdm":"MATH1001","teaxms":"张三","jxbmc":"高数A-01",
             "xnxqdm":"202501","zc":"2","jcdm":"0102","jcdm2":"01,02","xq":"1","jxcdmc":"教5-301",
             "sknrjj":"导数与微分","pkrs":"2025-09-08"},
            {"kcmc":"大学英语(二)","kcbh":"1002","teaxms":"李四","jxbmc":"英语2班",
             "xnxqdm":"202501","zc":"1","jcdm":"0304","jcdm2":"03,04","xq":"3","jxcdmc":"文科楼-202",
             "sknrjj":"Unit 1","pkrs":"2025-09-03"},
            {"kcmc":"程序设计基础","kcbh":"1003","teaxms":"王五","jxbmc":"计科1班",
             "xnxqdm":"202501","zc":"1","jcdm":"0506","jcdm2":"05,06","xq":"5","jxcdmc":"实验楼-401",
             "sknrjj":"C 语言基础","pkrs":"2025-09-05"}
          ],
          [
            {"xqmc":"1","rq":"2025-09-01"},
            {"xqmc":"2","rq":"2025-09-02"},
            {"xqmc":"3","rq":"2025-09-03"},
            {"xqmc":"4","rq":"2025-09-04"},
            {"xqmc":"5","rq":"2025-09-05"},
            {"xqmc":"6","rq":"2025-09-06"},
            {"xqmc":"7","rq":"2025-09-07"}
          ]
        ]
    """.trimIndent()

    private fun readFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("找不到 fixture: $name")

    // ================================================================== 班级级联筛选（getFind）

    @Test
    fun `班级级联从fixture文件解析成功`() {
        val options = JxfwScheduleParser.parseClassCascade(readFixture("class_cascade_get_find.real.txt"))

        assertThat(options).hasSize(11)
        // 计算机类/计科出现在 2025 级筛选结果里，dm 即 bjdm
        assertThat(options).contains(JxfwScheduleParser.ClassCascadeOption("116523137", "计算机科学与技术25(5)"))
        assertThat(options.map { it.code }).contains("114895095")
    }

    @Test
    fun `班级级联解析丢弃空code或空name的元素`() {
        val body = """xsyxdm^getFind:[{"dm":"0711","mc":"[0711]计算机科学与技术"},{"dm":"","mc":"坏行"},{"dm":"0712","mc":""}]"""
        val options = JxfwScheduleParser.parseClassCascade(body)
        assertThat(options).containsExactly(JxfwScheduleParser.ClassCascadeOption("0711", "[0711]计算机科学与技术"))
    }

    @Test
    fun `班级级联响应缺少分隔符时抛Parse`() {
        val e = assertThrows(GdutException.Parse::class.java) {
            JxfwScheduleParser.parseClassCascade("""[{"dm":"0711","mc":"x"}]""")
        }
        assertThat(e.detail).contains("^getFind:")
    }

    @Test
    fun `班级级联分隔符后不是JSON数组时抛Parse`() {
        assertThrows(GdutException.Parse::class.java) {
            JxfwScheduleParser.parseClassCascade("rxnf^getFind:不是JSON")
        }
    }

    @Test
    fun `班级级联查询参数包含guid与三个过滤条件`() {
        val query = JxfwScheduleParser.classCascadeQuery(
            guid = "rxnf", term = term, grade = "2025", collegeCode = "07", majorCode = "0711",
        )
        assertThat(query).containsExactlyEntriesIn(
            linkedMapOf(
                "guid" to "rxnf",
                "xnxqdm" to "202501",
                "xqdm" to "",
                "rxnf" to "2025",
                "xsyxdm" to "07",
                "zydm" to "0711",
            ),
        )
    }

    @Test
    fun `班级级联查询参数默认全部过滤条件为空`() {
        val query = JxfwScheduleParser.classCascadeQuery(guid = "xsyxdm", term = term)
        assertThat(query["rxnf"]).isEmpty()
        assertThat(query["xsyxdm"]).isEmpty()
        assertThat(query["zydm"]).isEmpty()
        // 校区在该页面没有控件，恒为空串
        assertThat(query["xqdm"]).isEmpty()
    }
}
