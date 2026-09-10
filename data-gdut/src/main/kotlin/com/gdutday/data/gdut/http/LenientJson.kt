package com.gdutday.data.gdut.http

import com.gdutday.core.model.GdutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 宽松 JSON 访问工具。
 *
 * ## 为什么不能用"正常的" JSON 解析
 *
 * 教务系统的响应有一堆不规范之处，全部实测过：
 *
 * | 现象 | 实测证据 |
 * |---|---|
 * | 返回 JSON 但 Content-Type 是 `text/html;charset=utf-8` | `POST /new/login` → `{"code":-1,...}` |
 * | 返回 JSON 但 Content-Type 是 `text/plain;charset=UTF-8` | `GET /checkNeedCaptcha.htl` → `{"isNeed":false}` |
 * | 会话过期时返回**登录页 HTML** 而不是 401 | jxfw 的 `!getDataList.action` 在未登录时 302 回 authserver |
 * | 字段值有时是数字、有时是字符串 | `zcj` 可能是 `87` 也可能是 `"87"` 或 `"优秀"` |
 * | 字段可能整体缺失 | 劳动教育课的 `zcj`/`cjjd` 在某些查询条件下为空 |
 *
 * 所以这里的策略是：**永远不因为一个字段格式不对就抛异常**，
 * 只有"整个响应根本不是 JSON"这种结构性问题才抛 [GdutException]。
 * 单个字段解析失败一律降级为 null / 空串，让上层决定怎么展示。
 */
public object LenientJson {

    /**
     * 全局 JSON 解析器。
     *
     * - `isLenient`：接受不带引号的 key、单引号字符串等非标准写法
     * - `ignoreUnknownKeys`：接口新增字段不应该让 App 崩
     * - `coerceInputValues`：`null` 赋给非空类型时回退默认值
     * - `allowSpecialFloatingPointValues`：教务处出现过 `NaN`
     */
    public val json: Json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        explicitNulls = false
    }

    /**
     * 解析响应体为 [JsonElement]。
     *
     * 会先做三件清洗：去掉 UTF-8 BOM、去掉首尾空白、去掉 JSONP 包裹（`callback({...})`）。
     *
     * @return 解析不出来返回 null。调用方通常应先检查 [looksLikeHtml] 以区分
     *   "会话过期"和"接口改版"两种完全不同的故障。
     */
    public fun parseOrNull(body: String?): JsonElement? {
        val cleaned = sanitize(body) ?: return null
        return runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
    }

    /** 解析为 [JsonObject]；不是对象则返回 null。 */
    public fun parseObjectOrNull(body: String?): JsonObject? = parseOrNull(body) as? JsonObject

    /** 解析为 [JsonArray]；不是数组则返回 null。 */
    public fun parseArrayOrNull(body: String?): JsonArray? = parseOrNull(body) as? JsonArray

    /**
     * 解析为 [JsonObject]，失败时抛 [GdutException]。
     *
     * 会先判断响应是不是 HTML：如果是，抛 [GdutException.SessionExpired]
     * （这是最常见的原因），否则抛 [GdutException.Parse] 并附上片段。
     *
     * @param what 出错时展示给用户的名词，如"课表"、"成绩"。
     */
    public fun requireObject(body: String?, what: String): JsonObject {
        if (body.isNullOrBlank()) {
            throw GdutException.Parse(what = what, snippet = "响应体为空")
        }
        if (looksLikeHtml(body)) {
            throw GdutException.SessionExpired(
                detail = "请求$what 时收到 HTML 页面而非 JSON，会话应已失效。片段: ${body.take(200)}",
            )
        }
        val element = parseOrNull(body)
        if (element is JsonObject) return element
        throw GdutException.Parse(
            what = what,
            snippet = "期望 JSON 对象，实际得到 ${element?.let { it::class.simpleName } ?: "无法解析的内容"}: ${sanitize(body)?.take(300)}",
        )
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 去 BOM、去空白、剥 JSONP 外壳。
     *
     * ⚠ **BOM 可能有多个**。实测统一认证登录页（fixture
     * `authserver_login_page_with_service.real.html`）开头是**两个连续的 U+FEFF**：
     * `\uFEFF\uFEFF<!DOCTYPE html><html class="root-main">`
     * 只剥一个的话，剩下那个会让所有 `startsWith("<!doctype")` 之类的判断失效。
     *
     * 而且 U+FEFF 在 Java/Kotlin 里**不算空白字符**（`Char.isWhitespace()` 返回 false），
     * 所以 `trim()` / `trimStart()` 也去不掉它，必须显式处理。
     *
     * 这个坑是跑真实 fixture 时才暴露出来的 —— 手写的合成 fixture 不会带 BOM。
     */
    private fun sanitize(body: String?): String? {
        if (body == null) return null
        var s = stripBomAndWhitespace(body)
        if (s.isEmpty()) return null
        // JSONP：callback({...}) 或 callback([...])
        if (!s.startsWith("{") && !s.startsWith("[")) {
            val open = s.indexOf('(')
            val close = s.lastIndexOf(')')
            if (open in 0 until close) {
                s = s.substring(open + 1, close).trim()
            }
        }
        return s
    }

    /**
     * 判断响应体是不是 HTML 页面。
     *
     * 教务系统在会话失效时不会返回 401，而是 302 到统一认证登录页并返回 200 + HTML。
     * 提前识别这一点，才能给出"请重新登录"而不是"接口改版了"的提示 ——
     * 这两者的处置方式完全不同。
     *
     * 判据刻意保守：只有出现明确的 HTML 标志才算，避免把含 `<` 的 JSON 字符串误判。
     */
    public fun looksLikeHtml(body: String?): Boolean {
        // 必须先剥 BOM：真实登录页开头有两个 U+FEFF，而它不算空白字符，
        // trimStart() 去不掉，会让下面所有 startsWith("<...") 判断失效。
        val s = body?.let { stripBomAndWhitespace(it) }?.lowercase() ?: return false
        if (s.isEmpty()) return false
        if (s.startsWith("{") || s.startsWith("[")) return false
        return s.startsWith("<!doctype html") ||
            s.startsWith("<html") ||
            s.startsWith("<head") ||
            (s.startsWith("<") && s.contains("</html>"))
    }

    /**
     * 去掉开头/结尾的所有 BOM（U+FEFF）与空白。
     *
     * 循环剥而不是只剥一次 —— 实测页面会带**两个** BOM。
     */
    private fun stripBomAndWhitespace(body: String): String {
        var start = 0
        var end = body.length
        while (start < end) {
            val c = body[start]
            if (c == '\uFEFF' || c.isWhitespace()) start++ else break
        }
        while (end > start) {
            val c = body[end - 1]
            if (c == '\uFEFF' || c.isWhitespace()) end-- else break
        }
        return body.substring(start, end)
    }

    /** 生成用于诊断信息展示的响应片段。 */
    public fun snippet(body: String?, maxLength: Int = 400): String {
        val s = sanitize(body) ?: return "<empty>"
        return if (s.length <= maxLength) s else s.take(maxLength) + "…(${s.length} chars total)"
    }
}


