package com.gdutday.data.gdut.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * [AuthServerCrypto] 的单元测试。
 *
 * ## 这些测试在证明什么
 *
 * 加密算法是整个登录链路的地基，**错一个字节就永远登不上，而且报错信息是"密码错误"**，
 * 排查方向会被彻底带偏。所以这里做三层验证：
 *
 * 1. **黄金值比对**：与 OpenSSL 独立算出的密文逐字节相同（证明 AES 参数正确）
 * 2. **随机性**：同一密码两次加密结果不同（证明我们真的在随机，没退化成硬编码）
 * 3. **IV 无关性**：用错误的 IV 解密再剥掉前 64 字节，仍能还原出明文
 *    —— 这条性质正是"旧后端硬编码 IV 却能用"的原因，也证明了我们的随机 IV 不会造成问题
 *
 * 参照实现（fixture `authserver_encrypt.js.real`，抓自线上）：
 * ```js
 * function getAesString(n,f,c){f=f.replace(/(^\s+)|(\s+$)/g,"");f=CryptoJS.enc.Utf8.parse(f);
 *   c=CryptoJS.enc.Utf8.parse(c);
 *   return CryptoJS.AES.encrypt(n,f,{iv:c,mode:CryptoJS.mode.CBC,padding:CryptoJS.pad.Pkcs7}).toString()}
 * function encryptAES(n,f){return f?getAesString(randomString(64)+n,f,randomString(16)):n}
 * var $aes_chars="ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
 * ```
 */
class AuthServerCryptoTest {

    /** 取自真实登录页 fixture `authserver_login_page_with_service.real.html` 的 `#pwdEncryptSalt`。 */
    private val realSalt = "xaOfScaw6epvgypH"

    /**
     * 黄金值。用 OpenSSL 独立计算：
     * ```bash
     * printf 'J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1mypassword123' > pt.txt
     * openssl enc -aes-128-cbc \
     *   -K 78614f66536361773665707667797048 \   # "xaOfScaw6epvgypH" 的 hex
     *   -iv 4a69736e6977716a77716a77716a7777 \  # "Jisniwqjwqjwqjww" 的 hex
     *   -in pt.txt -a -A
     * ```
     * 输出即下面这个字符串。
     *
     * 如果这条测试挂了，说明我们的 AES 实现与标准实现不一致 —— **不要去改这个期望值**，
     * 去查 [AuthServerCrypto]。
     */
    private val goldenCipherBase64 =
        "PGcL+pVo35QDmj6ESVEXwHXaNFDWEVpSocZNgniIWiqLs/Ttv9odcD4bgyfjP6hUibontEiHULKNtVc3aInybAfKTi83GSUm4bmErcOOldM="

    @Test
    fun `与 OpenSSL 独立算出的黄金值逐字节一致`() {
        val actual = AuthServerCrypto.encryptDeterministic(
            plaintext = "mypassword123",
            salt = realSalt,
            prefix = "J69IVxcXqvqNhvk1".repeat(4),
            iv = "Jisniwqjwqjwqjww",
        )
        assertThat(actual).isEqualTo(goldenCipherBase64)
    }

    @Test
    fun `默认前缀与 IV 就是旧后端硬编码的那两个，因此能复现旧后端的密文`() {
        // 这条测试的意义：如果将来有人想拿旧 Java 后端/F# 库产生的密文来对比，
        // 直接用 encryptDeterministic 的默认参数就能得到相同结果。
        assertThat(AuthServerCrypto.LEGACY_PREFIX).hasLength(64)
        assertThat(AuthServerCrypto.LEGACY_PREFIX).isEqualTo("J69IVxcXqvqNhvk1".repeat(4))
        assertThat(AuthServerCrypto.LEGACY_IV).hasLength(16)
        assertThat(AuthServerCrypto.encryptDeterministic("mypassword123", realSalt))
            .isEqualTo(goldenCipherBase64)
    }

    @Test
    fun `随机版本每次产生不同密文`() {
        val a = AuthServerCrypto.encryptPassword("mypassword123", realSalt)
        val b = AuthServerCrypto.encryptPassword("mypassword123", realSalt)
        assertThat(a).isNotEqualTo(b)
        // 但两者都能被服务端正确还原
        assertThat(stripPrefixWithWrongIv(a, realSalt)).isEqualTo("mypassword123")
        assertThat(stripPrefixWithWrongIv(b, realSalt)).isEqualTo("mypassword123")
    }

