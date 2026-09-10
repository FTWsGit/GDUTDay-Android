package com.gdutday.core.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** 隐私打码的测试。重点是**等长**这条不变量。 */
class PrivacyMaskTest {

    @Test
    fun `常见课程名保留首尾 中间打点`() {
        assertThat("高等数学".privacyMasked()).isEqualTo("高··学")
        assertThat("数据结构与算法".privacyMasked()).isEqualTo("数·····法")
    }

    @Test
    fun `结果长度永远等于原串长度`() {
        val samples = listOf("A", "AB", "ABC", "高等数学", "计算机组成原理", "a b c d e")
        for (s in samples) {
            assertWithMessage("\"$s\" 的打码长度")
                .that(s.privacyMasked().length)
                .isEqualTo(s.length)
        }
    }

    @Test
    fun `空串原样返回`() {
        assertThat("".privacyMasked()).isEmpty()
    }

    @Test
    fun `长度 1 与 2 的短串整串打码 - 隐私优先`() {
        // 2 个字符时"保留首尾"等于不打码，这里选择完整隐藏
        assertThat("张".privacyMasked()).isEqualTo("·")
        assertThat("张三".privacyMasked()).isEqualTo("··")
    }

    @Test
    fun `打码不泄露原文内容`() {
        val masked = "李四老师".privacyMasked()
        assertThat(masked).isNotEqualTo("李四老师")
        assertThat(masked).doesNotContain("四")
        assertThat(masked).contains("·")
    }
}
