package com.gdutday.data.gdut.auth

import com.gdutday.core.model.GdutException
import com.gdutday.data.gdut.GdutEndpoints
import com.gdutday.data.gdut.http.LenientJson
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 表单里的一个字段。[name] 允许为空字符串 —— 见 [AuthLoginPageParser] 的说明。 */
public data class FormField(
    public val name: String,
    public val value: String,
)

/**
 * 解析后的统一认证登录表单。
 *
 * @property action 表单 `action` 属性的原始值（相对路径 `/authserver/login`）。
 *   提交前必须用页面 URL 解析成绝对地址，见 [resolveAction]。
 * @property hiddenFields `#pwdFromId` 内所有 `input[type=hidden]`，**保持文档顺序**。
 *   其中包含一个 `name` 为空字符串的条目，它的 value 就是 salt —— 这是浏览器的真实行为，
 *   提交时必须原样带上（请求体里会出现 `=xaOfScaw6epvgypH` 这样的空名键值对）。
 * @property salt `#pwdEncryptSalt` 的 value。16 个字符。
 * @property service 页面内联变量 `var service = ...` 的值。
 *   **它决定了 POST 的目标 URL 要不要带 `?service=`**，见 [submitUrl]。
 * @property captchaSwitch 页面内联变量。`"1"`=图形验证码，`"2"`=滑块验证码。
 * @property badCredentialsCount 页面内联变量 `_badCredentialsCount`。
 *   `login.js` 的 `credentialsCount()` 判断它是否 `== 0`，为 0 时页面一加载就强制显示验证码 ——
 *   也就是说这是服务端下发的"本会话还剩几次试错机会"。
 */
public data class AuthLoginForm(
    public val action: String,
    public val hiddenFields: List<FormField>,
    public val salt: String?,
    public val execution: String?,
    public val cllt: String,
    public val dllt: String,
    public val lt: String,
    public val service: String?,
    public val captchaSwitch: String?,
    public val badCredentialsCount: Int?,
    public val pageUrl: String,
) {
    /** `action` 解析成绝对 URL。 */
    public fun resolveAction(): HttpUrl {
        val base = pageUrl.toHttpUrl()
        return base.resolve(action)
            ?: throw GdutException.Parse(
                what = "登录表单的 action",
                snippet = "无法把 action='$action' 解析成绝对地址（页面 URL=$pageUrl）",
            )
    }

    /**
     * 提交时真正要 POST 的地址。
     *
     * `login.js` 在 `DOMContentLoaded` 时会改写表单 action：
     * ```js
     * if (service && service != "") {
     *     utils.setUrlParam("pwdFromId", "?service", encodeURIComponent(service));
     * }
     * // utils.setUrlParam: eltObj.action = eltObj.action + paramName + "=" + paramValue
     * ```
     * 所以浏览器实际 POST 到 `/authserver/login?service=<urlencoded>`。
     *
     * **漏掉这个 query 会导致 CAS 不知道要签发哪个系统的 ticket**，
     * 登录看似成功却拿不到 jxfw 的会话 —— 这是最容易踩且最难排查的坑。
     */
    public fun submitUrl(): HttpUrl {
        val base = resolveAction()
        val svc = service?.takeIf { it.isNotBlank() } ?: return base
        return base.newBuilder()
            .addQueryParameter("service", svc)
            .build()
    }

    /** salt 是否是合法的 AES key。 */
    public fun hasUsableSalt(): Boolean = AuthServerCrypto.isValidSalt(salt)

    /** 表单里是否含有 `password` 字段（密文要写进去的那个）。 */
    public fun hasPasswordField(): Boolean = hiddenFields.any { it.name == "password" }

    /** 那个 name 为空的字段（就是 salt）是否存在。 */
    public fun hasEmptyNameField(): Boolean = hiddenFields.any { it.name.isEmpty() }

    /** 组装成便于打日志的形式。**不含 salt 与 execution 全文**，避免敏感信息进日志。 */
    override fun toString(): String =
        "AuthLoginForm(action=$action, service=$service, cllt=$cllt, dllt=$dllt, " +
            "lt='$lt', captchaSwitch=$captchaSwitch, badCredentialsCount=$badCredentialsCount, " +
            "hidden=${hiddenFields.map { if (it.name.isEmpty()) "<empty-name>" else it.name }}, " +
            "salt=${salt?.let { "${it.take(2)}***(${it.length})" }})"
}

