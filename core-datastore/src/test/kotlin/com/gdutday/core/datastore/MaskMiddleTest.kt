package com.gdutday.core.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [maskMiddle] 的边界测试。日志和设置页会直接展示它的输出，不能泄露中间段。 */
class MaskMiddleTest {

    @Test
    fun `空串不报错`() {
        assertThat("".maskMiddle()).isEmpty()
        assertThat("".maskMiddle(keepHead = 0, keepTail = 0)).isEmpty()
    }

    @Test
    fun `字符串短于保留位数时整串打码`() {
        // 3 + 3 = 6，长度 <= 6 全部打码
        assertThat("abc".maskMiddle()).isEqualTo("***")
        assertThat("abcdef".maskMiddle()).isEqualTo("******")
        assertThat("a".maskMiddle()).isEqualTo("*")
    }

    @Test
    fun `正常长度保留首尾`() {
        assertThat("3120001234".maskMiddle()).isEqualTo("312****234")
        assertThat("abcdefg".maskMiddle()).isEqualTo("abc*efg")
    }

    @Test
    fun `自定义保留位数`() {
        assertThat("3120001234".maskMiddle(keepHead = 4, keepTail = 2)).isEqualTo("3120****34")
        assertThat("3120001234".maskMiddle(keepHead = 0, keepTail = 0)).isEqualTo("**********")
    }

    @Test
    fun `打码后长度与原串一致`() {
        val raw = "3120001234"
        assertThat(raw.maskMiddle()).hasLength(raw.length)
    }

    @Test
    fun `中间段永远不出现在输出里`() {
        val raw = "3120001234"
        assertThat(raw.maskMiddle()).doesNotContain("000")
    }
}