// ---------------------------------------------------------------------------
// 下面是**文件级顶层扩展函数**。刻意不放进 LenientJson object：
// Kotlin 的成员扩展函数在 object 外部无法用 `LenientJson.str(obj, key)` 调用，
// 只能写 `with(LenientJson) { obj.str(key) }`，解析器里几十次字段访问会变得极其啰嗦。
// 顶层扩展函数配合显式 import，调用点是 `row.str("kcmc")`，最自然。
// ---------------------------------------------------------------------------

/**
 * 取出 EasyUI DataGrid 风格的分页响应里的 `rows` 数组。
 *
 * 教务系统所有 `!getDataList.action` 接口都返回 `{ "total": N, "rows": [...] }`。
 *
 * @return `rows` 缺失或不是数组时返回空列表 —— **不抛异常**。
 *   "本学期没有考试安排"和"接口结构变了"都会走到这里，
 *   由调用方结合 [totalOf] 判断：`total > 0 但 rows 为空`才是真的异常。
 */
public fun rowsOf(obj: JsonObject?): JsonArray =
    obj?.get("rows") as? JsonArray ?: JsonArray(emptyList())

/** 分页响应的 `total`。缺失返回 -1（区别于"确实是 0 条"）。 */
public fun totalOf(obj: JsonObject?): Int = obj?.get("total")?.intOrNullCompat() ?: -1

// ------------------------------------------------------------------ 字段访问器

/**
 * 取字符串。
 *
 * 数字/布尔会被转成字符串（`87` → `"87"`），`JsonNull` 和缺失返回 [default]。
 * 这一条很重要：教务系统同一字段在不同学期可能是数字也可能是字符串。
 */
public fun JsonObject?.str(key: String, default: String = ""): String {
    val v = this?.get(key) ?: return default
    if (v is JsonNull) return default
    if (v is JsonPrimitive) {
        // 优先取原始字面量，避免 "87.0" 被 double 转换弄成 "87.0" 之外的形式
        v.contentIfString()?.let { return it }
        return v.content
    }
    return v.toString()
}

public fun JsonObject?.strOrNull(key: String): String? =
    this?.get(key)?.takeIf { it !is JsonNull }?.let {
        if (it is JsonPrimitive) it.content else it.toString()
    }

public fun JsonObject?.int(key: String): Int? = this?.get(key)?.intOrNullCompat()

public fun JsonObject?.long(key: String): Long? = this?.get(key)?.longOrNullCompat()

public fun JsonObject?.double(key: String): Double? = this?.get(key)?.doubleOrNullCompat()

public fun JsonObject?.bool(key: String): Boolean? = this?.get(key)?.booleanOrNullCompat()

public fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

public fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

/**
 * 取 `datas.<name>.rows` —— 研究生 ehall 接口的三层嵌套结构。
 * 本科生用不到，但保留以便将来扩展。
 */
public fun nestedRows(obj: JsonObject?, datasKey: String): JsonArray =
    obj?.obj("datas")?.obj(datasKey)?.let { rowsOf(it) } ?: JsonArray(emptyList())

/** JsonPrimitive 若是字符串则返回其内容（去引号），否则返回 null。 */
private fun JsonPrimitive.contentIfString(): String? = if (isString) content else null

private fun JsonElement.intOrNullCompat(): Int? {
    if (this is JsonNull) return null
    if (this !is JsonPrimitive) return null
    intOrNull?.let { return it }
    // "87" 或 "87.0" 这类字符串数字
    return content.trim().toDoubleOrNull()?.toInt()
}

private fun JsonElement.longOrNullCompat(): Long? {
    if (this is JsonNull || this !is JsonPrimitive) return null
    longOrNull?.let { return it }
    return content.trim().toDoubleOrNull()?.toLong()
}

private fun JsonElement.doubleOrNullCompat(): Double? {
    if (this is JsonNull || this !is JsonPrimitive) return null
    doubleOrNull?.let { return it }
    return content.trim().toDoubleOrNull()
}

private fun JsonElement.booleanOrNullCompat(): Boolean? {
    if (this is JsonNull || this !is JsonPrimitive) return null
    booleanOrNull?.let { return it }
    return when (content.trim().lowercase()) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> null
    }
}
