package com.gdutday.data.gdut.jxfw

import com.gdutday.data.gdut.http.LenientJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 从 HTML / JS 文本里抠出内嵌的 JSON。
 *
 * ## 为什么不用正则
 *
 * 旧 F# 库 `GDUT.ClassSchedule/Library.fs` 用的是：
 * ```fsharp
 * let pattern = @"(?<=var kbxx = )\[(.*?)\}];"
 * ```
 * 这个正则有三个问题：
 * 1. `\[(.*?)\}];` 要求数组**必须以 `}]` 结尾**，最后一个元素若不是对象（比如是个数字或字符串）就匹配不到；
 * 2. 懒惰匹配 `.*?` 遇到嵌套的 `}];` 会提前截断；
 * 3. 要求 `var kbxx = ` 后面的空格数量、分号位置完全固定，服务端稍微改下格式就失效。
 *
 * 这里改用**括号配对扫描**：从标记后第一个 `[` 或 `{` 开始，按深度计数走到配对的闭合符，
 * 并正确跳过字符串字面量与转义字符。这样嵌套多深、结尾是什么类型都不影响。
 */
public object JsonExtractor {

    /**
     * 从 [text] 中找到 [marker] 之后的第一个 JSON 值（数组或对象）并解析。
     *
     * @param marker 定位标记，如 `"var kbxx"`。大小写敏感。
     * @return 解析结果；找不到标记、找不到 JSON、或 JSON 非法时返回 null。
     */
    public fun extractAfter(text: String, marker: String): kotlinx.serialization.json.JsonElement? {
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null
        val slice = extractBalanced(text, markerIdx + marker.length) ?: return null
        return LenientJson.parseOrNull(slice)
    }

    /** 同 [extractAfter]，但要求结果是数组。 */
    public fun extractArrayAfter(text: String, marker: String): JsonArray? =
        extractAfter(text, marker) as? JsonArray

    /** 同 [extractAfter]，但要求结果是对象。 */
    public fun extractObjectAfter(text: String, marker: String): JsonObject? =
        extractAfter(text, marker) as? JsonObject

    /**
     * 从 [startIndex] 起扫描，返回第一个**完整配对**的 JSON 数组/对象子串。
     *
     * 会跳过起始位置之前的所有字符（赋值号、空白、换行），
     * 所以 `extractBalanced(html, idxOf("var kbxx") + 8)` 能正确处理
     * `var kbxx\n  =\n  [ {...} ];` 这种带换行的写法。
     *
     * @return 含首尾括号的子串；找不到或括号不配对时返回 null。
     */
    public fun extractBalanced(text: String, startIndex: Int): String? {
        if (startIndex < 0 || startIndex >= text.length) return null

        // 1. 找到起始括号
        var i = startIndex
        while (i < text.length && text[i] != '[' && text[i] != '{') {
            // 遇到分号或换行后紧跟其它语句，说明这个赋值是空的（`var kbxx = ;`）
            if (text[i] == ';') return null
            i++
        }
        if (i >= text.length) return null

        val open = text[i]
        val close = if (open == '[') ']' else '}'
        val start = i

        // 2. 深度扫描
        var depth = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) {
                            // 只接受与起始括号配对的那一个
                            return if (c == close) text.substring(start, i + 1) else null
                        }
                        if (depth < 0) return null
                    }
                }
            }
            i++
        }
        return null // 括号没闭合
    }

    /**
     * 从一段 JS/HTML 里抠出 `var xxx = <字面量>;` 的值。
     *
     * 用于 `var xnxqdm = "202501";` 这类内嵌配置。
     *
     * @return 去掉引号后的字符串；找不到返回 null。
     */
    public fun extractJsStringVar(text: String, name: String): String? {
        val patterns = listOf(
            Regex("""(?:var|let|const)\s+${Regex.escape(name)}\s*=\s*"([^"]*)"""),
            Regex("""(?:var|let|const)\s+${Regex.escape(name)}\s*=\s*'([^']*)'"""),
        )
        for (p in patterns) p.find(text)?.groupValues?.get(1)?.let { return it }
        return null
    }
}
