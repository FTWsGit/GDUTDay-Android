package com.gdutday.core.common

import com.gdutday.core.model.Term
import java.time.LocalDate

/**
 * 已知的学期开学日期表（第 1 周的周一）。
 *
 * ## 为什么需要这张表
 *
 * 没有后端可以问"这学期几号开学"，而周次换算必须有这个锚点。
 * [TermCalendar] 的取值优先级是：
 * ```
 * 用户手动指定  >  从 pkrq 反推  >  本表  >  TermCalendar.guessSemesterStart() 瞎猜
 * ```
 * 本表是第三道防线：当课表走的是 `xsAllKbList` 接口（不返回 `pkrq`）时，
 * 反推不可用，就得靠这里。
 *
 * ## 维护方式
 *
 * **每学期开学后需要补一行。** 这是本项目唯一需要人工周期性维护的数据。
 * 补充方法（任选其一）：
 * 1. 用 `xsgrkbcx!getDataList.action` 拿一学期的课表，任取一行的 `pkrq` 和 `zc`，
 *    `开学周一 = pkrq - (zc-1) 周 - (xq-1) 天`；跑一下 `TermCalendarTest` 里的
 *    `deriveSemesterStart` 用例即可自动算出。
 * 2. 直接看校历（教务处每年发布）。
 *
 * 已知数据来源标注：
 * - `2025-2026 学年第一学期 = 2025-09-01`：取自旧 Java 后端
 *   `gdutday-wechat3.0-java/src/main/resources/application.yml` 的 `gdutday.admissionDate: "2025.9.1"`。
 *   该日期恰好是周一，与"第 1 周从周一开始"的约定一致。
 * - 其余条目为空 —— **不要凭印象编造日期**，宁可留空让 `guessSemesterStart` 兜底并在 UI 提示校准。
 */
public object KnownSemesterStarts {

    private val table: Map<Term, LocalDate> = mapOf(
        Term(2025, 1) to LocalDate.of(2025, 9, 1),
    )

    /** 查表。未收录返回 null。 */
    public fun startOf(term: Term): LocalDate? = table[term]

    /** 表里已收录的学期，按时间倒序。用于设置页的"已知学期"提示。 */
    public val knownTerms: List<Term> get() = table.keys.sortedDescending()

    /** 表里最新的学期。 */
    public val latestKnownTerm: Term? get() = knownTerms.firstOrNull()

    /**
     * 解析旧后端 `application.yml` 里那种 `"2025.9.1"` / `"2025-09-01"` / `"2025/9/1"` 格式。
     *
     * 之所以单独提供，是因为用户可能直接从旧配置里复制日期过来，
     * 而 `LocalDate.parse` 只认 ISO 格式（月和日必须补零）。
     *
     * @return 解析失败返回 null。
     */
    public fun parseFlexibleDate(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split('.', '-', '/', '年', '月').map { it.trim().removeSuffix("日") }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val (y, m, d) = parts
        return runCatching {
            LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        }.getOrNull()
    }
}
