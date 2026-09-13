package com.gdutday.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** [Term] 的测试 —— 重点是那两套学期编码不要搞混。 */
class TermTest {

    @Test
    fun `短码与长码互不混淆`() {
        val t = Term(2025, 1)
        assertThat(t.shortCode).isEqualTo("20251")
        assertThat(t.xnxqdm).isEqualTo("202501")
        assertThat(Term(2025, 2).shortCode).isEqualTo("20252")
        assertThat(Term(2025, 2).xnxqdm).isEqualTo("202502")
        // 旧后端用 (t/10)*100 + t%10 做短→长转换，可读性极差且无法处理学期号 >= 10
        assertThat(Term(2025, 1).xnxqdm).isNotEqualTo(Term(2025, 1).shortCode)
    }

    @Test
    fun `两种编码都能解析回同一个 Term`() {
        assertThat(Term.parse("20251")).isEqualTo(Term(2025, 1))
        assertThat(Term.parse("202501")).isEqualTo(Term(2025, 1))
        assertThat(Term.parse(20251)).isEqualTo(Term(2025, 1))
        assertThat(Term.parse(202501)).isEqualTo(Term(2025, 1))
        assertThat(Term.parse("20252")).isEqualTo(Term(2025, 2))
        assertThat(Term.parse("202502")).isEqualTo(Term(2025, 2))
    }

    @Test
    fun `非法输入返回 null 而不是抛异常`() {
        // 接口改版时不要炸掉整个页面
        for (bad in listOf(null, "", "   ", "abc", "2025", "2025-1", "20250", "202510", "2025000", "99999")) {
            assertThat(Term.parse(bad)).isNull()
        }
    }

    @Test
    fun `学期号越界被拒`() {
        assertThat(Term.parse("20250")).isNull()      // 学期 0
        assertThat(Term.parse("20259")).isEqualTo(Term(2025, 9))
        assertThat(Term.parse("202510")).isNull()     // 6 位但学期 10
    }

