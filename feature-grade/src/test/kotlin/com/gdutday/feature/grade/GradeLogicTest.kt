package com.gdutday.feature.grade

import com.gdutday.core.model.Grade
import com.gdutday.core.model.Term
import com.gdutday.core.model.TermGradeSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 成绩显示与绩点趋势的纯逻辑测试。
 *
 * 重点是等级制成绩：`score == null` 时必须显示 `scoreText`，
 * 任何"显示成 0 分或 --"的回归都会在这里被抓住。
 */
class GradeLogicTest {

    private fun grade(
        score: Double?,
        scoreText: String,
        gpa: Double? = null,
        credit: Double? = null,
    ) = Grade(
        termName = "2024-2025学年第一学期",
        courseName = "高等数学",
        scoreText = scoreText,
        score = score,
        gpa = gpa,
        credit = credit,
    )

    @Test
    fun `数值成绩去掉多余小数`() {
        assertThat(GradeLogic.scoreDisplay(grade(87.0, "87"))).isEqualTo("87")
        assertThat(GradeLogic.scoreDisplay(grade(87.5, "87.5"))).isEqualTo("87.5")
    }

    @Test
    fun `等级制成绩显示原文而不是 0 或 --`() {
        assertThat(GradeLogic.scoreDisplay(grade(null, "优秀"))).isEqualTo("优秀")
        assertThat(GradeLogic.scoreDisplay(grade(null, "合格"))).isEqualTo("合格")
        assertThat(GradeLogic.scoreDisplay(grade(null, "缺考"))).isEqualTo("缺考")
    }

    @Test
    fun `数值和等级都缺失时才回退 --`() {
        assertThat(GradeLogic.scoreDisplay(grade(null, ""))).isEqualTo("--")
        assertThat(GradeLogic.scoreDisplay(grade(null, "   "))).isEqualTo("--")
    }

    @Test
    fun `挂科判定只针对数值成绩`() {
        assertThat(GradeLogic.isFailed(grade(59.0, "59"))).isTrue()
        assertThat(GradeLogic.isFailed(grade(60.0, "60"))).isFalse()
        // 等级制没有数值，不参与挂科判定
        assertThat(GradeLogic.isFailed(grade(null, "不合格"))).isFalse()
    }

    @Test
    fun `绩点趋势跳过 null 并按学期升序`() {
        val summaries = listOf(
            TermGradeSummary("2025-2026学年第二学期", Term(2025, 2), listOf(grade(80.0, "80", 3.0, 2.0))),
            // weightedGpa 为 null（没有绩点数据）的学期必须被跳过
            TermGradeSummary("2025-2026学年第一学期", Term(2025, 1), listOf(grade(null, "优秀"))),
            TermGradeSummary("2024-2025学年第一学期", Term(2024, 1), listOf(grade(90.0, "90", 4.0, 3.0))),
        )

        val points = GradeLogic.trendPoints(summaries)
        assertThat(points).hasSize(2)
        // 左边旧、右边新
        assertThat(points.map { it.termName }).containsExactly(
            "2024-2025学年第一学期",
            "2025-2026学年第二学期",
        ).inOrder()
        assertThat(points.map { it.gpa }).containsExactly(4.0, 3.0).inOrder()
    }

    @Test
    fun `没有任何数值绩点时趋势为空`() {
        val summaries = listOf(
            TermGradeSummary("2024-2025学年第一学期", Term(2024, 1), listOf(grade(null, "合格"))),
        )
        assertThat(GradeLogic.trendPoints(summaries)).isEmpty()
    }

    @Test
    fun `加权绩点保留两位小数`() {
        assertThat(GradeLogic.formatGpa(3.5)).isEqualTo("3.50")
        assertThat(GradeLogic.formatGpa(4.0)).isEqualTo("4.00")
        assertThat(GradeLogic.formatGpa(2.345)).isEqualTo("2.35")
    }

    @Test
    fun `学分整数不带小数点`() {
        assertThat(GradeLogic.formatCredit(3.0)).isEqualTo("3")
        assertThat(GradeLogic.formatCredit(1.5)).isEqualTo("1.5")
    }

    @Test
    fun `学期短标签去掉冗余后缀`() {
        assertThat(GradeLogic.shortTermLabel("2024-2025学年第一学期")).isEqualTo("2024-2025第一")
    }
}
