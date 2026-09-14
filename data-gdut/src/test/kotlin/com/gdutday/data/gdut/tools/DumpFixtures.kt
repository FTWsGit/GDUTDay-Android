package com.gdutday.data.gdut.tools

import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.Term
import com.gdutday.data.gdut.auth.AuthServerClient
import com.gdutday.data.gdut.auth.AuthServerConfig
import com.gdutday.data.gdut.http.FormFields
import com.gdutday.data.gdut.http.SessionCookieJar
import com.gdutday.data.gdut.http.asBrowserXhr
import com.gdutday.data.gdut.jxfw.JxfwExamParser
import com.gdutday.data.gdut.jxfw.JxfwGradeParser
import com.gdutday.data.gdut.jxfw.JxfwScheduleParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 一次性 dump 工具：用真实会话抓取 jxfw 各接口的原始响应，落成 `.real` fixture。
 *
 * 同时直接探测 `xsAllKbList` 是否存活 —— 回答 T2.4「AUTO 命中 getDataList 时
 * 无法判断 A 接口是否该保留」。
 *
 * 输出：build/dump-fixtures/（人工确认无个人信息后再移入 test resources）。
 * 凭据来源同 verifyLogin（环境变量或根目录 secrets.properties）。
 */
public object DumpFixtures {

    private val SECTION = "=".repeat(72)

    @JvmStatic
    public fun main(args: Array<String>) {
        val creds = loadCredentials()
        if (creds == null) {
            println("缺少凭据（secrets.properties 或环境变量），退出。")
            exitProcess(2)
        }
        val (studentId, password) = creds
        val outDir = File("build/dump-fixtures").absoluteFile.apply { mkdirs() }
        println("输出目录: $outDir")

        // 登录拿到会话（密码只在这里用一次，不打印、不落盘）
        val session = AuthServerClient(
            baseClient(),
            AuthServerConfig(rejectNonUndergraduate = false),
        ).login(studentId, password)
        println("登录成功，cookie ${session.cookies.size} 个")

        // 用会话 cookie 构造带状态的客户端（与 JxfwClient 同构）
        val jar = SessionCookieJar(session.cookies)
        val http = baseClient().newBuilder().cookieJar(jar).build()
        val hosts = GdutHosts.PRODUCTION

        val termList = try {
            val body = get(http, hosts.jxfwTermList, hosts.jxfwDefaultReferer)
            File(outDir, "jxfw_term_list.real.html").writeText(body)
            println("[学期列表] ${body.length} 字符 → jxfw_term_list.real.html")
            JxfwScheduleParser // marker
            com.gdutday.data.gdut.jxfw.JxfwTermParser.parse(body)
        } catch (e: Exception) {
            println("[学期列表] 失败: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            exitProcess(1)
        }
        val term: Term = termList.current ?: termList.terms.firstOrNull() ?: run {
            println("无学期，退出")
            exitProcess(1)
        }
        println("当前学期: ${term.shortCode} (xnxqdm=${term.xnxqdm})")

        // ---------- 课表接口 B：getDataList 第 1 页（AUTO 首选） ----------
        dump(http, outDir, "jxfw_schedule_data_list_p1.real.json", "课表 getDataList 第1页") {
            post(http, hosts.jxfwScheduleDataList, JxfwScheduleParser.dataListForm(term, 1, 200), hosts.jxfwDefaultReferer)
        }

        // ---------- 课表接口 A：xsAllKbList（T2.4 存活探测） ----------
        try {
            val query = JxfwScheduleParser.allKbListQuery(term).entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            val aBody = get(http, "${hosts.jxfwScheduleAllKbList}?$query", hosts.jxfwScheduleAllKbReferer)
            File(outDir, "jxfw_schedule_all_kb_list.real.html").writeText(aBody)
            val rows = JxfwScheduleParser.parseAllKbList(aBody, term)
            println("[xsAllKbList] 存活，解析出 ${rows.size} 行 → jxfw_schedule_all_kb_list.real.html")
        } catch (e: Exception) {
            println("[xsAllKbList] 探测失败: ${e.javaClass.simpleName}: ${e.message?.take(300)}")
            File(outDir, "jxfw_schedule_all_kb_list.FAILED.txt")
                .writeText("${e.javaClass.name}: ${e.message}")
        }

        // ---------- 考试 ----------
        dump(http, outDir, "jxfw_exam_data_list.real.json", "考试 getDataList") {
            post(http, hosts.jxfwExamDataList, JxfwExamParser.form(term), hosts.jxfwDefaultReferer)
        }

        // ---------- 成绩（全部学期） ----------
        dump(http, outDir, "jxfw_score_data_list_all.real.json", "成绩 getDataList（全部学期）") {
            post(http, hosts.jxfwScoreDataList, JxfwGradeParser.form(null), hosts.jxfwDefaultReferer)
        }

        println(SECTION)
        println("完成。请人工检查 build/dump-fixtures/ 下文件不含个人信息后，")
        println("移动到 data-gdut/src/test/resources/fixtures/ 并更新解析器测试。")
        println(SECTION)
    }

    private fun dump(http: OkHttpClient, outDir: File, name: String, what: String, block: () -> String) {
        try {
            val body = block()
            File(outDir, name).writeText(body)
            println("[$what] ${body.length} 字符 → $name")
        } catch (e: Exception) {
            println("[$what] 失败: ${e.javaClass.simpleName}: ${e.message?.take(300)}")
            File(outDir, name.replace(".real.", ".FAILED."))
                .writeText("${e.javaClass.name}: ${e.message}")
        }
    }

    private fun get(http: OkHttpClient, url: String, referer: String): String {
        val request = Request.Builder().url(url).asBrowserXhr(referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get().build()
        return execute(http, request)
    }

    private fun post(http: OkHttpClient, url: String, params: Map<String, String>, referer: String): String {
        val origin = java.net.URI(url).let { "${it.scheme}://${it.host}" }
        val request = Request.Builder().url(url).asBrowserXhr(referer)
            .header("Origin", origin)
            .post(FormFields().addAll(params).toRequestBody())
            .build()
        return execute(http, request)
    }

    private fun execute(http: OkHttpClient, request: Request): String =
        http.newCall(request).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
            body
        }

    /** 本机 JDK 缺学校证书链，走 Windows 系统信任库（与 verifyLogin 的 jvmArg 等价）。 */
    private fun baseClient(): OkHttpClient {
        val trustManager = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {}
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** 环境变量优先；否则读根目录 secrets.properties（shell export 格式）。 */
    private fun loadCredentials(): Pair<String, String>? {
        val envId = System.getenv("GDUT_STUDENT_ID")?.trim().orEmpty()
        val envPwd = System.getenv("GDUT_PASSWORD").orEmpty()
        var id = envId
        var pwd = envPwd
        val file = File("secrets.properties")
        if (file.isFile) {
            for (line in file.readLines()) {
                val t = line.trim().removePrefix("export ").trim()
                val i = t.indexOf('=')
                if (i <= 0) continue
                val k = t.substring(0, i).trim()
                val v = t.substring(i + 1).trim().trim('\'', '"')
                if (k == "GDUT_STUDENT_ID" && id.isEmpty()) id = v
                if (k == "GDUT_PASSWORD" && pwd.isEmpty()) pwd = v
            }
        }
        return if (id.isNotEmpty() && pwd.isNotEmpty()) id to pwd else null
    }
}
