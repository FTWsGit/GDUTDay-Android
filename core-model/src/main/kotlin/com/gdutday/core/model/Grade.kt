package com.gdutday.core.model

/**
 * 一门课的成绩。
 *
 * 数据源：`POST https://jxfw.gdut.edu.cn/xskccjxx!getDataList.action`
 * 表单参数：`xnxqdm`(空=全部学期) `jhlxdm=""` `sort=xnxqdm` `order=asc` `page` `rows`
 *
 * 原始字段对照（实测，来自旧 Java 后端 `ExamScoreServiceImpl`）：
 * ```
 * kcmc    课程名称        xnxqmc   学期名称（中文，如 "2024-2025学年第一学期"）
 * zcj     总成绩          xnxqdm   学期代码（长码）
 * cjjd    成绩绩点        xf       学分
 * kcdlmc  课程大类名称     kcflmc   课程分类名称
 * xdfsmc  修读方式名称（必修/任选/限选…）
 * ```
 *
 * ## ⚠ 「劳动教育」的教务处 bug
 *
 * 当以 `xnxqdm=""`（查询全部学期）请求时，**「劳动教育」这门课的 `zcj` 和 `cjjd` 会返回空**，
 * 但带上具体的 `xnxqdm` 再查一次就有值。这是教务处接口的问题，旧后端在
 * `ExamScoreServiceImpl` 里做了兜底（遇到 `kcmc == "劳动教育"` 就用该行的 `xnxqdm` 重查一次）。
 *
 * 本项目把这个兜底放在 `data-gdut` 的 `JxfwClient.fetchGrades()` 里，
 * 见该方法的 `patchLaborEducation` 参数（默认开启）。
 *
 * @property scoreText 总成绩原始文本。可能是数字（`"87"`）、等级（`"优秀"`、`"合格"`）或空串。
 * @property score 解析出的数值分数；等级制/缺考/空值时为 null。
 * @property gpa 该课绩点（`cjjd`）。
 * @property credit 学分（`xf`）。
 */
public data class Grade(
    public val id: Long = 0L,
    public val termName: String,
    public val term: Term? = null,
    public val courseName: String,
    public val courseCategory: String = "",
    public val courseSubCategory: String = "",
    public val studyMode: String = "",
    public val scoreText: String = "",
    public val score: Double? = null,
    public val gpa: Double? = null,
    public val credit: Double? = null,
) {
    /**
     * 是否计入绩点。
     *
     * 规则（广工本科）：有数值成绩、有绩点值、学分 > 0 即计入。
     *
     * **挂科也计入**：绩点 0 是平均绩点的一部分。早期实现漏掉了 `score < 60` 的课，
     * 会让加权绩点虚高，与教务系统的算法不一致。
     *
     * 等级制成绩（优秀/良好/合格/不合格）不参与绩点计算，`cjjd` 通常为空。
     */
    public val countsTowardsGpa: Boolean
        get() = score != null && gpa != null && (credit ?: 0.0) > 0.0

    /**
     * 是否通过（学分是否计入总学分）。
     *
     * 数值成绩以 60 分为线；等级制成绩按"通过类"词判定；
     * 「缓考」既不算通过也不算挂科（成绩尚未确定）。
     */
    public val isPassed: Boolean
        get() {
            val s = score
            return if (s != null) s >= 60.0 else scoreText in PASS_WORDS
        }

    /** 是否挂科。数值成绩 < 60，或等级制为「不合格 / 不通过 / 缺考」。 */
    public val isFailed: Boolean
        get() {
            val s = score
            return if (s != null) s < 60.0 else scoreText in FAIL_WORDS
        }

    public companion object {
        /** 等级制成绩 → 是否通过。用于过滤"不合格"的课。 */
        private val GRADE_WORDS = setOf("优秀", "良好", "中等", "及格", "合格", "通过", "不合格", "不通过", "缺考", "缓考")

        /** 等级制里表示"通过"的词。 */
        private val PASS_WORDS = setOf("优秀", "良好", "中等", "及格", "合格", "通过")

        /** 等级制里表示"未通过"的词。「缓考」不在其中（成绩未定）。 */
        private val FAIL_WORDS = setOf("不合格", "不通过", "缺考")

        /**
         * 把 `zcj` 解析成数值。
         *
         * 处理这些真实存在的情况：`"87"`、`"87.0"`、`"优秀"`、`""`、`null`、`"缺考"`、`"-1"`。
         * 非数值一律返回 null，**不抛异常** —— 成绩页要能容忍教务处返回脏数据。
         */
        public fun parseScore(raw: String?): Double? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s in GRADE_WORDS) return null
            return s.toDoubleOrNull()
        }

        public fun parseDouble(raw: String?): Double? = raw?.trim()?.toDoubleOrNull()
    }
}

/**
 * 一个学期的成绩汇总。
 *
 * @property termName 教务系统给的学期中文名，作为分组键（因为 `xnxqdm` 在部分行里可能缺失）。
 * @property weightedGpa 加权平均绩点 = Σ(绩点×学分) / Σ(学分)，只对 [Grade.countsTowardsGpa] 的行计算。
 * @property totalCredit 该学期获得的总学分（成绩 ≥ 60 或等级制"通过"的课）。
 */
public data class TermGradeSummary(
    public val termName: String,
    public val term: Term? = null,
    public val grades: List<Grade> = emptyList(),
) {
    public val weightedGpa: Double?
        get() {
            val rows = grades.filter { it.countsTowardsGpa }
            val creditSum = rows.sumOf { it.credit ?: 0.0 }
            if (creditSum <= 0.0) return null
            return rows.sumOf { (it.gpa ?: 0.0) * (it.credit ?: 0.0) } / creditSum
        }

    public val totalCredit: Double
        get() = grades.filter { it.isPassed }
            .sumOf { it.credit ?: 0.0 }

    public val failedCount: Int
        get() = grades.count { it.isFailed }
}
