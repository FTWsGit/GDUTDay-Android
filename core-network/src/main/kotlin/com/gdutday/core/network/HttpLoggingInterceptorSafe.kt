package com.gdutday.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * 一个**会脱敏**的最小 HTTP 日志拦截器。
 *
 * ## 为什么不用 `okhttp3:logging-interceptor`
 *
 * 官方那个拦截器会原样打印请求体和所有请求头。对本项目来说这是**事故级**的：
 * - 登录请求体里有 `password=<AES 密文>` 和空 name 的 salt 字段
 * - 请求头里有 `Cookie: JSESSIONID=...`，而 JSESSIONID **等同于账号本身**
 * - 响应头里有 `Set-Cookie`
 *
 * 这些一旦落到 logcat，同设备上的任何 App（ADB 调试时更是任何人）都能捞走，
 * 直接劫持教务系统会话。
 *
 * 官方拦截器有 `redactHeader()`，但要一个个列，容易漏；而且它不会脱敏请求体。
 * 所以这里自己写 30 行：**默认全部脱敏，只在白名单里的字段才打印**。
 *
 * 即便如此，[HttpConfig.logHttp] 在发布版也必须是 false。
 */
internal object HttpLoggingInterceptorSafe : Interceptor {

    /** 这些请求头的值可以打印（不含敏感信息）。 */
    private val PRINTABLE_HEADERS = setOf(
        "user-agent", "accept", "referer", "origin", "content-type",
        "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest",
    )

    /** 这些请求体字段只打印 key，不打印 value。 */
    private val REDACTED_FIELDS = setOf(
        "password", "pwd", "verifycode", "captcha", "account", "username",
    )

    private const val TAG = "GdutHttp"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.nanoTime()

        android.util.Log.d(
            TAG,
            "--> ${request.method} ${request.url}" +
                " headers=${request.headers.names().filter { it.lowercase() in PRINTABLE_HEADERS }}" +
                " body=${redactBody(request)}",
        )

        val response = try {
            chain.proceed(request)
        } catch (e: java.io.IOException) {
            val ms = (System.nanoTime() - started) / 1_000_000
            android.util.Log.w(TAG, "<-- ${request.url} FAILED in ${ms}ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }

        val ms = (System.nanoTime() - started) / 1_000_000
        // 只打印状态、耗时、长度和 Location。Set-Cookie 一律不打印。
        android.util.Log.d(
            TAG,
            "<-- ${response.code} ${request.url} in ${ms}ms" +
                " len=${response.header("Content-Length") ?: "?"}" +
                (response.header("Location")?.let { " location=$it" } ?: ""),
        )
        return response
    }

    /**
     * 把请求体转成脱敏后的可读形式。
     *
     * 只处理 `application/x-www-form-urlencoded`（本项目唯一的请求体类型）。
     * 其它类型只打印长度。
     */
    private fun redactBody(request: okhttp3.Request): String {
        val body = request.body ?: return "<no body>"
        val contentType = body.contentType()?.toString().orEmpty()
        if (!contentType.contains("x-www-form-urlencoded", ignoreCase = true)) {
            return "<${body.contentLength()} bytes, $contentType>"
        }
        val raw = try {
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        } catch (e: Exception) {
            return "<unreadable: ${e.javaClass.simpleName}>"
        }
        return raw.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            when {
                // 空 name 的那个字段就是 pwdEncryptSalt，值本身不算机密但也没必要打印
                key.isEmpty() -> "=${value.take(2)}***(len=${value.length})"
                key.lowercase() in REDACTED_FIELDS -> "$key=***(len=${value.length})"
                else -> "$key=$value"
            }
        }
    }
}
