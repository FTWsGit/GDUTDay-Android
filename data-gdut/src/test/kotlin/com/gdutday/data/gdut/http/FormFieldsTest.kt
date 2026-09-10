package com.gdutday.data.gdut.http

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.URLDecoder

/**
 * [FormFields] 的单元测试。
 *
 * 这个类看着不起眼，但**表单编码错一个字符，登录就会以"密码错误"的形式失败**，
 * 排查方向会被彻底带偏。所以这里逐条验证。
 */
class FormFieldsTest {

    @Test
    fun `空 name 的字段会被编码成 =value - 这是登录能成功的关键`() {
        // 统一认证登录页的 pwdEncryptSalt 没有 name 属性，
        // 浏览器提交时它就是一个空名键值对。旧 Java 后端为此写了 tempMap.put("", salt)。
        val body = FormFields()
            .add("username", "3120012345")
            .add("", "xaOfScaw6epvgypH")
            .encode()

        assertThat(body).isEqualTo("username=3120012345&=xaOfScaw6epvgypH")
    }

    @Test
    fun `Base64 密文里的加号、斜杠、等号必须百分号编码`() {
        // AES 密文是 Base64，必然含 + / =。
        // 在 application/x-www-form-urlencoded 里 '+' 表示空格，
        // 不编码的话服务端收到的密文会多出空格，解密必然失败。
        val cipher = "abc+def/ghi=="
        val body = FormFields().add("password", cipher).encode()

        assertThat(body).isEqualTo("password=abc%2Bdef%2Fghi%3D%3D")
        // 反解回来必须与原文一致
        assertThat(URLDecoder.decode(body.removePrefix("password="), Charsets.UTF_8)).isEqualTo(cipher)
    }

    @Test
    fun `真实的 Base64 密文编码后不含裸露的加号或斜杠`() {
        val cipher = com.gdutday.data.gdut.auth.AuthServerCrypto.encryptPassword(
            "some-password",
            "xaOfScaw6epvgypH",
        )
        val encoded = FormFields.encodeComponent(cipher)
        assertThat(encoded).doesNotContain("+")
        assertThat(encoded).doesNotContain("/")
        assertThat(URLDecoder.decode(encoded, Charsets.UTF_8)).isEqualTo(cipher)
    }

    @Test
    fun `空格编码成加号而不是 %20 - 遵循 HTML 表单规范`() {
        assertThat(FormFields.encodeComponent("a b")).isEqualTo("a+b")
    }

    @Test
    fun `保留字符不被编码`() {
        // HTML 规范：A-Z a-z 0-9 - _ . * 不编码；空格仍然编码成 +
        assertThat(FormFields.encodeComponent("AZaz09-_.*")).isEqualTo("AZaz09-_.*")
        assertThat(FormFields.encodeComponent("AZaz09-_. *")).isEqualTo("AZaz09-_.+*")
    }

    @Test
    fun `中文按 UTF-8 逐字节编码`() {
        assertThat(FormFields.encodeComponent("教5")).isEqualTo("%E6%95%995")
    }

    @Test
    fun `多字节字符不会因符号扩展而编错`() {
        // Byte 在 Kotlin 里是有符号的，0xE4 会变成 -28。
        // 若忘记掩码，百分号编码会算出错误的十六进制位。
        val encoded = FormFields.encodeComponent("大")   // UTF-8: E5 A4 A7
        assertThat(encoded).isEqualTo("%E5%A4%A7")
        assertThat(URLDecoder.decode(encoded, Charsets.UTF_8)).isEqualTo("大")
    }

    @Test
    fun `null 值被当成空串而不是被丢掉`() {
        // 教务系统大量使用"字段存在但值为空"的写法：lt=""、zc=""、jhlxdm=""、captcha=""。
        // 漏掉这些字段（而不是传空值）会导致请求被拒。
        val body = FormFields().add("lt", null).add("zc", null).encode()
        assertThat(body).isEqualTo("lt=&zc=")
    }

    @Test
    fun `字段顺序与添加顺序一致`() {
        val body = FormFields().add("c", "3").add("a", "1").add("b", "2").encode()
        assertThat(body).isEqualTo("c=3&a=1&b=2")
    }

    @Test
    fun `set 覆盖已有字段但保持它原来的位置`() {
        // 登录表单的处理方式：先把所有隐藏域按文档顺序铺好（其中 password 是空的），
        // 再 set("password", 密文) 覆盖。位置不该因此跑到末尾。
        val f = FormFields().add("password", "").add("_eventId", "submit")
        f.set("password", "CIPHER")
        assertThat(f.encode()).isEqualTo("password=CIPHER&_eventId=submit")
    }

    @Test
    fun `set 一个不存在的字段则追加到末尾`() {
        val f = FormFields().add("a", "1")
        f.set("b", "2")
        assertThat(f.encode()).isEqualTo("a=1&b=2")
    }

    @Test
    fun `addAll 保持 Map 的迭代顺序`() {
        val map = linkedMapOf("xnxqdm" to "202501", "zc" to "", "page" to "1", "rows" to "200")
        assertThat(FormFields().addAll(map).encode())
            .isEqualTo("xnxqdm=202501&zc=&page=1&rows=200")
    }

    @Test
    fun `toRequestBody 的 Content-Type 是 form-urlencoded 且带 UTF-8`() {
        val body = FormFields().add("k", "中文").toRequestBody()
        assertThat(body.contentType().toString()).contains("application/x-www-form-urlencoded")
        assertThat(body.contentType().toString().lowercase()).contains("utf-8")
        assertThat(body.contentLength()).isGreaterThan(0)
    }

    @Test
    fun `重复的同名字段都会被保留`() {
        // 教务系统某些接口会传同名多值参数（例如 sort=zc,xq 被拆开时）。
        // 我们的场景暂时用不到，但"静默丢弃"是更糟的行为，所以显式测试保留语义。
        assertThat(FormFields().add("a", "1").add("a", "2").encode()).isEqualTo("a=1&a=2")
    }

    @Test
    fun `get 能读回字段值`() {
        val f = FormFields().add("username", "31200").add("", "salt")
        assertThat(f["username"]).isEqualTo("31200")
        assertThat(f[""]).isEqualTo("salt")
        assertThat(f["nope"]).isNull()
        assertThat(f.size).isEqualTo(2)
        assertThat(FormFields().isEmpty()).isTrue()
    }
}
