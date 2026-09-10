package com.gdutday.core.model

/**
 * 学期。
 *
 * ## ⚠ 广工有两套学期编码，混用是历史 bug 的重灾区
 *
 * | 形式 | 例子 | 用在哪 |
 * |---|---|---|
 * | **短码** `YYYY + N`   | `20251`  | 页面 `<option>` 的显示、旧后端的 DTO、本项目内部存储 |
 * | **长码** `YYYY + NN`（= `xnxqdm`） | `202501` | jxfw 所有接口的 `xnxqdm` 请求参数 |
 *
 * 旧 Java 后端在两者之间来回换算，用的是
 * `(t / 10) * 100 + t % 10`（短→长）和
 * `s.substring(0, s.length - 2) + s.last()`（长→短），
 * 可读性极差且无法处理学期号 ≥ 10 的情况。
 *
 * 本项目改为**显式存 (year, semester) 两个字段**，两种编码都由计算属性派生，
 * 从此不存在"我手上这个字符串到底是哪种码"的问题。
 *
 * @property year 学年起始年。`2025` 表示 2025-2026 学年。
 * @property semester 学期序号，1 = 第一学期（秋季），2 = 第二学期（春季）。
 */
public data class Term(
    public val year: Int,
    public val semester: Int,
) : Comparable<Term> {

    init {
        require(year in 1900..2999) { "学年不合法: $year" }
        require(semester in 1..9) { "学期序号不合法: $semester" }
    }

    /** 短码，如 `"20251"`。用于本地存储与界面展示。 */
    public val shortCode: String get() = "$year$semester"

    /** 长码，即 jxfw 接口的 `xnxqdm` 参数，如 `"202501"`。 */
    public val xnxqdm: String get() = "$year${semester.toString().padStart(2, '0')}"

    /** `"2025-2026学年第一学期"` */
    public val displayName: String
        get() = "$year-${year + 1}学年第${if (semester == 1) "一" else "二"}学期"

    /** 第一学期跨自然年（9 月开学），第二学期在次年初（2 月开学）。用于粗略估算学期起止。 */
    public val approximateStartMonth: Int get() = if (semester == 1) 9 else 2

    override fun compareTo(other: Term): Int =
        compareValuesBy(this, other, { it.year }, { it.semester })

    override fun toString(): String = shortCode

    public companion object {

        /**
         * 从任意一种编码解析学期。
         *
         * 接受 `"20251"`、`"202501"`、`20251`、`202501`。规则：
         * - 长度 5 → 前 4 位是年，最后 1 位是学期
         * - 长度 6 → 前 4 位是年，后 2 位是学期
         *
         * @return 解析失败返回 null（接口改版时不要抛异常炸掉整个页面）。
         */
        public fun parse(raw: String?): Term? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s.any { !it.isDigit() }) return null
            // 先拆字段再校验，避免 Term 构造器的 require 把"解析失败"变成抛异常
            val year = s.substring(0, 4).toIntOrNull() ?: return null
            val semester = when (s.length) {
                5 -> s.substring(4).toIntOrNull()
                6 -> s.substring(4, 6).toIntOrNull()
                else -> null
            } ?: return null
            if (year !in 1900..2999 || semester !in 1..9) return null
            return Term(year, semester)
        }

        public fun parse(raw: Int?): Term? = parse(raw?.toString())
    }
}
