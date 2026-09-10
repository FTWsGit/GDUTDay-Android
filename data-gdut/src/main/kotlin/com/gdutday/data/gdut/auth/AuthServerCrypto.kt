package com.gdutday.data.gdut.auth

import com.gdutday.core.model.GdutException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 统一认证登录页的密码加密。**逐字节复刻**学校前端的行为。
 *
 * ## 地面真相（2026-09-10 从线上抓取）
 *
 * `https://authserver.gdut.edu.cn/authserver/gdutThemes/static/common/encrypt.js` 末尾：
 * ```js
 * function getAesString(n, f, c) {                        // n=明文 f=key c=iv
 *     f = f.replace(/(^\s+)|(\s+$)/g, "");                // ← key 会 trim
 *     f = CryptoJS.enc.Utf8.parse(f);
 *     c = CryptoJS.enc.Utf8.parse(c);                     // ← iv 不 trim
 *     return CryptoJS.AES.encrypt(n, f, {
 *         iv: c, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7
 *     }).toString();                                      // ← Base64(裸密文)
 * }
 * function encryptAES(n, f) {
 *     return f ? getAesString(randomString(64) + n, f, randomString(16)) : n;
 * }                                                       //  ↑ 64 字节随机前缀  ↑ 16 字节随机 IV
 * function encryptPassword(n, f) { try { return encryptAES(n, f) } catch (c) {} return n }
 *
 * var $aes_chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";   // 48 个字符
 * function randomString(n) { var f=""; for (i=0;i<n;i++) f += $aes_chars.charAt(Math.floor(Math.random()*48)); return f }
 * ```
 *
 * ## ⚠ 旧实现的一个认知错误（务必不要照抄）
 *
 * 旧 Java 后端 `LiUtils.cbcEncrypt` 和 F# 的 `GDUT.Auth.EhallCrypto.encrypt` 都把
 * **64 字节前缀和 16 字节 IV 硬编码成了常量**：
 * ```java
 * String iv = "Jisniwqjwqjwqjww";   // 16 字节，硬编码
 * String s = "J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1" + plaintext;  // 16×4 = 64 字节前缀
 * ```
 * 这两个字符串是原作者**某一次抓包看到的随机值**，被误当成了协议常量。
 *
 * **为什么错了还能用**：CBC 解密时只有第 1 个明文块依赖 IV，从第 2 块起只依赖密文块本身。
 * 服务端解密后丢掉前 64 字节（正好 4 个块）的垃圾前缀，剩下的真实密码与 IV 取值无关。
 * 所以硬编码任何 IV 都能碰巧成功 —— 但这在密文上留下了**固定指纹**
 * （同一个密码每次登录产生的密文完全一样），对风控不友好，也是明显的实现缺陷。
 *
 * 本项目照浏览器原样每次生成随机前缀与随机 IV。
 *
 * ## 与浏览器行为的唯一分歧
 *
 * JS 版 `encryptPassword` 里有 `try { ... } catch (c) {} return n`，
 * 即加密失败时**把明文密码发出去**。这显然是无意的，我们不复刻：
 * 加密失败一律抛 [GdutException.Parse]，绝不发送明文密码。
 *
 * 但 `encryptAES` 的 `f ? ... : n`（salt 为空时返回明文）是**有意的设计**
 * —— 对应服务端未启用密码加密的部署形态，这一条我们保留。
 */
public object AuthServerCrypto {

    /**
     * 随机字符表，48 个字符。
     *
     * 刻意排除了易混淆字符：大写缺 `I L O U V`，小写缺 `g l o q u v`，数字缺 `0 1 9`。
     * 这个字符串**必须与线上 encrypt.js 完全一致**，否则生成的前缀/IV 虽然长度对，
     * 但字符分布不同 —— 目前不影响服务端校验，却会成为一个不必要的指纹差异。
     */
    public const val AES_CHARS: String = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    /** 明文前的垃圾前缀长度（字节）。服务端解密后会丢弃这么多个字符。 */
    public const val RANDOM_PREFIX_LENGTH: Int = 64

    /** IV 长度（字节）。AES 块大小固定 16。 */
    public const val IV_LENGTH: Int = 16

    /** CryptoJS 的 CBC 模式 + Pkcs7 填充，等价于 JCE 的 `AES/CBC/PKCS5Padding`。 */
    private const val TRANSFORMATION: String = "AES/CBC/PKCS5Padding"

    init {
        // 字符表长度写死校验：一旦有人误改 AES_CHARS，测试阶段就炸，而不是等登录失败才发现
        check(AES_CHARS.length == 48) { "AES_CHARS 必须是 48 个字符，当前 ${AES_CHARS.length}" }
    }

    /**
     * 生成 [length] 个随机字符，字符取自 [AES_CHARS]。复刻 JS 的 `randomString(n)`。
     *
     * @param random 可注入以便测试复现。默认 [Random.Default]（Kotlin 文档保证其使用安全随机源）。
     */
    public fun randomString(length: Int, random: Random = Random.Default): String {
        require(length >= 0) { "length 不能为负: $length" }
        return buildString(length) {
            repeat(length) { append(AES_CHARS[random.nextInt(AES_CHARS.length)]) }
        }
    }