    @Test
    fun `注入固定种子的 Random 时结果可复现`() {
        // 同一种子的 kotlin.random.Random 产生同一串随机字符，因此密文可复现。
        // 这条测试保证"随机"这件事本身是可控的 —— 出问题时能在本地稳定重放。
        val a = AuthServerCrypto.encryptPassword("mypassword123", realSalt, Random(20260910))
        val b = AuthServerCrypto.encryptPassword("mypassword123", realSalt, Random(20260910))
        val c = AuthServerCrypto.encryptPassword("mypassword123", realSalt, Random(1))

        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        // 三种密文服务端都能还原成同一个密码
        assertThat(stripPrefixWithWrongIv(a, realSalt)).isEqualTo("mypassword123")
        assertThat(stripPrefixWithWrongIv(c, realSalt)).isEqualTo("mypassword123")
    }

    @Test
    fun `旧后端硬编码的前缀与 IV 根本不可能是 randomString 产生的`() {
        // 一个有意思的旁证：
        //   前缀 "J69IVxcXqvqNhvk1" 含 '9' '1' 'I' 'V'
        //   IV     "Jisniwqjwqjwqjww" 含 'q'
        // 这些字符都**不在** encrypt.js 的 48 字符表里（表里刻意排除了 I L O U V / g l o q u v / 0 1 9）。
        // 也就是说那两个常量不可能出自 `randomString()`，
        // 进一步说明它们是凭空写死的、而不是某次抓包的产物。
        // 这条测试把这个事实钉住，免得将来有人以为"照抄旧常量就等于照抄浏览器行为"。
        val illegalInPrefix = AuthServerCrypto.LEGACY_PREFIX.toList().filter { it !in AuthServerCrypto.AES_CHARS }.distinct()
        val illegalInIv = AuthServerCrypto.LEGACY_IV.toList().filter { it !in AuthServerCrypto.AES_CHARS }.distinct()

        assertThat(illegalInPrefix).isNotEmpty()
        assertThat(illegalInIv).isNotEmpty()
        assertThat(illegalInPrefix).containsAtLeast('1', '9', 'I', 'V')
        assertThat(illegalInIv).contains('q')
    }

    @Test
    fun `IV 只影响第一个明文块 - 用错误的 IV 解密再剥前 64 字节仍能还原密码`() {
        // 这条是整个"随机 IV 安全"论证的核心，也用 OpenSSL 实测验证过：
        //   echo "<IV1 产生的密文>" | openssl enc -d -aes-128-cbc -K <key> -iv <另一个 IV> -a -A | tail -c +65
        //   → mypassword123
        val cipher = AuthServerCrypto.encryptDeterministic(
            plaintext = "mypassword123",
            salt = realSalt,
            prefix = "J69IVxcXqvqNhvk1".repeat(4),
            iv = "Jisniwqjwqjwqjww",
        )
        // 用一个完全不相干的 IV 去解
        val recovered = AuthServerCrypto.decryptStripPrefix(cipher, realSalt, "ZZZZZZZZZZZZZZZZ")
        assertThat(recovered).isEqualTo("mypassword123")
    }

    @Test
    fun `密文长度符合预期 - 前缀64 + 明文，PKCS7 补齐到 16 的倍数`() {
        val cipher = AuthServerCrypto.encryptPassword("mypassword123", realSalt)
        val bytes = java.util.Base64.getDecoder().decode(cipher)
        // 64 + 13 = 77 → 补到 80
        assertThat(bytes).hasLength(80)
    }

    @Test
    fun `salt 为空时返回明文 - 忠实复刻 encryptAES 的三元判断`() {
        // JS: function encryptAES(n,f){return f?getAesString(...):n}
        // 这对应服务端未启用密码加密的部署形态，是有意的分支，不是 bug。
        assertThat(AuthServerCrypto.encryptPassword("plaintext-pw", null)).isEqualTo("plaintext-pw")
        assertThat(AuthServerCrypto.encryptPassword("plaintext-pw", "")).isEqualTo("plaintext-pw")
    }

