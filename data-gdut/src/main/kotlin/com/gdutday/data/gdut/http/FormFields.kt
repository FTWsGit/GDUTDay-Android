package com.gdutday.data.gdut.http

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `application/x-www-form-urlencoded` 表单构造器。
 *
 * ## 为什么不直接用 OkHttp 的 `FormBody.Builder`
 *
 * 统一认证的登录表单里有一个 **name 为空字符串**的字段 —— 就是 `pwdEncryptSalt`：
 * ```html
 * <input type="hidden" id="pwdEncryptSalt" value="nL1mqFuvlY7dro2Z">
 *                                        ↑ 没有 name 属性
 * ```
 * 浏览器提交时它会变成请求体里一个 `=nL1mqFuvlY7dro2Z` 的空名键值对。
 * 旧 Java 后端为此专门写了 `tempMap.put("", pwdEncryptSalt)`，
 * F# 版则用 `match (name, value) with | null, v -> ("", v.Value)` 把缺失的 name 归一为 `""`。
 *
 * `FormBody.Builder` 理论上也能接受空 name，但这是**未文档化的行为**，
 * 一旦 OkHttp 某个版本加了 `require(name.isNotEmpty())` 就会静默破坏登录。
 * 自己编码 20 行代码，换来对请求体的完全掌控（包括字段顺序），值得。
 *
 * ## 字段顺序
 *
 * 保持与 HTML 中 `<input>` 出现顺序一致（隐藏域在前，用户输入在后，salt 空名字段紧随隐藏域）。
 * 表单顺序对服务端通常无意义，但保持一致能减少一切不必要的差异。
 */
public class FormFields {

    private val entries = mutableListOf<Pair<String, String>>()

    /** 字段数量。 */
    public val size: Int get() = entries.size

    public fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * 追加一个字段。
     *
     * @param name 允许为空字符串（见类注释）
     * @param value null 会被当成空串 —— 教务系统大量使用"字段存在但值为空"的写法
     *   （`lt=""`、`zc=""`、`jhlxdm=""`、`captcha=""`），漏掉这些字段会导致请求被拒。
     */
    public fun add(name: String, value: String?): FormFields = apply {
        entries += name to (value ?: "")
    }

    /** 批量追加，保持 [map] 的迭代顺序（传 `LinkedHashMap` 可精确控制顺序）。 */
    public fun addAll(map: Map<String, String?>): FormFields = apply {
        map.forEach { (k, v) -> add(k, v) }
    }

    /** 覆盖已存在的字段；不存在则追加到末尾。 */
    public fun set(name: String, value: String?): FormFields = apply {
        val idx = entries.indexOfFirst { it.first == name }
        if (idx >= 0) entries[idx] = name to (value ?: "") else add(name, value)
    }

    public operator fun get(name: String): String? = entries.firstOrNull { it.first == name }?.second

    /** 编码成请求体字符串，如 `username=312...&password=abc%2B%2F%3D&=salt`。 */
    public fun encode(): String = buildString {
        entries.forEachIndexed { i, (name, value) ->
            if (i > 0) append('&')
            append(encodeComponent(name)).append('=').append(encodeComponent(value))
        }
    }

    public fun toRequestBody(): RequestBody =
        encode().toRequestBody(FORM_URL_ENCODED)

    override fun toString(): String = encode()

    public companion object {
        /**
         * 教务系统所有 POST 接口都用这个 Content-Type。
         *
         * ⚠ 不是 `application/json`。旧小程序的 `axios-config.js` 里也是
         * `'content-type': 'application/x-www-form-urlencoded'`。
         */
        public val FORM_URL_ENCODED: MediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        /**
         * HTML 规范的 `application/x-www-form-urlencoded` 百分号编码。
         *
         * 保留字符：`A-Z a-z 0-9 - _ . *`；空格编码为 `+`；其余按 UTF-8 逐字节 `%XX`。
         *
         * 这一点对登录**至关重要**：AES 密文是 Base64，必然含 `+` `/` `=`，
         * 不编码就会把 `+` 当成空格传给服务端，导致解密失败、报"密码错误"。
         */
        public fun encodeComponent(s: String): String {
            // 快速路径：全部字符都无需编码时直接返回，避免为每个字段都建 StringBuilder
            if (s.all { it in UNRESERVED }) return s
            val bytes = s.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size + 8)
            for (b in bytes) {
                val c = b.toInt().toChar()
                when {
                    // UTF-8 多字节序列的字节都 ≥ 0x80，不会命中 ASCII 分支，逐个转 %XX 即可
                    c in UNRESERVED -> sb.append(c)
                    c == ' ' -> sb.append('+')
                    else -> {
                        sb.append('%')
                        sb.append(HEX[(b.toInt() shr 4) and 0x0F])
                        sb.append(HEX[b.toInt() and 0x0F])
                    }
                }
            }
            return sb.toString()
        }

        private const val UNRESERVED: String =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.*"

        private const val HEX: String = "0123456789ABCDEF"

        public fun builder(): FormFields = FormFields()
    }
}