/**
 * 统一认证登录页解析器。
 *
 * ## 实测的页面结构（2026-09-10 抓取自线上，fixture 见 `src/test/resources/fixtures/`）
 *
 * ```html
 * <form class="loginFromClass" method="post" id="pwdFromId" action="/authserver/login">
 *   <input type="text"     id="username"       name="username"     value="">
 *   <input type="password" id="password"       name="passwordText" value="">   ← 提交前被 JS disabled
 *   <input type="hidden"   id="saltPassword"   name="password"     value="">   ← 密文写这里
 *   <input type="text"     id="captcha"        name="captcha"      value="">
 *   <input type="checkbox" id="rememberMe"     name="rememberMe"   value="true">
 *   <input type="hidden"   id="_eventId"       name="_eventId"     value="submit">
 *   <input type="hidden"   id="cllt"           name="cllt"         value="userNameLogin">
 *   <input type="hidden"   id="dllt"           name="dllt"         value="generalLogin">
 *   <input type="hidden"   id="lt"             name="lt"           value="">
 *   <input type="hidden"   id="pwdEncryptSalt"                     value="xaOfScaw6epvgypH">
 *                                                    ↑ 没有 name 属性！
 *   <input type="hidden"   id="execution"      name="execution"    value="dd938fd8-...">
 * </form>
 * ```
 *
 * ## 三个必须做对的细节
 *
 * **1. 必须限定在 `#pwdFromId` 内解析。**
 * 页面上还有第二个表单（动态/手机号登录），它的隐藏域是 `cllt=dynamicLogin`，
 * 而且用的 salt 元素 id 是 `encryptSalt` 而非 `pwdEncryptSalt`。
 * 全文档扫 `input[type=hidden]` 会把两个表单的字段混在一起，提交必然失败。
 *
 * **2. `pwdEncryptSalt` 没有 `name` 属性。**
 * jsoup 的 `attr("name")` 对缺失属性返回**空字符串**（不是 null），
 * 于是它自然成为请求体里一个 `=<salt>` 的空名字段 —— 这正是浏览器的行为。
 * 旧 Java 后端为此专门写了 `tempMap.put("", pwdEncryptSalt)`，
 * F# 版写了 `| null, v -> ("", v.Value)`，两者殊途同归。
 * 本实现不需要特判：直接取 `attr("name")` 即可。
 *
 * **3. `passwordText` 不会出现在请求体里。**
 * `checkForm()` 在提交前执行 `$(LOGIN_PASSWORD_ID).attr("disabled", "disabled")`，
 * 被 disabled 的控件不参与表单序列化。而它是 `type=password` 不是 `type=hidden`，
 * 所以"只收集 hidden"这条规则天然把它排除了。**不要**好心把它加进去。
 *
 * ## `execution` 与会话绑定
 *
 * `execution` 的值形如 `dd938fd8-d88d-405a-bf86-2fe07ca76605_ZXlKaGJHY2lPaUpJVXpVeE1...`
 * （UUID + Base64 的 JWT 样式串），它是 CAS 的 flow execution key，**与 JSESSIONID 绑定且一次性**。
 * 所以登录流程必须是"取页面 → 立刻提交"，中间不能换 cookie jar，也不能复用旧的 execution。
 * 这也是为什么 [AuthServerClient.login] 每次都新建一个
 * [com.gdutday.data.gdut.http.SessionCookieJar]。
 */
public object AuthLoginPageParser {

    /** 登录表单的 id。 */
    public const val FORM_ID: String = "pwdFromId"

    /** salt 隐藏域的 id。 */
    public const val SALT_ID: String = "pwdEncryptSalt"