    @Test
    fun `salt 前后空白会被 trim - 复刻 getAesString 里的 replace 正则`() {
        // JS: f = f.replace(/(^\s+)|(\s+$)/g,"")
        val withSpaces = AuthServerCrypto.encryptDeterministic(
            "mypassword123", "  $realSalt  ",
            prefix = "J69IVxcXqvqNhvk1".repeat(4), iv = "Jisniwqjwqjwqjww",
        )
        assertThat(withSpaces).isEqualTo(goldenCipherBase64)
    }

    @Test
    fun `salt 长度非法时抛 Parse 异常而不是把明文发出去`() {
        // 与 JS 版的 `catch(c){} return n`（失败就发明文）刻意不同：宁可失败也不泄露明文密码
        val e = org.junit.Assert.assertThrows(com.gdutday.core.model.GdutException.Parse::class.java) {
            AuthServerCrypto.encryptPassword("pw", "too-short")
        }
        assertThat(e).hasMessageThat().contains("pwdEncryptSalt")
    }

    @Test
    fun `isValidSalt 只接受 16-24-32 字节`() {
        assertThat(AuthServerCrypto.isValidSalt("xaOfScaw6epvgypH")).isTrue()   // 16
        assertThat(AuthServerCrypto.isValidSalt("123456789012345678901234")).isTrue()  // 24
        assertThat(AuthServerCrypto.isValidSalt("12345678901234567890123456789012")).isTrue() // 32
        assertThat(AuthServerCrypto.isValidSalt("short")).isFalse()
        assertThat(AuthServerCrypto.isValidSalt(null)).isFalse()
        assertThat(AuthServerCrypto.isValidSalt("")).isFalse()
    }

    @Test
    fun `随机字符表与线上 encrypt-js 完全一致`() {
        // 这条测试盯着 fixture：如果哪天有人"顺手改一下"字符表，或者学校改了 encrypt.js
        // 而我们没跟上，测试会立刻发现。
        //
        // 正则里两个坑，都在这里绕开了：
        // 1. JS 的变量名以 `$` 开头。裸写 `$` 在正则里是"行尾锚点"，在 Kotlin 普通字符串里
        //    又会触发插值 —— 用字符类 `[$]` 表示字面量美元符号，一次解决两边。
        // 2. 用原始字符串 """...""" 才能让 `\s` 原样传给正则（普通字符串里 `\s` 是非法转义，编译不过）。
        //    捕获组用 `[A-Za-z0-9]+` 而不是 `[^"]+`，这样模式串就不会以引号结尾，
        //    避免 `""""` 这种四引号连写的歧义。
        val js = readFixture("authserver_encrypt.js.real")
        val pattern = Regex("""[$]aes_chars\s*=\s*"([A-Za-z0-9]+)""")
        val m = pattern.find(js)
        check(m != null) { "fixture 里找不到 aes_chars 字符表定义，可能抓错了文件" }
        assertThat(AuthServerCrypto.AES_CHARS).isEqualTo(m.groupValues[1])
        assertThat(AuthServerCrypto.AES_CHARS).hasLength(48)
    }

    @Test
    fun `randomString 只产出字符表内的字符且长度正确`() {
        val s = AuthServerCrypto.randomString(256)
        assertThat(s).hasLength(256)
        assertThat(s.all { it in AuthServerCrypto.AES_CHARS }).isTrue()
        // 前缀长度必须是 16 的倍数，否则服务端按 64 字节剥离时会切坏真实密码
        assertThat(AuthServerCrypto.RANDOM_PREFIX_LENGTH % 16).isEqualTo(0)
    }

    @Test
    fun `中文与特殊字符密码能正常加密还原`() {
        for (pw in listOf("密码是中文", "p@ss w0rd!#+=", "a".repeat(64), "\uD83D\uDE00emoji")) {
            val cipher = AuthServerCrypto.encryptPassword(pw, realSalt)
            assertThat(stripPrefixWithWrongIv(cipher, realSalt)).isEqualTo(pw)
        }
    }

    // ------------------------------------------------------------------ 辅助

    /** 模拟服务端：用一个"错误的" IV 解密，然后剥掉前 64 字节。 */
    private fun stripPrefixWithWrongIv(cipher: String, salt: String): String =
        AuthServerCrypto.decryptStripPrefix(cipher, salt, "0000000000000000")

    private fun readFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.readText()
            ?: error("找不到 fixture: $name")
}