    /**
     * 加密登录密码。
     *
     * @param plaintext 用户输入的明文密码
     * @param salt 登录页 `#pwdEncryptSalt` 隐藏域的 value（16 个字符）
     * @param random 随机源，测试可注入固定种子
     * @return Base64 密文，直接作为表单的 `password` 字段值
     * @throws GdutException.Parse salt 非空但不是合法的 AES key 长度（16/24/32 字节）
     */
    public fun encryptPassword(
        plaintext: String,
        salt: String?,
        random: Random = Random.Default,
    ): String {
        // 忠实复刻 encryptAES 的 `f ? getAesString(...) : n`：salt 为空 → 不加密
        if (salt.isNullOrEmpty()) return plaintext

        val key = salt.trim()                       // getAesString 对 key 做 trim
        val prefix = randomString(RANDOM_PREFIX_LENGTH, random)
        val iv = randomString(IV_LENGTH, random)    // iv 不 trim（随机字符表里也没有空白）
        return aesCbcEncryptBase64(data = prefix + plaintext, key = key, iv = iv)
    }

    /**
     * 判断一个 salt 是否是合法的 AES key。
     *
     * 登录页解析失败时可以用它提前给出明确错误，而不是等到 `Cipher.init` 抛
     * `InvalidKeyException` 再猜原因。
     */
    public fun isValidSalt(salt: String?): Boolean {
        val bytes = salt?.trim()?.toByteArray(Charsets.UTF_8) ?: return false
        return bytes.size == 16 || bytes.size == 24 || bytes.size == 32
    }

    /**
     * 用**指定的**前缀与 IV 加密 —— 确定性版本，不取随机数。
     *
     * 存在的理由有两个：
     *
     * 1. **黄金值测试**。单元测试里需要一个可与独立实现逐字节比对的期望值。
     *    `AuthServerCryptoTest` 用 OpenSSL 算出
     *    `AES-128-CBC(key="xaOfScaw6epvgypH", iv="Jisniwqjwqjwqjww",
     *    data="J69IVxcXqvqNhvk1"×4 + "mypassword123")` 的 Base64，
     *    再断言本方法输出一模一样 —— 这就证明了 Kotlin 实现与浏览器里的 CryptoJS 等价。
     *    随机版本没法这么测。
     *
     * 2. **复现旧后端的行为**。旧 Java 后端和 F# 库都硬编码了
     *    前缀 `"J69IVxcXqvqNhvk1"×4` 和 IV `"Jisniwqjwqjwqjww"`。
     *    如果哪天需要对比"我们的密文"和"旧后端的密文"，
     *    用这个方法传同样的参数就能得到完全相同的输出。
     *
     * **线上流程不要用这个方法** —— 用 [encryptPassword]，让前缀和 IV 每次随机。
     *
     * @param prefix 明文前的垃圾前缀，**必须是 16 的整数倍**（否则真实密码会落在块中间，
     *   服务端按 64 字节剥离时会切坏）。默认 64 字节，与浏览器一致。
     * @param iv 16 字节 IV
     */
    public fun encryptDeterministic(
        plaintext: String,
        salt: String,
        prefix: String = LEGACY_PREFIX,
        iv: String = LEGACY_IV,
    ): String {
        require(prefix.length % 16 == 0) {
            "前缀长度必须是 16 的整数倍，收到 ${prefix.length}"
        }
        require(iv.toByteArray(Charsets.UTF_8).size == IV_LENGTH) {
            "IV 必须是 $IV_LENGTH 字节，收到 ${iv.toByteArray(Charsets.UTF_8).size}"
        }
        return aesCbcEncryptBase64(data = prefix + plaintext, key = salt.trim(), iv = iv)
    }

    /** 旧 Java 后端 / F# 库硬编码的 64 字节前缀（`"J69IVxcXqvqNhvk1"` 重复 4 次）。 */
    public const val LEGACY_PREFIX: String =
        "J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1"

    /** 旧 Java 后端 / F# 库硬编码的 IV。 */
    public const val LEGACY_IV: String = "Jisniwqjwqjwqjww"

    /**
     * AES/CBC/PKCS7 加密并 Base64。
     *
     * 对应 CryptoJS：`AES.encrypt(utf8(data), Utf8.parse(key), {iv: Utf8.parse(iv), CBC, Pkcs7}).toString()`
     *
     * CryptoJS 在 key 以 WordArray（而非口令字符串）传入时**不会**生成 OpenSSL 的
     * `Salted__` 头，`.toString()` 就是裸密文的 Base64。JCE 的行为天然一致。
     */
    private fun aesCbcEncryptBase64(data: String, key: String, iv: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) {
            throw GdutException.Parse(
                what = "登录页的 pwdEncryptSalt",
                snippet = "salt 长度 ${keyBytes.size} 字节，不是合法的 AES key（需 16/24/32）。" +
                    "登录页结构可能已变更。",
            )
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
            )
            Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            // 绝不在失败时回退成明文（与 JS 版的 catch{} return n 刻意不同）
            throw GdutException.Parse(
                what = "登录密码",
                snippet = "${e.javaClass.simpleName}: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * 解密 —— **仅用于自测与诊断**，线上流程不需要。
     *
     * 模拟服务端的校验方式：解密后丢掉前 [RANDOM_PREFIX_LENGTH] 个字符，
     * 剩下的应当等于原始明文。这条性质是"IV 可以任意取"的根本原因，
     * 单元测试 [AuthServerCryptoTest] 用它来证明随机 IV 不影响可解性。
     *
     * ⚠ 需要知道 IV 才能解出**第 1 个块**；从第 2 块起与 IV 无关。
     * 由于我们丢弃前 4 个块，[decryptStripPrefix] 允许传任意 IV 而结果不变 —— 这正是服务端能工作的原理。
     *
     * @param iv 加密时用的 IV；传其它值也能得到相同的去前缀结果
     */
    public fun decryptStripPrefix(cipherBase64: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.trim().toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val plain = String(cipher.doFinal(Base64.getDecoder().decode(cipherBase64)), Charsets.UTF_8)
        return if (plain.length > RANDOM_PREFIX_LENGTH) plain.substring(RANDOM_PREFIX_LENGTH) else plain
    }
}
