package com.gdutday.feature.grade

import com.gdutday.core.model.Grade
import com.gdutday.core.model.TermGradeSummary
import java.util.Locale
import kotlin.math.floor

/**
 * 绩点趋势图的一个数据点。
 *
 * @property termName 学期中文名，用于横轴标签。
 * @property gpa 该学期加权绩点。**只包含非 null 的学期** —— 没有数值绩点的学期
 *   （比如全班都是等级制打分）不能画成 0，否则折线会掉到谷底造成误导。
 */
public data class GpaTrendPoint(
    public val termName: String,
    public val gpa: Double,
)

/**
 * 成绩页纯逻辑。
 *
 * 抽出来的动机：等级制成绩的显示分支（`score == null` 但 `scoreText == "优秀"`）
 * 一旦写错，用户看到的是"0 分"或"--"，这类问题在真机上很容易被忽略却直接影响信任度。
 */
public object GradeLogic {

    /** 挂科线。教务系统数值成绩 < 60 记为不及格。 */
    public const val FAIL_THRESHOLD: Double = 60.0

    /**
     * 成绩的显示文本。
     *
     * 优先级：数值分数 > 原始等级文本 > "--"。
     * **等级制课程绝不能显示成 0 或 "--"** —— 它们的 `score` 是 null，
     * 但 `scoreText` 里明确写着"优秀"/"合格"。
     */
    public fun scoreDisplay(grade: Grade): String {
        grade.score?.let { return formatScore(it) }
        if (grade.scoreText.isNotBlank()) return grade.scoreText
        return "--"
    }

    /** 数值分数：整数不带小数点，非整数保留 1 位。 */
    public fun formatScore(score: Double): String =
        if (score == floor(score)) score.toInt().toString()
        else String.format(Locale.US, "%.1f", score)

    /** 加权绩点显示，固定 2 位小数。 */
    public fun formatGpa(gpa: Double): String = String.format(Locale.US, "%.2f", gpa)

    /** 学分显示：整数不带小数点。 */
    public fun formatCredit(credit: Double): String =
        if (credit == floor(credit)) credit.toInt().toString()
        else String.format(Locale.US, "%.1f", credit)

    /** 是否挂科。等级制（score == null）不参与判定。 */
    public fun isFailed(grade: Grade): Boolean {
        // 不能直接对 grade.score 做智能转换：它是跨模块的 public API 属性。
        val score = grade.score ?: return false
        return score < FAIL_THRESHOLD
    }

    /**
     * 从学期汇总里抽取折线图数据点。
     *
     * - 跳过 `weightedGpa == null` 的学期；
     * - 按学期升序排列（左旧右新）。Repository 给的是倒序（最新在前），
     *   这里统一成时间顺序，避免图表方向取决于数据源顺序。
     */
    public fun trendPoints(summaries: List<TermGradeSummary>): List<GpaTrendPoint> =
        summaries
            .filter { it.weightedGpa != null }
            .sortedWith(compareBy(nullsLast()) { it.term })
            .map { GpaTrendPoint(it.termName, it.weightedGpa!!) }

    /** 横轴学期短标签：`2024-2025学年第一学期` → `2024-2025第一`。 */
    public fun shortTermLabel(termName: String): String =
        termName.replace("学年", "").replace("学期", "").ifBlank { termName }

    /**
     * "全部学期"总览：把所有学期成绩合并成一个 [TermGradeSummary]。
     *
     * `weightedGpa` / `totalCredit` / `failedCount` 由 [TermGradeSummary] 的既有计算属性给出，
     * 天然排除 `countsTowardsGpa == false` 的课程，总学分只统计已通过课程。
     *
     * 排序规则：`term` 倒序（最新学期在前），同学期按课程名升序。
     */
    public fun overallSummary(grades: List<Grade>): TermGradeSummary {
        val sorted = grades.sortedWith(
            compareByDescending<Grade> { it.term }
                .thenBy { it.courseName }
        )
        return TermGradeSummary(termName = "全部", grades = sorted)
    }
}