    /**
     * 解析登录页。
     *
     * @param html 登录页 HTML
     * @param pageUrl 取到该页面的 URL，用于把相对 `action` 解析成绝对地址
     * @throws GdutException.Parse 页面里没有 `#pwdFromId`（几乎一定意味着学校改版，
     *   或者被下发到了移动版/维护页）。异常里带 HTML 片段便于诊断。
     */
    public fun parse(html: String, pageUrl: String): AuthLoginForm {
        val doc: Document = Jsoup.parse(html, pageUrl)

        val form: Element = doc.selectFirst("#$FORM_ID")
            ?: throw formNotFoundError(doc, html, pageUrl)

        // 只收集 hidden 域，保持文档顺序。
        // attr("name") 对缺失属性返回 ""，正好复刻浏览器对无名 input 的提交行为。
        val hidden = form.select("input[type=hidden]").map { input ->
            FormField(name = input.attr("name"), value = input.attr("value"))
        }

        val salt = form.selectFirst("#$SALT_ID")?.attr("value")?.takeIf { it.isNotBlank() }
            // 兜底：万一 id 变了但 name 还在
            ?: hidden.firstOrNull { it.name == SALT_ID }?.value

        return AuthLoginForm(
            action = form.attr("action").ifBlank { GdutEndpoints.AUTHSERVER_LOGIN },
            hiddenFields = hidden,
            salt = salt,
            execution = hidden.firstOrNull { it.name == "execution" }?.value,
            cllt = hidden.firstOrNull { it.name == "cllt" }?.value ?: "userNameLogin",
            dllt = hidden.firstOrNull { it.name == "dllt" }?.value ?: "generalLogin",
            lt = hidden.firstOrNull { it.name == "lt" }?.value ?: "",
            service = extractInlineVar(html, "service"),
            captchaSwitch = extractInlineVar(html, "captchaSwitch"),
            badCredentialsCount = extractInlineVar(html, "_badCredentialsCount")?.toIntOrNull(),
            pageUrl = pageUrl,
        )
    }

    /**
     * 判断一份 HTML 是不是"登录失败后重新渲染的登录页"，并抠出错误文案。
     *
     * CAS 在密码错误时不会返回 4xx，而是 **200 + 重新渲染的登录页**，
     * 错误文案写在几个固定容器里。实测页面结构中有：
     * ```html
     * <div id="formErrorTip" class="form-errorTip lang_text_ellipsis">
     *     <span id="showErrorTip" class="form-error"></span>
     *     <span id="showWarnTip"  class="form-warn"></span>
     * </div>
     * ```
     * 初始状态这些 span 是空的，服务端渲染错误时会填上文字。
     *
     * @return 错误文案；页面不是登录页、或找不到任何错误文字时返回 null。
     */
    public fun extractLoginError(html: String): String? {
        if (!looksLikeLoginPage(html)) return null
        val doc = Jsoup.parse(html)
        val selectors = listOf(
            "#showErrorTip",
            "#formErrorTip .form-error",
            ".form-error",
            "#showWarnTip",
            "#error",
            "#msg.errors",
            ".errors",
        )
        for (sel in selectors) {
            val text = doc.select(sel).map { it.text().trim() }
                .firstOrNull { it.isNotEmpty() && it.length <= 200 }
            if (text != null) return text
        }
        return null
    }

    /** 这份 HTML 是不是统一认证登录页（含 `#pwdFromId` 或 `#pwdEncryptSalt`）。 */
    public fun looksLikeLoginPage(html: String): Boolean {
        if (html.isBlank()) return false
        // 先用廉价的字符串检查，避免为每个响应都跑一次完整 HTML 解析
        if (!html.contains(FORM_ID) && !html.contains(SALT_ID)) return false
        val doc = Jsoup.parse(html)
        return doc.selectFirst("#$FORM_ID") != null || doc.selectFirst("#$SALT_ID") != null
    }