    @Test
    fun `构造器拒绝明显非法的值`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { Term(2025, 0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { Term(2025, 10) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { Term(1800, 1) }
    }

    @Test
    fun `显示名与排序`() {
        assertThat(Term(2025, 1).displayName).isEqualTo("2025-2026学年第一学期")
        assertThat(Term(2024, 2).displayName).isEqualTo("2024-2025学年第二学期")
        assertThat(Term(2025, 1)).isGreaterThan(Term(2024, 2))
        assertThat(Term(2025, 2)).isGreaterThan(Term(2025, 1))
        assertThat(listOf(Term(2024, 1), Term(2025, 2), Term(2024, 2)).sortedDescending())
            .containsExactly(Term(2025, 2), Term(2024, 2), Term(2024, 1)).inOrder()
    }

    @Test
    fun `toString 是短码`() {
        assertThat(Term(2025, 1).toString()).isEqualTo("20251")
    }

    @Test
    fun `大致开学月份`() {
        assertThat(Term(2025, 1).approximateStartMonth).isEqualTo(9)
        assertThat(Term(2025, 2).approximateStartMonth).isEqualTo(2)
    }
}

/** [Course] 的测试 —— 重点是周次文本解析和自然键。 */
class CourseTest {

    private val term = Term(2025, 1)

    private fun course(weeks: Set<Int> = setOf(1)) = Course(
        term = term, name = "高数", dayOfWeek = 1,
        startSection = 1, sectionCount = 2, weeks = weeks,
    )

    @Test
    fun `endSection 由 start 与 count 派生`() {
        assertThat(course().copy(startSection = 8, sectionCount = 4).endSection).isEqualTo(11)
        assertThat(course().copy(startSection = 3, sectionCount = 1).endSection).isEqualTo(3)
    }

    @Test
    fun `parseWeeks 认得逗号列表`() {
        assertThat(Course.parseWeeks("1,2,3")).containsExactly(1, 2, 3)
        assertThat(Course.parseWeeks("1, 2, 3")).containsExactly(1, 2, 3)
        assertThat(Course.parseWeeks("2,4,6,8")).containsExactly(2, 4, 6, 8)
    }

    @Test
    fun `parseWeeks 认得区间`() {
        assertThat(Course.parseWeeks("1-16")).containsExactlyElementsIn(1..16)
        assertThat(Course.parseWeeks("1-8,11-16")).containsExactlyElementsIn((1..8) + (11..16))
        assertThat(Course.parseWeeks("3-3")).containsExactly(3)
    }

    @Test
    fun `parseWeeks 认得单双周 - 研究生 ZCMC 的写法`() {
        // 本科生接口目前不出现，但 Cost.parseWeeks 要能吃下，将来做研究生时不用重写
        assertThat(Course.parseWeeks("1-16单周")).containsExactlyElementsIn((1..16).filter { it % 2 == 1 })
        assertThat(Course.parseWeeks("2-12双周")).containsExactlyElementsIn((2..12).filter { it % 2 == 0 })
        assertThat(Course.parseWeeks("1-8单,10-16双"))
            .containsExactlyElementsIn((1..8).filter { it % 2 == 1 } + (10..16).filter { it % 2 == 0 })
    }

    @Test
    fun `parseWeeks 带"周"字和空格也能解析`() {
        assertThat(Course.parseWeeks("1-16 周")).containsExactlyElementsIn(1..16)
        assertThat(Course.parseWeeks("第1-3周")).containsExactly(1, 2, 3)
    }

    @Test
    fun `parseWeeks 对垃圾输入返回空集而不抛异常`() {
        assertThat(Course.parseWeeks(null)).isEmpty()
        assertThat(Course.parseWeeks("")).isEmpty()
        assertThat(Course.parseWeeks("   ")).isEmpty()
        assertThat(Course.parseWeeks("每周")).isEmpty()
        assertThat(Course.parseWeeks("abc")).isEmpty()
        // 越界的周次被丢弃
        assertThat(Course.parseWeeks("0,1,99")).containsExactly(1)
        // 倒置区间被丢弃而不是产生空 range 异常
        assertThat(Course.parseWeeks("16-1")).isEmpty()
        // 部分非法不影响合法部分
        assertThat(Course.parseWeeks("1-3,xxx,5")).containsExactly(1, 2, 3, 5)
    }

    @Test
    fun `formatWeekRanges 把周次压缩成区间文本`() {
        assertThat(Course.formatWeekRanges((1..16).toSet())).isEqualTo("1-16周")
        assertThat(Course.formatWeekRanges(setOf(1, 2, 3, 5, 6))).isEqualTo("1-3,5-6周")
        assertThat(Course.formatWeekRanges(setOf(1, 3, 5))).isEqualTo("1,3,5周")
        assertThat(Course.formatWeekRanges(setOf(7))).isEqualTo("7周")   // 单周不写成 7-7
        assertThat(Course.formatWeekRanges(emptySet())).isEqualTo("无周次")
        // 乱序输入也要正确
        assertThat(Course.formatWeekRanges(setOf(5, 1, 2, 6, 3))).isEqualTo("1-3,5-6周")
    }

    @Test
    fun `weeksDisplay 与 occursInWeek`() {
        val c = course(weeks = setOf(1, 2, 3))
        assertThat(c.weeksDisplay).isEqualTo("1-3周")
        assertThat(c.occursInWeek(2)).isTrue()
        assertThat(c.occursInWeek(4)).isFalse()
    }

    @Test
    fun `naturalKey 忽略 id-颜色-日期，因此可用于跨次同步比对`() {
        val a = course().copy(id = 1, colorKey = "red", classDates = listOf(LocalDate.of(2025, 9, 1)))
        val b = course().copy(id = 2, colorKey = "blue", classDates = listOf(LocalDate.of(2025, 9, 8)))
        assertThat(a.naturalKey).isEqualTo(b.naturalKey)
        // 但换了教室不该改变 naturalKey（教室不在键里）—— 换教室由 CourseNormalizer 在聚合阶段处理
        assertThat(a.naturalKey).isEqualTo(a.copy(classroom = "别的教室").naturalKey)
        // 换了星期/节次就必须不同
        assertThat(a.naturalKey).isNotEqualTo(a.copy(dayOfWeek = 2).naturalKey)
        assertThat(a.naturalKey).isNotEqualTo(a.copy(startSection = 3).naturalKey)
    }

    @Test
    fun `naturalKey 不含来源与周次，OVERRIDE 补丁才能在同步后匹配回教务课程`() {
        // 补丁的 overrideTargetNaturalKey 存的是原教务课程的自然键；
        // 若键里含 source，补丁（OVERRIDE）永远匹配不到原课程（SCHOOL）。
        val a = course()
        assertThat(a.naturalKey).isEqualTo(a.copy(source = CourseSource.CUSTOM).naturalKey)
        assertThat(a.naturalKey).isEqualTo(a.copy(source = CourseSource.OVERRIDE).naturalKey)
        assertThat(a.naturalKey).isEqualTo(a.copy(weeks = setOf(5, 6, 7)).naturalKey)
    }

    @Test
    fun `补丁相关字段的默认值`() {
        val c = course()
        // 默认 -1 = 未设置绝对时间，网格仍走"节次 → 作息表"换算
        assertThat(c.startMinute).isEqualTo(-1)
        assertThat(c.endMinute).isEqualTo(-1)
        assertThat(c.overrideScope).isNull()
        assertThat(c.overrideTargetNaturalKey).isNull()
        assertThat(c.overrideWeeks).isEmpty()
    }

    @Test
    fun `设置绝对时间与补丁字段后可整体拷贝`() {
        val c = course().copy(
            startMinute = 8 * 60 + 30,
            endMinute = 9 * 60 + 15,
            source = CourseSource.OVERRIDE,
            overrideScope = OverrideScope.WEEK_RANGE,
            overrideTargetNaturalKey = "高数|||1|1|2",
            overrideWeeks = setOf(3, 4),
        )
        assertThat(c.startMinute).isEqualTo(510)
        assertThat(c.endMinute).isEqualTo(555)
        assertThat(c.overrideScope).isEqualTo(OverrideScope.WEEK_RANGE)
        assertThat(c.overrideWeeks).containsExactly(3, 4)
    }

    @Test
    fun `构造器校验星期与节次范围`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { course().copy(dayOfWeek = 0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { course().copy(dayOfWeek = 8) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { course().copy(startSection = 0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { course().copy(sectionCount = 0) }
    }

    @Test
    fun `常量上限`() {
        assertThat(Course.MAX_WEEK).isAtLeast(20)   // 旧小程序硬编码 20，第 21 周的课会消失
        assertThat(Course.MAX_SECTION).isEqualTo(14)   // 与 SectionRunSplitter 对齐，13/14 节为实验课
    }
}

/** [StudentProfile] 与 [UserType] 的测试。 */
class StudentProfileTest {

    @Test
    fun `学号首位数字决定身份`() {
        assertThat(UserType.fromStudentId("3120012345")).isEqualTo(UserType.UNDERGRADUATE)
        assertThat(UserType.fromStudentId("2120012345")).isEqualTo(UserType.GRADUATE)
        assertThat(UserType.fromStudentId("0120012345")).isEqualTo(UserType.TEACHER)
        assertThat(UserType.fromStudentId("9120012345")).isEqualTo(UserType.UNKNOWN)
        assertThat(UserType.fromStudentId(null)).isEqualTo(UserType.UNKNOWN)
        assertThat(UserType.fromStudentId("")).isEqualTo(UserType.UNKNOWN)
    }

    @Test
    fun `code 与旧后端 RoleConstant 对齐`() {
        assertThat(UserType.UNDERGRADUATE.code).isEqualTo(1)
        assertThat(UserType.GRADUATE.code).isEqualTo(2)
        assertThat(UserType.TEACHER.code).isEqualTo(3)
        assertThat(UserType.fromCode(1)).isEqualTo(UserType.UNDERGRADUATE)
        assertThat(UserType.fromCode(null)).isEqualTo(UserType.UNKNOWN)
        assertThat(UserType.fromCode(99)).isEqualTo(UserType.UNKNOWN)
    }

    @Test
    fun `本科生学号格式校验`() {
        assertThat(StudentProfile.looksLikeUndergraduateId("3120012345")).isTrue()
        assertThat(StudentProfile.looksLikeUndergraduateId("312001234")).isFalse()    // 9 位
        assertThat(StudentProfile.looksLikeUndergraduateId("31200123456")).isFalse()  // 11 位
        assertThat(StudentProfile.looksLikeUndergraduateId("2120012345")).isFalse()   // 研究生
        assertThat(StudentProfile.looksLikeUndergraduateId("312001234a")).isFalse()
        assertThat(StudentProfile.looksLikeUndergraduateId(null)).isFalse()
    }

    @Test
    fun `姓名缺失时显示学号`() {
        assertThat(StudentProfile("3120012345").displayName).isEqualTo("3120012345")
        assertThat(StudentProfile("3120012345", name = "张三").displayName).isEqualTo("张三")
        assertThat(StudentProfile("3120012345").userType).isEqualTo(UserType.UNDERGRADUATE)
    }
}

/** [Campus] 的测试。 */
class CampusTest {

    @Test
    fun `fromRawName 宽松匹配各种写法`() {
        assertThat(Campus.fromRawName("大学城校区")).isEqualTo(Campus.UNIVERSITY_CITY)
        assertThat(Campus.fromRawName("大学城")).isEqualTo(Campus.UNIVERSITY_CITY)
        assertThat(Campus.fromRawName("广州大学城")).isEqualTo(Campus.UNIVERSITY_CITY)
        assertThat(Campus.fromRawName(" 东风路校区 ")).isEqualTo(Campus.DONGFENG_ROAD)
        assertThat(Campus.fromRawName("龙洞")).isEqualTo(Campus.LONGDONG)
        assertThat(Campus.fromRawName("番禺校区")).isEqualTo(Campus.PANYU)
    }

    @Test
    fun `认不出来时返回 UNKNOWN 而不是瞎猜`() {
        assertThat(Campus.fromRawName(null)).isEqualTo(Campus.UNKNOWN)
        assertThat(Campus.fromRawName("")).isEqualTo(Campus.UNKNOWN)
        assertThat(Campus.fromRawName("某个新校区")).isEqualTo(Campus.UNKNOWN)
    }

    @Test
    fun `DEFAULT 是大学城`() {
        assertThat(Campus.DEFAULT).isEqualTo(Campus.UNIVERSITY_CITY)
    }
}

/** [Exam] 的测试。 */
class ExamTest {

    private val term = Term(2025, 1)

    @Test
    fun `parseTimeRange 认得两个减号的实测格式`() {
        val (s, e) = Exam.parseTimeRange("08:30--10:05")
        assertThat(s).isEqualTo(LocalTime.of(8, 30))
        assertThat(e).isEqualTo(LocalTime.of(10, 5))
    }

    @Test
    fun `parseTimeRange 容错单减号、波浪号、单位数小时`() {
        assertThat(Exam.parseTimeRange("8:30-10:05").first).isEqualTo(LocalTime.of(8, 30))
        assertThat(Exam.parseTimeRange("8:30~10:05").second).isEqualTo(LocalTime.of(10, 5))
        assertThat(Exam.parseTimeRange("8:30—10:05").first).isEqualTo(LocalTime.of(8, 30))
    }

    @Test
    fun `parseTimeRange 对空值与垃圾输入返回 null`() {
        assertThat(Exam.parseTimeRange(null).first).isNull()
        assertThat(Exam.parseTimeRange("").second).isNull()
        assertThat(Exam.parseTimeRange("待定").first).isNull()
        assertThat(Exam.parseTimeRange("25:99--26:00").first).isNull()
    }

    @Test
    fun `timeDisplay 缺时间时给可读文案`() {
        val base = Exam(term = term, courseName = "课", date = LocalDate.of(2025, 12, 20))
        assertThat(base.copy().timeDisplay).isEqualTo("时间待定")
        assertThat(base.copy(startTime = LocalTime.of(8, 30)).timeDisplay).isEqualTo("08:30")
        assertThat(
            base.copy(startTime = LocalTime.of(8, 30), endTime = LocalTime.of(10, 5)).timeDisplay,
        ).isEqualTo("08:30--10:05")
    }

    @Test
    fun `daysUntil 计算倒计时`() {
        val exam = Exam(term = term, courseName = "课", date = LocalDate.of(2025, 12, 20))
        assertThat(exam.daysUntil(LocalDate.of(2025, 12, 10))).isEqualTo(10)
        assertThat(exam.daysUntil(LocalDate.of(2025, 12, 20))).isEqualTo(0)
        assertThat(exam.daysUntil(LocalDate.of(2025, 12, 25))).isEqualTo(-5)
    }
}

/** [GdutException] 的测试 —— 确保每个错误都有可直接展示的用户文案。 */
class GdutExceptionTest {

    @Test
    fun `所有子类都有非空的 userMessage`() {
        val all: List<GdutException> = listOf(
            GdutException.Network("detail"),
            GdutException.Http(503, "https://x"),
            GdutException.Http(404, "https://x"),
            GdutException.Http(405, "https://x"),
            GdutException.Http(418, "https://x"),
            GdutException.BadCredentials(),
            GdutException.BadCredentials("密码错误次数过多"),
            GdutException.SessionExpired(),
            GdutException.CaptchaRequired(),
            GdutException.TooManyRedirects(listOf("a", "b"), 10),
            GdutException.UnexpectedLoginResult(),
            GdutException.BadCaptcha(),
            GdutException.Parse("课表"),
            GdutException.EmptySchedule(Term(2025, 1)),
            GdutException.EmptySchedule(),
            GdutException.UnsupportedUserType(UserType.GRADUATE),
            GdutException.UnsupportedUserType(UserType.TEACHER),
            GdutException.Local("本地数据损坏"),
        )
        for (e in all) {
            assertThat(e.userMessage).isNotEmpty()
            // message 里要能看到用户文案，便于日志排查
            assertThat(e.message).contains(e.userMessage)
        }
    }

    @Test
    fun `Http 的状态码映射到不同文案`() {
        assertThat(GdutException.Http(503, "u").userMessage).contains("繁忙")
        assertThat(GdutException.Http(404, "u").userMessage).contains("改版")
        assertThat(GdutException.Http(418, "u").userMessage).contains("418")
        assertThat(GdutException.Http(500, "u").recoverable).isTrue()
        assertThat(GdutException.Http(404, "u").recoverable).isFalse()
    }

    @Test
    fun `recoverable 与 shouldRetryLogin 的语义正确`() {
        // 只有网络抖动和 5xx 值得自动重试
        assertThat(GdutException.Network().recoverable).isTrue()
        assertThat(GdutException.Http(502, "u").recoverable).isTrue()
        assertThat(GdutException.BadCredentials().recoverable).isFalse()
        assertThat(GdutException.CaptchaRequired().recoverable).isFalse()  // 重试只会让风控计数继续涨
        // 只有会话过期该引导重登
        assertThat(GdutException.SessionExpired().shouldRetryLogin).isTrue()
        assertThat(GdutException.BadCredentials().shouldRetryLogin).isFalse()
        assertThat(GdutException.Network().shouldRetryLogin).isFalse()
    }

    @Test
    fun `BadCredentials 优先展示服务端原文`() {
        assertThat(GdutException.BadCredentials("密码错误次数过多，请30分钟后再试").userMessage)
            .isEqualTo("密码错误次数过多，请30分钟后再试")
        assertThat(GdutException.BadCredentials().userMessage).isEqualTo("学号或密码错误")
        assertThat(GdutException.BadCredentials("   ").userMessage).isEqualTo("学号或密码错误")
    }

    @Test
    fun `Parse 异常会截断过长的 snippet`() {
        val long = "x".repeat(2000)
        val e = GdutException.Parse("课表", long)
        assertThat(e.detail!!.length).isAtMost(520)
        assertThat(e.detail).contains("x")
    }

    @Test
    fun `UnsupportedUserType 按身份给不同提示`() {
        assertThat(GdutException.UnsupportedUserType(UserType.GRADUATE).userMessage).contains("研究生")
        assertThat(GdutException.UnsupportedUserType(UserType.TEACHER).userMessage).contains("教师")
        assertThat(GdutException.UnsupportedUserType(UserType.UNKNOWN).userMessage).contains("无法识别")
    }

    @Test
    fun `CaptchaRequired 的文案给出可操作的出路`() {
        // 用户看到这句话应该知道下一步做什么，而不是只知道"失败了"
        val msg = GdutException.CaptchaRequired().userMessage
        assertThat(msg).contains("滑块")
        assertThat(msg).contains("教务系统登录")
    }

    @Test
    fun `detail 为空时 message 就是 userMessage，不带多余括号`() {
        assertThat(GdutException.Network().message).isEqualTo(GdutException.Network().userMessage)
    }
}

/** [ScheduleSnapshot] 与 [Grade] 的测试。 */
class SnapshotAndGradeTest {

    private val term = Term(2025, 1)

    @Test
    fun `maxWeek 至少 20，与旧小程序的滑动范围一致`() {
        // 旧小程序把周数硬编码成 20（weekLength = 20），第 21 周的课直接消失。
        // 本实现从数据推导，但保底 20 周以维持用户熟悉的范围。
        assertThat(ScheduleSnapshot(term).maxWeek).isEqualTo(20)
        val long = ScheduleSnapshot(
            term,
            courses = listOf(
                Course(term = term, name = "长学期课", dayOfWeek = 1, startSection = 1,
                    sectionCount = 1, weeks = (1..23).toSet()),
            ),
        )
        assertThat(long.maxWeek).isEqualTo(23)
    }

    @Test
    fun `isEmpty 与 source`() {
        assertThat(ScheduleSnapshot(term).isEmpty).isTrue()
        assertThat(ScheduleSnapshot(term).source).isEqualTo(ScheduleSource.UNKNOWN)
        val withCourse = ScheduleSnapshot(
            term,
            courses = listOf(
                Course(term = term, name = "课", dayOfWeek = 1, startSection = 1, sectionCount = 1),
            ),
        )
        assertThat(withCourse.isEmpty).isFalse()
    }

    @Test
    fun `ScheduleSource 只描述实际命中的接口`() {
        // ScheduleSource 是**结果**（数据实际来自哪个接口），所以只有两个真实接口 + UNKNOWN。
        // "本地" 不是一种接口来源，它属于用户可配置的 ScheduleFetchStrategy.LOCAL_ONLY，
        // 两者是不同维度的枚举，混在一起会让"上次同步命中了什么"这个诊断信息失去意义。
        assertThat(ScheduleSource.entries.map { it.label })
            .containsExactly("xsAllKbList", "getDataList", "未知")
    }

    @Test
    fun `ScheduleFetchStrategy 覆盖了全部四种用户可选策略`() {
        assertThat(ScheduleFetchStrategy.entries.map { it.name })
            .containsExactly("AUTO", "ONLY_ALL_KB_LIST", "ONLY_DATA_LIST", "LOCAL_ONLY")
        // AUTO 必须是默认值：fromName(null) 和 fromName("乱码") 都要落到它
        assertThat(ScheduleFetchStrategy.fromName(null)).isEqualTo(ScheduleFetchStrategy.AUTO)
        assertThat(ScheduleFetchStrategy.fromName("乱码")).isEqualTo(ScheduleFetchStrategy.AUTO)
        // 每个选项都要有给用户看的文案，否则设置页的单选列表会出现空白项
        assertThat(ScheduleFetchStrategy.entries.all { it.displayName.isNotBlank() && it.description.isNotBlank() })
            .isTrue()
    }

    @Test
    fun `parseScore 认得数字、等级、空值与缺考`() {
        assertThat(Grade.parseScore("87")).isEqualTo(87.0)
        assertThat(Grade.parseScore("87.5")).isEqualTo(87.5)
        assertThat(Grade.parseScore("优秀")).isNull()
        assertThat(Grade.parseScore("合格")).isNull()
        assertThat(Grade.parseScore("缺考")).isNull()
        assertThat(Grade.parseScore("")).isNull()
        assertThat(Grade.parseScore(null)).isNull()
        assertThat(Grade.parseScore("  90  ")).isEqualTo(90.0)
    }

    @Test
    fun `countsTowardsGpa 计入挂科但不计入等级制-缺绩点-零学分`() {
        fun g(score: Double?, gpa: Double?, credit: Double?) = Grade(
            termName = "T", courseName = "C", score = score, gpa = gpa, credit = credit,
        )
        assertThat(g(87.0, 3.7, 5.0).countsTowardsGpa).isTrue()
        assertThat(g(60.0, 1.0, 3.0).countsTowardsGpa).isTrue()     // 60 分及格
        assertThat(g(59.0, 0.0, 3.0).countsTowardsGpa).isTrue()     // 挂科：0 绩点也是平均绩点的一部分
        assertThat(g(30.0, 0.0, 4.0).countsTowardsGpa).isTrue()     // 低分挂科同样计入
        assertThat(g(null, null, 1.0).countsTowardsGpa).isFalse()   // 等级制
        assertThat(g(87.0, null, 5.0).countsTowardsGpa).isFalse()   // 没给绩点
        assertThat(g(87.0, 3.7, 0.0).countsTowardsGpa).isFalse()    // 零学分
        assertThat(g(87.0, 3.7, null).countsTowardsGpa).isFalse()
    }

    @Test
    fun `isPassed 与 isFailed 同时覆盖数值和等级制`() {
        fun g(score: Double? = null, text: String = "") =
            Grade(termName = "T", courseName = "C", score = score, scoreText = text)
        // 数值
        assertThat(g(score = 60.0).isPassed).isTrue()
        assertThat(g(score = 60.0).isFailed).isFalse()
        assertThat(g(score = 59.0).isFailed).isTrue()
        assertThat(g(score = 59.0).isPassed).isFalse()
        // 等级制
        for (word in listOf("优秀", "良好", "中等", "及格", "合格", "通过")) {
            assertThat(g(text = word).isPassed).isTrue()
            assertThat(g(text = word).isFailed).isFalse()
        }
        for (word in listOf("不合格", "不通过", "缺考")) {
            assertThat(g(text = word).isFailed).isTrue()
            assertThat(g(text = word).isPassed).isFalse()
        }
        // 缓考既不算过也不算挂
        assertThat(g(text = "缓考").isPassed).isFalse()
        assertThat(g(text = "缓考").isFailed).isFalse()
    }

    @Test
    fun `加权绩点计入挂科的 0 绩点`() {
        val summary = TermGradeSummary(
            termName = "T",
            grades = listOf(
                Grade(termName = "T", courseName = "A", score = 90.0, gpa = 4.0, credit = 3.0),
                Grade(termName = "T", courseName = "B", score = 50.0, gpa = 0.0, credit = 1.0), // 挂科
            ),
        )
        // (4.0*3 + 0.0*1) / (3+1) = 12/4 = 3.0，挂科把绩点拉低
        assertThat(summary.weightedGpa!!).isWithin(0.001).of(3.0)
    }

    @Test
    fun `加权绩点按学分加权`() {
        val summary = TermGradeSummary(
            termName = "T",
            grades = listOf(
                Grade(termName = "T", courseName = "A", score = 90.0, gpa = 4.0, credit = 5.0),
                Grade(termName = "T", courseName = "B", score = 70.0, gpa = 2.0, credit = 1.0),
                Grade(termName = "T", courseName = "等级制", scoreText = "优秀", credit = 2.0),
            ),
        )
        // (4.0*5 + 2.0*1) / (5+1) = 22/6 = 3.6667（等级制不计绩点，但计入总学分）
        assertThat(summary.weightedGpa!!).isWithin(0.001).of(3.6667)
        assertThat(summary.totalCredit).isWithin(0.001).of(8.0)   // A、B + 等级制「优秀」的 2 学分
        assertThat(summary.failedCount).isEqualTo(0)
    }

    @Test
    fun `totalCredit 与 failedCount 覆盖等级制-挂科-缓考`() {
        val summary = TermGradeSummary(
            termName = "T",
            grades = listOf(
                Grade(termName = "T", courseName = "A", score = 90.0, credit = 3.0),
                Grade(termName = "T", courseName = "B", score = 55.0, credit = 2.0), // 挂科
                Grade(termName = "T", courseName = "C", scoreText = "合格", credit = 1.0),
                Grade(termName = "T", courseName = "D", scoreText = "不合格", credit = 1.0), // 挂科
                Grade(termName = "T", courseName = "E", scoreText = "缺考", credit = 1.0),   // 挂科
                Grade(termName = "T", courseName = "F", scoreText = "缓考", credit = 1.0),   // 未定
            ),
        )
        assertThat(summary.totalCredit).isWithin(0.001).of(4.0)   // A + C
        assertThat(summary.failedCount).isEqualTo(3)              // B + D + E
    }

    @Test
    fun `没有可计绩点的课时 weightedGpa 为 null 而不是 0 或 NaN`() {
        val summary = TermGradeSummary(
            termName = "T",
            grades = listOf(Grade(termName = "T", courseName = "A", scoreText = "合格", credit = 1.0)),
        )
        assertThat(summary.weightedGpa).isNull()
        assertThat(summary.totalCredit).isWithin(0.001).of(1.0)
        assertThat(summary.failedCount).isEqualTo(0)
    }
}
