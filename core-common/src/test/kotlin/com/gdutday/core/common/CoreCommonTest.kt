package com.gdutday.core.common

import com.gdutday.core.model.Campus
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.Exam
import com.gdutday.core.model.Term
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 学期历的测试。
 *
 * 周次换算看着简单，实际是**最容易出错又最难发现**的地方：
 * 算错一周，整张课表显示的就是别人的课，而用户往往要到"怎么这节课没上"才发现。
 *
 * 旧小程序的两个坑（都已在 [TermCalendar] 修掉，这里盯着别退化）：
 * 1. `termStart` 是模块级常量，改了开学日期要重启小程序
 * 2. 直接 `(now - termStart) / 7天`，开学日不是周一时"第 1 周"会从周中开始
 */
class TermCalendarTest {

    /** 2025-09-01 是周一（与旧后端 `application.yml` 的 `admissionDate: "2025.9.1"` 一致）。 */
    private val mondayStart = TermCalendar(LocalDate.of(2025, 9, 1))

    /** 2025-09-03 是周三，用来验证"对齐到周一"的行为。 */
    private val wednesdayStart = TermCalendar(LocalDate.of(2025, 9, 3))

    @Test
    fun `开学日是周一时 weekOneMonday 就是它本身`() {
        assertThat(mondayStart.weekOneMonday).isEqualTo(LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `开学日不是周一时第 1 周对齐到那个周的周一`() {
        // 旧实现会让"第 1 周"从周三开始，于是周一周二的课被算到第 0 周去，直接消失
        assertThat(wednesdayStart.weekOneMonday).isEqualTo(LocalDate.of(2025, 9, 1))
        assertThat(wednesdayStart.weekOf(LocalDate.of(2025, 9, 1))).isEqualTo(1)  // 那个周一
        assertThat(wednesdayStart.weekOf(LocalDate.of(2025, 9, 3))).isEqualTo(1)  // 开学日
    }

    @Test
    fun `weekOf 与 dateOf 互为逆运算`() {
        for (week in 1..25) {
            for (day in 1..7) {
                val date = mondayStart.dateOf(week, day)
                assertThat(mondayStart.weekOf(date)).isEqualTo(week)
                assertThat(date.dayOfWeek.value).isEqualTo(day)
            }
        }
    }

    @Test
    fun `开学前返回 0 或负数，不钳制`() {
        // 让调用方能区分"还没开学"和"第 1 周"
        assertThat(mondayStart.weekOf(LocalDate.of(2025, 8, 31))).isEqualTo(0)
        assertThat(mondayStart.weekOf(LocalDate.of(2025, 8, 25))).isEqualTo(0)
        assertThat(mondayStart.weekOf(LocalDate.of(2025, 8, 24))).isEqualTo(-1)
    }

    @Test
    fun `currentWeekClamped 把假期钳制到边界 - 对齐旧小程序行为`() {
        // 旧 getCurrentWeek() 钳制到 [0,19]（0-based），本实现钳制到 [1,totalWeeks]（1-based）
        val before = TermCalendar(LocalDate.of(2025, 9, 1), totalWeeks = 20)
        assertThat(before.currentWeekClamped(LocalDate.of(2025, 7, 1))).isEqualTo(1)
        assertThat(before.currentWeekClamped(LocalDate.of(2026, 6, 1))).isEqualTo(20)
        assertThat(before.currentWeekClamped(LocalDate.of(2025, 9, 10))).isEqualTo(2)
    }

    @Test
    fun `mondayOf 与 sundayOf 给出正确的周边界`() {
        assertThat(mondayStart.mondayOf(1)).isEqualTo(LocalDate.of(2025, 9, 1))
        assertThat(mondayStart.sundayOf(1)).isEqualTo(LocalDate.of(2025, 9, 7))
        assertThat(mondayStart.mondayOf(2)).isEqualTo(LocalDate.of(2025, 9, 8))
        assertThat(mondayStart.weekRange(1).let { it.start to it.endInclusive })
            .isEqualTo(LocalDate.of(2025, 9, 1) to LocalDate.of(2025, 9, 7))
    }

    @Test
    fun `isInSemester 与 isPastWeek`() {
        assertThat(mondayStart.isInSemester(LocalDate.of(2025, 9, 1))).isTrue()
        assertThat(mondayStart.isInSemester(LocalDate.of(2025, 8, 1))).isFalse()
        // 第 1 周的周日是 9-7；今天 9-10 已经过了第 1 周
        assertThat(mondayStart.isPastWeek(1, LocalDate.of(2025, 9, 10))).isTrue()
        assertThat(mondayStart.isPastWeek(2, LocalDate.of(2025, 9, 10))).isFalse()
    }

    // ------------------------------------------------------------------ 反推开学日期

    @Test
    fun `从 pkrq + zc + xq 反推开学日期`() {
        // 第 3 周周一是 2025-09-15 → 第 1 周周一是 2025-09-01
        val start = TermCalendar.deriveSemesterStart(
            listOf(SemesterStartSample(LocalDate.of(2025, 9, 15), week = 3, dayOfWeek = 1)),
        )
        assertThat(start).isEqualTo(LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `多条样本互相印证`() {
        val start = TermCalendar.deriveSemesterStart(
            listOf(
                SemesterStartSample(LocalDate.of(2025, 9, 15), week = 3, dayOfWeek = 1),
                SemesterStartSample(LocalDate.of(2025, 9, 18), week = 3, dayOfWeek = 4),
                SemesterStartSample(LocalDate.of(2025, 10, 8), week = 6, dayOfWeek = 3),
            ),
        )
        assertThat(start).isEqualTo(LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `单条脏数据不会污染结果 - 取众数`() {
        // 三条指向 9-01，一条指向 8-25（调课/脏数据）→ 众数胜出
        val start = TermCalendar.deriveSemesterStart(
            listOf(
                SemesterStartSample(LocalDate.of(2025, 9, 15), week = 3, dayOfWeek = 1),
                SemesterStartSample(LocalDate.of(2025, 9, 16), week = 3, dayOfWeek = 2),
                SemesterStartSample(LocalDate.of(2025, 9, 17), week = 3, dayOfWeek = 3),
                SemesterStartSample(LocalDate.of(2025, 9, 8), week = 3, dayOfWeek = 1),  // 异类
            ),
        )
        assertThat(start).isEqualTo(LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `样本非法或为空时返回 null，由上层回退到内置表`() {
        assertThat(TermCalendar.deriveSemesterStart(emptyList())).isNull()
        assertThat(
            TermCalendar.deriveSemesterStart(
                listOf(SemesterStartSample(LocalDate.of(2025, 9, 15), week = 0, dayOfWeek = 1)),
            ),
        ).isNull()
        assertThat(
            TermCalendar.deriveSemesterStart(
                listOf(SemesterStartSample(LocalDate.of(2025, 9, 15), week = 3, dayOfWeek = 9)),
            ),
        ).isNull()
    }

    @Test
    fun `samplesFrom 只在日期数与周次数一致时才产出样本`() {
        val term = Term(2025, 1)
        val aligned = Course(
            term = term, name = "A", dayOfWeek = 1, startSection = 1, sectionCount = 2,
            weeks = setOf(1, 2),
            classDates = listOf(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 8)),
        )
        assertThat(TermCalendar.samplesFrom(aligned)).hasSize(2)

        // 数量不一致说明有调课/合并行，硬凑会算出错误的开学日期，所以整门课跳过
        val mismatched = aligned.copy(classDates = listOf(LocalDate.of(2025, 9, 1)))
        assertThat(TermCalendar.samplesFrom(mismatched)).isEmpty()
    }

    @Test
    fun `guessSemesterStart 兜底到合理的日期`() {
        val fallback1 = TermCalendar.guessSemesterStart(2025, 1)
        assertThat(fallback1.year).isEqualTo(2025)
        assertThat(fallback1.monthValue).isEqualTo(9)
        assertThat(fallback1.dayOfWeek.value).isEqualTo(1)   // 一定是周一

        val fallback2 = TermCalendar.guessSemesterStart(2025, 2)
        assertThat(fallback2.year).isEqualTo(2026)
        assertThat(fallback2.monthValue).isEqualTo(2)
        assertThat(fallback2.dayOfWeek.value).isEqualTo(1)
    }

    // ------------------------------------------------------------------ 内置学期表

    @Test
    fun `内置表收录了 2025-2026 第一学期`() {
        // 来源：旧 Java 后端 application.yml 的 gdutday.admissionDate: "2025.9.1"
        assertThat(KnownSemesterStarts.startOf(Term(2025, 1))).isEqualTo(LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `内置表收录了 2026-2027 第一学期`() {
        assertThat(KnownSemesterStarts.startOf(Term(2026, 1))).isEqualTo(LocalDate.of(2026, 9, 7))
        assertThat(KnownSemesterStarts.latestKnownTerm).isEqualTo(Term(2026, 1))
    }

    @Test
    fun `未收录的学期返回 null 而不是瞎猜`() {
        assertThat(KnownSemesterStarts.startOf(Term(2030, 1))).isNull()
    }

    @Test
    fun `parseFlexibleDate 认得旧后端配置文件里的各种写法`() {
        // 用户很可能直接从旧 application.yml 里把 "2025.9.1" 复制过来，
        // 而 LocalDate.parse 只认 ISO 格式（月日必须补零）
        assertThat(KnownSemesterStarts.parseFlexibleDate("2025.9.1")).isEqualTo(LocalDate.of(2025, 9, 1))
        assertThat(KnownSemesterStarts.parseFlexibleDate("2025-09-01")).isEqualTo(LocalDate.of(2025, 9, 1))
        assertThat(KnownSemesterStarts.parseFlexibleDate("2025/9/1")).isEqualTo(LocalDate.of(2025, 9, 1))
        assertThat(KnownSemesterStarts.parseFlexibleDate("2025年9月1日")).isEqualTo(LocalDate.of(2025, 9, 1))
        assertThat(KnownSemesterStarts.parseFlexibleDate("")).isNull()
        assertThat(KnownSemesterStarts.parseFlexibleDate(null)).isNull()
        assertThat(KnownSemesterStarts.parseFlexibleDate("下学期")).isNull()
        assertThat(KnownSemesterStarts.parseFlexibleDate("2025-13-45")).isNull()
    }
}

/** [SectionRunSplitter] 的测试。 */
class SectionRunSplitterTest {

    @Test
    fun `两位拼接格式 - getDataList 的 jcdm`() {
        assertThat(SectionRunSplitter.parsePaired("0102").sections).containsExactly(1, 2)
        assertThat(SectionRunSplitter.parsePaired("0809").sections).containsExactly(8, 9)
        // 第 10 节以上必须能表达 —— 旧小程序的字符串戏法在这里可读性极差
        assertThat(SectionRunSplitter.parsePaired("08091011").sections).containsExactly(8, 9, 10, 11)
        assertThat(SectionRunSplitter.parsePaired("101112").sections).containsExactly(10, 11, 12)
    }

    @Test
    fun `逗号分隔格式 - xsAllKbList 的 jcdm2`() {
        assertThat(SectionRunSplitter.parseCommaSeparated("1,2").sections).containsExactly(1, 2)
        assertThat(SectionRunSplitter.parseCommaSeparated("1,2,5,6").sections).containsExactly(1, 2, 5, 6)
        assertThat(SectionRunSplitter.parseCommaSeparated("10,11").sections).containsExactly(10, 11)
        // 容错中文逗号、分号、空格
        assertThat(SectionRunSplitter.parseCommaSeparated("1，2").sections).containsExactly(1, 2)
        assertThat(SectionRunSplitter.parseCommaSeparated("1;2").sections).containsExactly(1, 2)
        assertThat(SectionRunSplitter.parseCommaSeparated("1, 2").sections).containsExactly(1, 2)
    }

    @Test
    fun `两种格式绝不能混淆 - 12 在两种格式下含义完全不同`() {
        // "12" 作为逗号格式 = 第 12 节；作为两位拼接格式 = 第 12 节（恰好一致）
        // 但 "0102" 作为逗号格式解析不出东西，"1,2" 作为两位拼接格式会变成第 1、2 节 + 垃圾
        // 所以调用方必须显式声明格式（RawScheduleRow.sectionsPaired），parseAuto 只是兜底
        assertThat(SectionRunSplitter.parsePaired("0102").sections).containsExactly(1, 2)
        // 用逗号格式去解析 "0102" 会得到整数 102，超出节次上限被丢弃 —— 这正是为什么
        // RawScheduleRow 要显式带 sectionsPaired 标记，绝不能让 parseAuto 去猜。
        val wrongFormat = SectionRunSplitter.parseCommaSeparated("0102")
        assertThat(wrongFormat.sections).isEmpty()
        assertThat(wrongFormat.dropped).contains("0102")
    }

    @Test
    fun `parseAuto 按有无分隔符判别格式`() {
        assertThat(SectionRunSplitter.parseAuto("0102").sections).containsExactly(1, 2)
        assertThat(SectionRunSplitter.parseAuto("1,2").sections).containsExactly(1, 2)
        assertThat(SectionRunSplitter.parseAuto("1-2").sections).containsExactly(1, 2)
        assertThat(SectionRunSplitter.parseAuto("").sections).isEmpty()
        assertThat(SectionRunSplitter.parseAuto(null).sections).isEmpty()
    }

    @Test
    fun `区间写法 1-4 能展开`() {
        assertThat(SectionRunSplitter.parseCommaSeparated("1-4").sections).containsExactly(1, 2, 3, 4)
        assertThat(SectionRunSplitter.parseCommaSeparated("1-2,5-6").sections).containsExactly(1, 2, 5, 6)
    }

    @Test
    fun `越界节次被丢弃并记入 dropped`() {
        val outcome = SectionRunSplitter.parsePaired("0102" + "99")
        assertThat(outcome.sections).containsExactly(1, 2)
        assertThat(outcome.dropped).contains("99")
        assertThat(SectionRunSplitter.parseCommaSeparated("0,1,99").sections).containsExactly(1)
    }

    @Test
    fun `奇数长度的拼接串按容错规则处理`() {
        // "01025" 更可能是 "01","02","5"，而不是整串作废
        val outcome = SectionRunSplitter.parsePaired("01025")
        assertThat(outcome.sections).containsAtLeast(1, 2)
    }

    @Test
    fun `连续段切分 - 一天两段必须拆开`() {
        // {1,2,5,6} 在网格上是两个不相邻的色块，画成一个 1→6 的矩形会盖住午休和下午前两节。
        // 旧 Java 后端没做这件事。
        assertThat(SectionRunSplitter.splitIntoRuns(setOf(1, 2, 5, 6)))
            .containsExactly(SectionRun(1, 2), SectionRun(5, 2))
            .inOrder()
    }

    @Test
    fun `连续段切分的各种情况`() {
        assertThat(SectionRunSplitter.splitIntoRuns(setOf(3))).containsExactly(SectionRun(3, 1))
        assertThat(SectionRunSplitter.splitIntoRuns(emptySet())).isEmpty()
        assertThat(SectionRunSplitter.splitIntoRuns(setOf(1, 2, 3, 4)))
            .containsExactly(SectionRun(1, 4))
        assertThat(SectionRunSplitter.splitIntoRuns(setOf(1, 3, 5)))
            .containsExactly(SectionRun(1, 1), SectionRun(3, 1), SectionRun(5, 1))
        // 输入乱序也要正确
        assertThat(SectionRunSplitter.splitIntoRuns(listOf(6, 5, 2, 1)))
            .containsExactly(SectionRun(1, 2), SectionRun(5, 2))
        // 重复值
        assertThat(SectionRunSplitter.splitIntoRuns(listOf(1, 1, 2)))
            .containsExactly(SectionRun(1, 2))
    }

    @Test
    fun `SectionRun 的派生属性`() {
        val run = SectionRun(8, 4)
        assertThat(run.end).isEqualTo(11)
        assertThat(run.sections).containsExactly(8, 9, 10, 11)
        assertThat(run.toString()).isEqualTo("8-11")
        assertThat(SectionRun(3, 1).toString()).isEqualTo("3")
    }

    @Test
    fun `parseAndSplit 一步到位`() {
        assertThat(SectionRunSplitter.parseAndSplit("0102"))
            .containsExactly(SectionRun(1, 2))
        assertThat(SectionRunSplitter.parseAndSplit("1,2,5,6"))
            .containsExactly(SectionRun(1, 2), SectionRun(5, 2))
    }
}

/** [CampusTimetable] 的测试。数据逐字抄自旧小程序 `staticData/campusTime.js`。 */
class CampusTimetableTest {

    @Test
    fun `四个校区加 UNKNOWN 都能取到作息表，且都是 12 节`() {
        for (campus in Campus.entries) {
            val t = CampusTimetable.of(campus)
            assertThat(t.size).isEqualTo(12)
            assertThat(t.periods.map { it.index }).containsExactlyElementsIn(1..12).inOrder()
        }
    }

    @Test
    fun `大学城第 1 节 8-30 起，末节 20-55 止`() {
        val t = CampusTimetable.of(Campus.UNIVERSITY_CITY)
        assertThat(t.periodOf(1).start).isEqualTo(LocalTime.of(8, 30))
        assertThat(t.periodOf(1).end).isEqualTo(LocalTime.of(9, 15))
        assertThat(t.periodOf(12).end).isEqualTo(LocalTime.of(20, 55))
        assertThat(t.firstPeriodStart).isEqualTo(LocalTime.of(8, 30))
        assertThat(t.lastPeriodEnd).isEqualTo(LocalTime.of(20, 55))
    }

    @Test
    fun `东风路与龙洞 8-15 起，且两者作息完全一致`() {
        val df = CampusTimetable.of(Campus.DONGFENG_ROAD)
        val ld = CampusTimetable.of(Campus.LONGDONG)
        assertThat(df.periodOf(1).start).isEqualTo(LocalTime.of(8, 15))
        // 原始数据里这两份是逐字相同的拷贝
        assertThat(df.periods).isEqualTo(ld.periods)
    }

    @Test
    fun `番禺校区的第 10 到 12 节是可疑的占位数据 - 原样保留并在此钉住`() {
        // 原始数据里三节完全相同，都是 19:30-21:30。这显然不是真实作息。
        // 我们不擅自编造，但必须让后来者知道这里有坑：
        // 如果哪天有人"修好"了它，这条测试会失败，届时请同步更新文档说明依据。
        val t = CampusTimetable.of(Campus.PANYU)
        assertThat(t.periodOf(10).start).isEqualTo(t.periodOf(11).start)
        assertThat(t.periodOf(11).start).isEqualTo(t.periodOf(12).start)
        assertThat(t.periodOf(10).start).isEqualTo(LocalTime.of(19, 30))
        // 番禺的前 9 节是正常递增的
        assertThat(t.periodOf(1).start).isEqualTo(LocalTime.of(8, 30))
        assertThat(t.periodOf(9).end).isEqualTo(LocalTime.of(17, 50))
    }

    @Test
    fun `UNKNOWN 回退到大学城`() {
        assertThat(CampusTimetable.of(Campus.UNKNOWN).periods)
            .isEqualTo(CampusTimetable.of(Campus.UNIVERSITY_CITY).periods)
    }

    @Test
    fun `节次越界时钳制而不抛异常`() {
        // 教务系统偶尔会给出第 13 节（某些实验课），课表页不该因此整页崩溃
        val t = CampusTimetable.of(Campus.UNIVERSITY_CITY)
        assertThat(t.periodOf(0)).isEqualTo(t.periodOf(1))
        assertThat(t.periodOf(13)).isEqualTo(t.periodOf(12))
        assertThat(t.periodOf(99)).isEqualTo(t.periodOf(12))
    }

    @Test
    fun `spanMinutes 计算含课间的总时长`() {
        val t = CampusTimetable.of(Campus.UNIVERSITY_CITY)
        // 第 1-2 节：8:30 → 10:05，共 95 分钟（含 5 分钟课间）
        assertThat(t.spanMinutes(1, 2)).isEqualTo(95)
        // 单节 45 分钟
        assertThat(t.spanMinutes(1, 1)).isEqualTo(45)
        // 参数顺序颠倒也能算
        assertThat(t.spanMinutes(2, 1)).isEqualTo(95)
    }

    @Test
    fun `每节课都是 45 分钟（番禺的可疑节次除外）`() {
        for (campus in listOf(Campus.UNIVERSITY_CITY, Campus.DONGFENG_ROAD, Campus.LONGDONG)) {
            for (p in CampusTimetable.of(campus).periods) {
                assertThat(p.durationMinutes).isEqualTo(45)
            }
        }
    }

    @Test
    fun `自定义作息表能覆盖默认值`() {
        val raw = (1..12).flatMap { i ->
            listOf("%02d:00".format(i + 7), "%02d:50".format(i + 7))
        }
        val custom = CampusTimetable.parseCustom(Campus.UNIVERSITY_CITY, raw)
        assertThat(custom).isNotNull()
        assertThat(custom!!.periodOf(1).start).isEqualTo(LocalTime.of(8, 0))
        assertThat(custom.periodOf(1).end).isEqualTo(LocalTime.of(8, 50))
    }

    @Test
    fun `自定义作息表有一处输错时整体拒绝，而不是半对半错`() {
        val good = (1..12).flatMap { listOf("08:00", "08:45") }
        assertThat(CampusTimetable.parseCustom(Campus.PANYU, good)).isNotNull()
        // 长度不对
        assertThat(CampusTimetable.parseCustom(Campus.PANYU, good.take(10))).isNull()
        assertThat(CampusTimetable.parseCustom(Campus.PANYU, null)).isNull()
        // 格式不对
        assertThat(CampusTimetable.parseCustom(Campus.PANYU, good.toMutableList().also { it[3] = "八点" })).isNull()
        // 起止倒挂
        assertThat(CampusTimetable.parseCustom(Campus.PANYU, good.toMutableList().also { it[0] = "09:00"; it[1] = "08:00" })).isNull()
    }
}

/** [CourseColors] 的测试。 */
class CourseColorsTest {

    @Test
    fun `调色板顺序与旧小程序 colors-js 一致`() {
        // 从旧版迁移过来的用户看到的配色不该有突兀变化
        assertThat(CourseColors.palette.map { it.key }).containsExactly(
            "red", "olive", "green", "hiwamoegi", "cyan", "grey",
            "blue", "pink", "yellow", "mauve", "purple",
        ).inOrder()
        assertThat(CourseColors.size).isEqualTo(11)
    }

    @Test
    fun `颜色值与旧小程序的 rgba 一致`() {
        // colors.js: red: 'rgba(215,84,85)'
        assertThat(CourseColors.byKey("red").argb).isEqualTo(0xFFD75455.toInt())
        assertThat(CourseColors.byKey("pink").argb).isEqualTo(0xFFE16B8C.toInt())
        assertThat(CourseColors.byKey("blue").argb).isEqualTo(0xFFA4D6F9.toInt())
        // 默认主题色是 pink（旧 config.js 的 defaultColor）
        assertThat(CourseColors.DEFAULT.key).isEqualTo("pink")
    }

    @Test
    fun `未知 key 回退到默认色而不抛异常`() {
        // 数据库里可能存着旧版本的 key
        assertThat(CourseColors.byKey("不存在的颜色")).isEqualTo(CourseColors.DEFAULT)
        assertThat(CourseColors.byKey(null)).isEqualTo(CourseColors.DEFAULT)
    }

    @Test
    fun `自动配色对输入顺序免疫 - 这是相对旧版的改进`() {
        // 旧版是 mark++ % length，mark 随数组遍历顺序递增，
        // 服务端调整排序或用户插入一门课，全部课程的颜色都会重新洗牌。
        val names = listOf("高等数学", "大学英语", "程序设计", "体育", "思政课")
        val a = CourseColors.assign(names)
        val b = CourseColors.assign(names.shuffled())
        val c = CourseColors.assign(names.reversed())
        assertThat(a).isEqualTo(b)
        assertThat(a).isEqualTo(c)
    }

    @Test
    fun `同名课程拿到同一个颜色`() {
        val m = CourseColors.assign(listOf("高数", "高数", "英语"))
        assertThat(m["高数"]).isEqualTo(m["高数"])
        assertThat(m["高数"]).isNotEqualTo(m["英语"])
    }

    @Test
    fun `已持久化的配色不会被重新分配`() {
        val names = listOf("A", "B", "C")
        val first = CourseColors.assign(names)
        // 模拟"B 已经存过颜色，现在新增了一门排在它前面的课"
        val persisted = mapOf("B" to first.getValue("B").key)
        val second = CourseColors.assign(listOf("新课", "A", "B", "C"), persisted)
        assertThat(second["B"]).isEqualTo(first["B"])   // B 的颜色纹丝不动
        assertThat(second).containsKey("新课")
    }

    @Test
    fun `课程数超过调色板大小时循环取色且不崩`() {
        val names = (1..30).map { "课程$it" }
        val m = CourseColors.assign(names)
        assertThat(m).hasSize(30)
        assertThat(m.values.map { it.key }).containsAtLeastElementsIn(CourseColors.palette.map { it.key })
    }

    @Test
    fun `新课程优先用没被占用的颜色`() {
        val m = CourseColors.assign(listOf("A", "B", "C"))
        val keys = m.values.map { it.key }
        assertThat(keys.toSet()).hasSize(3)   // 三门课三种颜色，不重色
    }
}

/** [ScheduleGridBuilder] 的测试 —— 尤其是并排冲突布局。 */
class ScheduleGridBuilderTest {

    private val term = Term(2025, 1)
    private val timetable = CampusTimetable.of(Campus.UNIVERSITY_CITY)
    private val calendar = TermCalendar(LocalDate.of(2025, 9, 1))
    private val builder = ScheduleGridBuilder(timetable, calendar)

    /** 2025-09-08 是第 2 周的周一。 */
    private val week2Monday = LocalDate.of(2025, 9, 8)
    private val nowInWeek2 = LocalDateTime.of(week2Monday, LocalTime.of(7, 0))

    private fun course(
        name: String,
        day: Int,
        start: Int,
        count: Int = 2,
        weeks: Set<Int> = (1..16).toSet(),
        source: CourseSource = CourseSource.SCHOOL,
        room: String = "教5-301",
    ) = Course(
        term = term, name = name, dayOfWeek = day,
        startSection = start, sectionCount = count,
        weeks = weeks, classroom = room, source = source,
    )

    @Test
    fun `按周过滤 - 不在本周的课不出现`() {
        val grid = builder.buildWeek(
            listOf(
                course("每周都上", 1, 1),
                course("只有第5周", 1, 3, weeks = setOf(5)),
            ),
            week = 2, now = nowInWeek2,
        )
        val names = grid.day(1).blocks.map { it.course.name }
        assertThat(names).containsExactly("每周都上")
    }

    @Test
    fun `色块的时刻由作息表决定`() {
        val grid = builder.buildWeek(listOf(course("高数", 1, 1, 2)), week = 2, now = nowInWeek2)
        val block = grid.day(1).blocks.single()
        // 大学城第 1 节 8:30 起，第 2 节 10:05 止
        assertThat(block.startMinute).isEqualTo(8 * 60 + 30)
        assertThat(block.endMinute).isEqualTo(10 * 60 + 5)
        assertThat(block.startClock).isEqualTo("08:30")
        assertThat(block.endClock).isEqualTo("10:05")
        assertThat(block.sectionLabel).isEqualTo("1-2节")
        assertThat(block.durationMinutes).isEqualTo(95)
    }

    @Test
    fun `单节课的标签不带范围`() {
        val grid = builder.buildWeek(listOf(course("体育", 3, 7, 1)), week = 2, now = nowInWeek2)
        assertThat(grid.day(3).blocks.single().sectionLabel).isEqualTo("7节")
    }

    @Test
    fun `七天固定七列，索引 0 是周一`() {
        val grid = builder.buildWeek(emptyList(), week = 2, now = nowInWeek2)
        assertThat(grid.days).hasSize(7)
        assertThat(grid.days.map { it.dayOfWeek }).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(grid.day(1).date).isEqualTo(week2Monday)
        assertThat(grid.day(7).date).isEqualTo(week2Monday.plusDays(6))
        assertThat(grid.isEmpty).isTrue()
        assertThat(grid.blockCount).isEqualTo(0)
    }

    // ------------------------------------------------------------------ 并排冲突布局

    @Test
    fun `完全不重叠的课各占满整宽`() {
        val grid = builder.buildWeek(
            listOf(course("A", 1, 1, 2), course("B", 1, 5, 2)),
            week = 2, now = nowInWeek2,
        )
        val blocks = grid.day(1).blocks
        assertThat(blocks).hasSize(2)
        assertThat(blocks.all { it.columnCount == 1 && it.columnIndex == 0 }).isTrue()
    }

    @Test
    fun `完全重叠的两门课并排各占一半`() {
        val grid = builder.buildWeek(
            listOf(
                course("A", 1, 1, 2, room = "教5-301"),
                course("B", 1, 1, 2, room = "教6-101"),   // 同学期同星期的冲突（例如重修）
            ),
            week = 2, now = nowInWeek2,
        )
        val blocks = grid.day(1).blocks
        assertThat(blocks).hasSize(2)
        assertThat(blocks.map { it.columnIndex }).containsExactly(0, 1)
        assertThat(blocks.all { it.columnCount == 2 }).isTrue()
    }

    @Test
    fun `三门课互相重叠时各占三分之一`() {
        val grid = builder.buildWeek(
            listOf(
                course("A", 1, 1, 4),
                course("B", 1, 2, 3),
                course("C", 1, 3, 2),
            ),
            week = 2, now = nowInWeek2,
        )
        val blocks = grid.day(1).blocks
        assertThat(blocks).hasSize(3)
        assertThat(blocks.all { it.columnCount == 3 }).isTrue()
        assertThat(blocks.map { it.columnIndex }).containsExactly(0, 1, 2)
    }

    @Test
    fun `按簇分配列数 - 一门跨全天的课不会把不冲突的课也压窄`() {
        // 旧版直接叠着画，后画的盖住先画的，用户根本看不到被盖住的课。
        // 全局统一 columnCount 也不对：一门 1-12 节的课会让所有课都变成 1/2 宽。
        // 正确做法是按"传递重叠"分簇，簇内统一列数。
        val grid = builder.buildWeek(
            listOf(
                course("整天实验", 1, 1, 12),   // 8:30 - 20:55
                course("早上的课", 2, 1, 2),     // 另一天，完全无关
            ),
            week = 2, now = nowInWeek2,
        )
        val day1 = grid.day(1).blocks.single()
        val day2 = grid.day(2).blocks.single()
        assertThat(day1.columnCount).isEqualTo(1)   // 它自己一簇
        assertThat(day2.columnCount).isEqualTo(1)   // 不受影响
    }

    @Test
    fun `首尾相接的课不算冲突`() {
        // A 结束于 10:05，B 从 10:25 开始（第 3 节）—— 中间隔着课间，不重叠
        val grid = builder.buildWeek(
            listOf(course("A", 1, 1, 2), course("B", 1, 3, 2)),
            week = 2, now = nowInWeek2,
        )
        assertThat(grid.day(1).blocks.all { it.columnCount == 1 }).isTrue()
    }

    // ------------------------------------------------------------------ 状态

    @Test
    fun `BlockStatus 按当前时刻判定`() {
        val courses = listOf(
            course("第1-2节", 1, 1, 2),
            course("第5-6节", 1, 5, 2),
        )
        // 周一 9:00 —— 第 1-2 节正在上，第 5-6 节还没开始
        val during = builder.buildWeek(courses, week = 2, now = LocalDateTime.of(week2Monday, LocalTime.of(9, 0)))
        val blocks = during.day(1).blocks.associateBy { it.course.name }
        assertThat(blocks.getValue("第1-2节").status).isEqualTo(BlockStatus.ONGOING)
        assertThat(blocks.getValue("第5-6节").status).isEqualTo(BlockStatus.UPCOMING)

        // 周一 21:00 —— 都上完了
        val after = builder.buildWeek(courses, week = 2, now = LocalDateTime.of(week2Monday, LocalTime.of(21, 0)))
        assertThat(after.day(1).blocks.all { it.status == BlockStatus.FINISHED }).isTrue()

        // 周一 7:00 —— 都还没开始
        val before = builder.buildWeek(courses, week = 2, now = LocalDateTime.of(week2Monday, LocalTime.of(7, 0)))
        assertThat(before.day(1).blocks.all { it.status == BlockStatus.UPCOMING }).isTrue()
    }

    @Test
    fun `过去日期的课一律标记为已完成`() {
        val courses = listOf(course("上周的课", 1, 1, 2, weeks = setOf(1)))
        // 站在第 2 周回头看第 1 周
        val grid = builder.buildWeek(courses, week = 1, now = LocalDateTime.of(week2Monday, LocalTime.of(9, 0)))
        assertThat(grid.day(1).blocks.single().status).isEqualTo(BlockStatus.FINISHED)
    }

    @Test
    fun `未来的课一律标记为未开始`() {
        val courses = listOf(course("下周的课", 1, 1, 2, weeks = setOf(5)))
        val grid = builder.buildWeek(courses, week = 5, now = LocalDateTime.of(week2Monday, LocalTime.of(9, 0)))
        assertThat(grid.day(1).blocks.single().status).isEqualTo(BlockStatus.UPCOMING)
    }

    // ------------------------------------------------------------------ 考试

    @Test
    fun `考试按时刻落到对应的节次上`() {
        val exam = Exam(
            term = term, courseName = "高等数学", date = week2Monday,
            startTime = LocalTime.of(8, 30), endTime = LocalTime.of(10, 5),
            classroom = "教5-301", campus = Campus.UNIVERSITY_CITY,
            category = "正常考试", arrangementType = "集中安排",
        )
        val grid = builder.buildWeek(emptyList(), listOf(exam), week = 2, now = nowInWeek2)
        val block = grid.day(1).blocks.single()
        assertThat(block.isExam).isTrue()
        assertThat(block.course.source).isEqualTo(CourseSource.EXAM)
        assertThat(block.course.name).isEqualTo("高等数学")
        // 8:30-10:05 覆盖第 1、2 节
        assertThat(block.course.startSection).isEqualTo(1)
        assertThat(block.course.sectionCount).isEqualTo(2)
        assertThat(block.startMinute).isEqualTo(8 * 60 + 30)
        assertThat(block.endMinute).isEqualTo(10 * 60 + 5)
        assertThat(block.course.description).contains("考试")
    }

    @Test
    fun `不在本周的考试不出现`() {
        val exam = Exam(term = term, courseName = "期末考", date = week2Monday.plusWeeks(3))
        val grid = builder.buildWeek(emptyList(), listOf(exam), week = 2, now = nowInWeek2)
        assertThat(grid.isEmpty).isTrue()
    }

    @Test
    fun `考试与课程冲突时并排显示`() {
        val grid = builder.buildWeek(
            listOf(course("正常的课", 1, 1, 2)),
            listOf(
                Exam(
                    term = term, courseName = "补考", date = week2Monday,
                    startTime = LocalTime.of(8, 30), endTime = LocalTime.of(10, 5),
                ),
            ),
            week = 2, now = nowInWeek2,
        )
        val blocks = grid.day(1).blocks
        assertThat(blocks).hasSize(2)
        assertThat(blocks.all { it.columnCount == 2 }).isTrue()
    }

    @Test
    fun `sectionsForTimeRange 用重叠而非包含来判定`() {
        // 10:05 正好是第 2 节结束、第 3 节 10:25 才开始，不该把第 3 节算进来
        val run = builder.sectionsForTimeRange(LocalTime.of(8, 30), LocalTime.of(10, 5))
        assertThat(run).isEqualTo(SectionRun(1, 2))
        // 跨越午休：11:00-14:00 覆盖第 3 节(10:25-11:10)、第 4 节(11:15-12:00)、
        // 第 5 节(13:50-14:35)。第 6 节 14:40 才开始，不该被算进来。
        // 注意 SectionRun 是 (start, count) 而不是 (start, end)：3 节起、共 3 节 → 3..5。
        assertThat(builder.sectionsForTimeRange(LocalTime.of(11, 0), LocalTime.of(14, 0)))
            .isEqualTo(SectionRun(3, 3))
        // 缺结束时间时用开始时间
        assertThat(builder.sectionsForTimeRange(LocalTime.of(15, 30), null))
            .isEqualTo(SectionRun(7, 1))
        // 完全缺时间返回 null，调用方回退到占第 1 节
        assertThat(builder.sectionsForTimeRange(null, null)).isNull()
    }

    @Test
    fun `落在课间或作息表之外的时间会吸附到最近的一节`() {
        // 12:30 在午休里（第 4 节 12:00 结束，第 5 节 13:50 开始）
        val run = builder.sectionsForTimeRange(LocalTime.of(12, 30), LocalTime.of(13, 0))
        assertThat(run).isNotNull()
        assertThat(run!!.count).isEqualTo(1)
    }

    // ------------------------------------------------------------------ 日视图与纵向范围

    @Test
    fun `buildDay 返回指定日期的那一列`() {
        val courses = listOf(course("周三的课", 3, 1, 2))
        val day = builder.buildDay(courses, date = week2Monday.plusDays(2), now = nowInWeek2)
        assertThat(day.dayOfWeek).isEqualTo(3)
        assertThat(day.date).isEqualTo(week2Monday.plusDays(2))
        assertThat(day.blocks.single().course.name).isEqualTo("周三的课")
    }

    @Test
    fun `verticalRange 覆盖作息表与实际色块的并集`() {
        // 空网格 = 作息表首尾
        val empty = builder.buildWeek(emptyList(), week = 2, now = nowInWeek2)
        assertThat(empty.verticalRange.first).isEqualTo(8 * 60 + 30)
        assertThat(empty.verticalRange.last).isEqualTo(20 * 60 + 55)

        // 有一门晚于末节的自定义课 → 下界被撑大
        val late = course("社团活动", 1, 12, 1, source = CourseSource.CUSTOM)
        val grid = builder.buildWeek(listOf(late), week = 2, now = nowInWeek2)
        assertThat(grid.verticalRange.last).isAtLeast(20 * 60 + 55)
    }

    @Test
    fun `自定义课程与学校课程混排且能被区分`() {
        val grid = builder.buildWeek(
            listOf(
                course("高数", 1, 1, 2),
                course("篮球社", 1, 9, 2, source = CourseSource.CUSTOM, room = "操场"),
            ),
            week = 2, now = nowInWeek2,
        )
        val blocks = grid.day(1).blocks.associateBy { it.course.name }
        assertThat(blocks.getValue("高数").isCustom).isFalse()
        assertThat(blocks.getValue("篮球社").isCustom).isTrue()
        assertThat(blocks.getValue("篮球社").isExam).isFalse()
    }

    @Test
    fun `颜色按课程名分配`() {
        val assignment = CourseColors.assign(listOf("高数", "英语"))
        val colored = ScheduleGridBuilder(timetable, calendar, assignment)
            .buildWeek(listOf(course("高数", 1, 1, 2), course("英语", 1, 5, 2)), week = 2, now = nowInWeek2)
        val blocks = colored.day(1).blocks.associateBy { it.course.name }
        assertThat(blocks.getValue("高数").color).isEqualTo(assignment["高数"])
        assertThat(blocks.getValue("英语").color).isEqualTo(assignment["英语"])
        assertThat(blocks.getValue("高数").color).isNotEqualTo(blocks.getValue("英语").color)
    }

    @Test
    fun `没有配色表时回退到默认色而不崩`() {
        val grid = builder.buildWeek(listOf(course("高数", 1, 1, 2)), week = 2, now = nowInWeek2)
        assertThat(grid.day(1).blocks.single().color).isEqualTo(CourseColors.DEFAULT)
    }
}
