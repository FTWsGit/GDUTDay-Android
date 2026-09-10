package com.gdutday.data.repository

import com.gdutday.core.common.KnownSemesterStarts
import com.gdutday.core.common.TermCalendar
import com.gdutday.core.database.Mappers
import com.gdutday.core.database.SemesterStartSource
import com.gdutday.core.database.TermMetaEntity
import com.gdutday.core.model.Course
import com.gdutday.core.model.Term
import java.time.LocalDate

/**
 * 「第 1 周周一」的解析结果。
 *
 * 之所以要把日期和来源一起返回，而不是只返回日期：来源直接决定 UI 的信任级别。
 * 只有 [SemesterStartSource.GUESSED] 需要提示用户"去设置里校准"，
 * 其余三级即使与真实校历差一两天，也不会造成"整周课错位"的观感崩坏。
 *
 * @property date 第 1 周的周一。
 * @property source 该日期来自哪一级优先级，会持久化进 `term_meta.start_source`。
 */
public data class SemesterStartResult(
    public val date: LocalDate,
    public val source: SemesterStartSource,
)

/**
 * 决定某学期的「第 1 周周一」。
 *
 * ## 为什么需要一个独立的纯逻辑对象
 *
 * 开学日期是整个课表日期换算的锚点，但它**不在教务系统的任何课表接口里**。
 * 只能从四个互相独立的来源里挑一个，而且这一次挑错，后面所有"第 N 周是几号"
 * 全都会错，所以这段决策必须能被单元测试单独钉死，不能藏在 Sync 的大流程里。
 *
 * ## 四级优先级的理由（来自 [com.gdutday.core.common.TermCalendar] 类注释）
 *
 * 1. **用户手填** —— 用户已经明确表达过日期，任何自动值都不得覆盖它。
 *    这是唯一一个"跨同步持久有效"的级别，判断依据是 `start_source == USER`
 *    而不是"数据库里有值"（自动反推的值也会落盘）。
 * 2. **从课表 `pkrq` 反推** —— `getDataList` 的每行都带具体上课日期与周次，
 *    两者相减足以唯一确定学期历，是最可靠的自动来源（[TermCalendar.deriveSemesterStart]
 *    还用众数抗单条脏数据）。
 * 3. **内置已知表** —— 当课表走 `xsAllKbList`（不返回 `pkrq`）时反推不可用，
 *    只能查 [KnownSemesterStarts]。这张表需要人工维护，但命中时是权威的。
 * 4. **瞎猜** —— 第一学期猜 9 月第一个周一、第二学期猜 2 月下旬，
 *    误差可达两周。这条分支必须返回 [SemesterStartSource.GUESSED] 让 UI 提示校准。
 *
 * 注意 2 与 1 的顺序：**即使用户之前手填过，只要当前来源不是 USER，就重新反推**。
 * 因为自动值可能来自上个学期或一次失败同步的兜底猜测，重算没有坏处。
 */
public object SemesterStartResolver {

    /**
     * @param term 正在解析的学期。
     * @param existing 数据库里已有的 `term_meta` 行；null 表示该学期还没落过盘。
     *   只有它的 `startSource == USER` 且日期可解析时才会短路返回。
     * @param courses 本次同步拿到的该学期课程（可含自定义课）。没有带 `classDates` 的课程时反推会失败。
     * @param knownStart 内置表的查询结果，默认按 [term] 查 [KnownSemesterStarts]。
     *   暴露成参数是为了让单元测试能构造"表里有/表里没有"两种世界。
     */
    public fun resolve(
        term: Term,
        existing: TermMetaEntity?,
        courses: List<Course>,
        knownStart: LocalDate? = KnownSemesterStarts.startOf(term),
    ): SemesterStartResult {
        // 1. 用户手填：唯一允许跨同步存活的来源。
        if (existing != null && SemesterStartSource.fromName(existing.startSource) == SemesterStartSource.USER) {
            Mappers.parseDateLenient(existing.semesterStart)?.let {
                return SemesterStartResult(it, SemesterStartSource.USER)
            }
            // 来源标记为 USER 但日期不可解析（旧版本脏数据）：落回自动流程，
            // 不因为一行坏数据让整个学期没有日历。
        }

        // 2. 从 pkrq 反推。samplesFrom 会在 classDates 与 weeks 长度不一致时返回空，
        //    等价于"这门课不参与反推"，不会污染众数。
        val samples = courses.flatMap { TermCalendar.samplesFrom(it) }
        TermCalendar.deriveSemesterStart(samples)?.let {
            return SemesterStartResult(it, SemesterStartSource.DERIVED)
        }

        // 3. 内置已知表。
        knownStart?.let {
            return SemesterStartResult(it, SemesterStartSource.KNOWN_TABLE)
        }

        // 4. 兜底猜测，必须带 GUESSED 让 UI 提示校准。
        return SemesterStartResult(
            date = TermCalendar.guessSemesterStart(term.year, term.semester),
            source = SemesterStartSource.GUESSED,
        )
    }
}
