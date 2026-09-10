package com.gdutday.data.repository

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.common.KnownSemesterStarts
import com.gdutday.core.common.TermCalendar
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.model.Course
import com.gdutday.core.model.Term
import org.junit.Test
import java.time.LocalDate

/**
 * 开学日期的四级优先级。
 *
 * 这是整个课表日期换算的锚点，判错会让"第 N 周"整体偏移，所以四条分支逐条钉死，
 * 外加"脏数据不能毁掉整个学期"的降级行为。
 */
class SemesterStartResolverTest {

    private val term20251 = Term(2025, 1)

    /** 反推样本：周一、第 1 周、2025-09-01 → 第 1 周周一就是 2025-09-01。 */
    private fun courseWithDerivableDate(date: LocalDate = LocalDate.of(2025, 9, 1)): Course = Course(
        term = term20251,
        name = "高等数学",
        dayOfWeek = 1,
        startSection = 1,
        sectionCount = 2,
        weeks = setOf(1),
        classDates = listOf(date),
    )

    @Test
    fun `用户手填优先于反推与内置表`() {
        val custom = LocalDate.of(2025, 8, 25)
        val existing = TermMetaEntity(
            termCode = "20251",
            semesterStart = custom.toString(),
            startSource = SemesterStartSource.USER.name,
        )

        val result = SemesterStartResolver.resolve(
            term = term20251,
            existing = existing,
            courses = listOf(courseWithDerivableDate()),
            knownStart = LocalDate.of(2025, 9, 1),
        )

        assertThat(result.date).isEqualTo(custom)
        assertThat(result.source).isEqualTo(SemesterStartSource.USER)
    }

    @Test
    fun `非USER来源时重新反推而不是沿用旧值`() {
        // 旧值是上一次失败同步留下的兜底猜测，本次课表带来了 pkrq，应当以反推为准。
        val existing = TermMetaEntity(
            termCode = "20251",
            semesterStart = "2025-08-11",
            startSource = SemesterStartSource.GUESSED.name,
        )

        val result = SemesterStartResolver.resolve(
            term = term20251,
            existing = existing,
            courses = listOf(courseWithDerivableDate(LocalDate.of(2025, 9, 15))),
            knownStart = LocalDate.of(2025, 9, 1),
        )

        // 9/15 是周一、第 1 周 → 第 1 周周一 = 9/15（这里课程 metadata 与日期自洽）
        assertThat(result.date).isEqualTo(LocalDate.of(2025, 9, 15))
        assertThat(result.source).isEqualTo(SemesterStartSource.DERIVED)
    }

    @Test
    fun `没有pkrq时回退到内置表`() {
        val known = LocalDate.of(2025, 9, 1)

        val result = SemesterStartResolver.resolve(
            term = term20251,
            existing = null,
            courses = emptyList(),
            knownStart = known,
        )

        assertThat(result.date).isEqualTo(known)
        assertThat(result.source).isEqualTo(SemesterStartSource.KNOWN_TABLE)
    }

    @Test
    fun `内置表也没有时猜测并标注GUESSED`() {
        val term = Term(2030, 2)

        val result = SemesterStartResolver.resolve(
            term = term,
            existing = null,
            courses = emptyList(),
            knownStart = null,
        )

        assertThat(result.source).isEqualTo(SemesterStartSource.GUESSED)
        assertThat(result.date).isEqualTo(TermCalendar.guessSemesterStart(2030, 2))
    }

    @Test
    fun `用户来源但日期不可解析时降级到反推而不是崩溃`() {
        val existing = TermMetaEntity(
            termCode = "20251",
            semesterStart = "不是日期",
            startSource = SemesterStartSource.USER.name,
        )

        val result = SemesterStartResolver.resolve(
            term = term20251,
            existing = existing,
            courses = listOf(courseWithDerivableDate()),
            knownStart = null,
        )

        assertThat(result.source).isEqualTo(SemesterStartSource.DERIVED)
        assertThat(result.date).isEqualTo(LocalDate.of(2025, 9, 1))
    }

    @Test
    fun `内置表对2025秋季学期的值符合文档`() {
        // 交叉校验：KnownSemesterStarts 里记录的 2025-2026 学年第一学期是 2025-09-01。
        assertThat(KnownSemesterStarts.startOf(term20251)).isEqualTo(LocalDate.of(2025, 9, 1))
    }
}
