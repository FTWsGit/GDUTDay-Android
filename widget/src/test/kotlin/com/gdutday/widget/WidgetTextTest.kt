package com.gdutday.widget

import com.gdutday.core.datastore.UserSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WidgetTextTest {

    @Test
    fun `不打码时原样返回`() {
        assertThat(WidgetText.mask("高等数学", blur = false)).isEqualTo("高等数学")
    }

    @Test
    fun `打码时生成等长的点号`() {
        val masked = WidgetText.mask("高等数学", blur = true)
        assertThat(masked).isEqualTo("····")
        assertThat(masked.length).isEqualTo("高等数学".length)
    }

    @Test
    fun `空字符串无论是否打码都保持为空`() {
        assertThat(WidgetText.mask("", blur = true)).isEmpty()
        assertThat(WidgetText.mask("", blur = false)).isEmpty()
    }

    @Test
    fun `只有两个开关都打开才在插件里打码`() {
        assertThat(WidgetText.shouldBlur(UserSettings())).isFalse()
        assertThat(
            WidgetText.shouldBlur(UserSettings(privacyBlurEnabled = true, privacyBlurInWidget = true)),
        ).isTrue()
        // 总开关关掉时，插件单独打开也不生效（避免"关了打码桌面还在打码"）。
        assertThat(
            WidgetText.shouldBlur(UserSettings(privacyBlurEnabled = false, privacyBlurInWidget = true)),
        ).isFalse()
        // 总开关打开但用户明确关掉插件打码。
        assertThat(
            WidgetText.shouldBlur(UserSettings(privacyBlurEnabled = true, privacyBlurInWidget = false)),
        ).isFalse()
    }
}