    /**
     * 从内联 `<script>` 里抠出 `var xxx = <字面量>;` 的值。
     *
     * ## ⚠ 实测发现：右值有三种形态，其中一种很容易踩坑
     *
     * 抓取线上真实页面（fixture `authserver_login_page*.real.html`）后确认：
     *
     * | 变量 | 不带 service 时 | 带 service 时 |
     * |---|---|---|
     * | `service` | `var service = null;` | `var service = ["https:\/\/jxfw.gdut.edu.cn\/new\/ssoLogin"];` |
     * | `captchaSwitch` | `var captchaSwitch = "2";` | 同 |
     * | `_badCredentialsCount` | `var _badCredentialsCount="5";`（等号两边**无空格**） | 同 |
     * | `needCaptcha` | `var needCaptcha = "";` | 同 |
     *
     * **`service` 的右值是一个 JSON 数组，不是普通字符串字面量**，而且里面的斜杠被转义成了 `\/`。
     *
     * 一个只匹配 `var\s+service\s*=\s*"([^"]*)"` 的正则（很自然会这么写）在这种情况下
     * **匹配不到任何东西**，返回 null，于是 [AuthLoginForm.submitUrl] 就不会带 `?service=`，
     * CAS 不知道该给哪个系统签发票据 —— 登录流程看着走完了，却拿不到 jxfw 会话。
     * 这个 bug 极难排查，所以三种形态都要处理，并且有对应的单元测试盯着。
     *
     * @return 解码后的字符串；值为 `null` / `undefined` / 找不到时返回 null。
     */
    public fun extractInlineVar(html: String, name: String): String? {
        val decl = Regex("""(?:var|let|const)\s+${Regex.escape(name)}\s*=\s*""").find(html) ?: return null
        val rhs = readJsRhs(html, decl.range.last + 1) ?: return null
        return decodeJsLiteral(rhs)
    }

    /**
     * 从 [from] 起读取一个 JS 右值，直到遇到**不在字符串/括号内**的分号或换行。
     *
     * 需要括号深度跟踪：`["https:\/\/..."]` 是个数组字面量，
     * 字符串内部也可能出现分号，简单的 `indexOf(';')` 会截错。
     */
    private fun readJsRhs(text: String, from: Int): String? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        val start = i
        var depth = 0
        var quote: Char? = null
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            val q = quote
            if (q != null) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == q -> quote = null
                }
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '[', '{', '(' -> depth++
                    ']', '}', ')' -> depth--
                    ';' -> if (depth <= 0) return text.substring(start, i).trim()
                    // JS 的自动分号插入：顶层换行也算语句结束
                    '\n' -> if (depth <= 0) {
                        val piece = text.substring(start, i).trim()
                        if (piece.isNotEmpty()) return piece
                    }
                    else -> Unit
                }
            }
            i++
        }
        return text.substring(start).trim().ifEmpty { null }
    }

    /**
     * 解码一个 JS 字面量成字符串。
     *
     * - `null` / `undefined` / 空 → null
     * - `"..."` / `'...'` → 去引号 + 反转义
     * - `[...]` → 当 JSON 数组解析，取第一个元素（`service` 就是这种）
     * - `{...}` → 原样返回（本项目用不到，但别把 JSON 对象误当成字符串）
     * - 其它（`5`、`true`）→ 原样返回
     */
    private fun decodeJsLiteral(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v == "null" || v == "undefined") return null

        if (v.startsWith("[")) {
            val arr = LenientJson.parseArrayOrNull(v)
            if (arr != null) {
                val first = arr.firstOrNull()
                return (first as? JsonPrimitive)?.content
            }
            // JSON 解析失败时的兜底：手工剥壳
            return v.trim('[', ']').trim().trim('"', '\'').replace("\\/", "/").ifEmpty { null }
        }
        if (v.startsWith("{")) return v
        if (v.length >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return unescapeJs(v.substring(1, v.length - 1))
        }
        return v
    }

    /** 反转义 JS 字符串里的 `\/` `\n` `\uXXXX` 等。 */
    private fun unescapeJs(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'u' -> {
                    val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else null
                    val code = hex?.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 4
                    } else {
                        sb.append(n)
                    }
                }
                else -> sb.append(n) // \/ → /, \" → ", \\ → \
            }
            i += 2
        }
        return sb.toString()
    }

    private fun formNotFoundError(doc: Document, html: String, pageUrl: String): GdutException.Parse {
        // 尽量给出有用的诊断：页面上到底有哪些 form、是不是被导到了别的地方
        val forms = doc.select("form").map { f ->
            "id='${f.attr("id")}' class='${f.attr("class")}' action='${f.attr("action")}'"
        }
        val title = doc.title()
        val hint = when {
            forms.isEmpty() -> "页面里一个 <form> 都没有，可能被导到了维护页或移动版"
            forms.none { it.contains(FORM_ID) } -> "页面有 ${forms.size} 个表单但没有 #$FORM_ID：$forms"
            else -> "表单存在但 jsoup 选择器没命中：$forms"
        }
        return GdutException.Parse(
            what = "统一认证登录页",
            snippet = "url=$pageUrl title='$title' $hint; html=${html.take(400)}",
        )
    }
}
