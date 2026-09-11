package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.Term

/**
 * 学期列表解析器。
 *
 * 数据源：`GET https://jxfw.gdut.edu.cn/xsksap!ksapList.action`（考试安排页面）
 *
 * 这个页面的学期下拉框长这样：
 * ```html
 * <select name="xnxqdm">
 *   <option value='202402'>2024-2025学年第二学期</option>
 *   <option value='202501' selected>2025-2026学年第一学期</option>
 * </select>
 * ```
 *
 * 旧 Java 后端的正则是 `<option value='(\d+)' selected>`，只取 selected 那一项。
 * 这里解析**全部** option，因为 App 需要学期切换下拉框；同时单独标出 selected 项作为当前学期。
 *
 * 正则比旧版宽松：引号可选（单/双/无）、属性间允许任意空白、`selected` 可以出现在
 * `value` 之前或之后（HTML 属性顺序不保证）。
 */
public object JxfwTermParser {

    /**
     * 学期解析结果。
     *
     * @property terms 全部学期，**按时间倒序**（最新学期在前），已去重。
     * @property current 页面标记为 `selected` 的学期。
     * @property names `学期短码 → 页面显示文本`，如 `"20251" -> "2025-2026学年第一学期"`。
     *   教务系统给的中文名比 [Term.displayName] 更权威（可能包含"春季/秋季"等措辞），
     *   UI 应优先用它。
     */
    public data class TermList(
        public val terms: List<Term>,
        public val current: Term?,
        public val names: Map<String, String> = emptyMap(),
    ) {
        public val isEmpty: Boolean get() = terms.isEmpty()

        /** 取显示名：优先教务系统的原文，回退到 [Term.displayName]。 */
        public fun nameOf(term: Term): String = names[term.shortCode] ?: term.displayName
    }

    private val OPTION_REGEX = Regex(
        """<option\b([^>]*)>([^<]*)</option>""",
        RegexOption.IGNORE_CASE,
    )
    private val VALUE_REGEX = Regex("""value\s*=\s*['"]?\s*(\d{5,6})\s*['"]?""", RegexOption.IGNORE_CASE)
    private val SELECTED_REGEX = Regex("""\bselected\b""", RegexOption.IGNORE_CASE)

    /**
     * 解析学期列表页。
     *
     * @throws GdutException.SessionExpired 响应是统一认证登录页
     * @throws GdutException.Parse 页面里一个学期 option 都没有 —— 几乎一定意味着会话失效或页面改版
     */
    public fun parse(html: String): TermList {
        if (looksLikeAuthPage(html)) {
            throw GdutException.SessionExpired(
                detail = "请求学期列表时被导回统一认证登录页。${html.take(200)}",
            )
        }

        val terms = LinkedHashMap<String, Term>()   // shortCode -> Term，用于去重并保持顺序
        val names = LinkedHashMap<String, String>()
        var current: Term? = null

        for (match in OPTION_REGEX.findAll(html)) {
            val attrs = match.groupValues[1]
            val text = match.groupValues[2].trim()
            val value = VALUE_REGEX.find(attrs)?.groupValues?.get(1) ?: continue
            val term = Term.parse(value) ?: continue
            // 只收看起来像学期的（避免把院系代码之类的 option 混进来）
            if (term.year < 2000 || term.year > 2100) continue

            terms.putIfAbsent(term.shortCode, term)
            if (text.isNotEmpty()) names.putIfAbsent(term.shortCode, text)
            if (current == null && SELECTED_REGEX.containsMatchIn(attrs)) current = term
        }

        if (terms.isEmpty()) {
            throw GdutException.Parse(
                what = "学期列表",
                snippet = "页面里没有找到任何学期 <option>。${html.take(400)}",
            )
        }

        // 兜底：没有 selected 就取最新的一个
        if (current == null) current = terms.values.maxOrNull()

        return TermList(
            terms = terms.values.sortedDescending(),
            current = current,
            names = names,
        )
    }

    /**
     * 从任意 HTML 里单独抠出"当前学期"。
     *
     * 用于那些顺带返回了学期下拉框的页面（考试安排页本身就是），
     * 省一次专门请求 [com.gdutday.data.gdut.GdutEndpoints.JXFW_TERM_LIST]。
     *
     * 只吞 [GdutException.Parse]（结构不认识 → 可选优化路径失败，返回 null）；
     * [GdutException.SessionExpired] 等其它异常**向上抛** —— 会话失效必须让
     * 上层走统一的重登流程，静默吞掉会伪装成"解析不到学期"。
     *
     * @return 解析失败返回 null；会话失效抛异常。
     */
    public fun parseCurrentOnly(html: String): Term? = try {
        parse(html).current
    } catch (e: GdutException.Parse) {
        null
    }

    private fun looksLikeAuthPage(html: String): Boolean =
        html.contains("pwdEncryptSalt", ignoreCase = true) ||
            (html.contains("authserver", ignoreCase = true) && html.contains("<form", ignoreCase = true))
}
